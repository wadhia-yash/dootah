import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "dev.dootah"
version = rootProject.extra["dootahVersion"] as String

val backendFamily = project.name.substringAfter("-kotlin-", "2.3")
val backendMatrix = Properties().apply {
    rootProject.file("gradle/compiler-backends.properties").inputStream().use { load(it) }
}
val adapterDefinitions = Properties().apply {
    rootProject.file("gradle/compiler-adapters.properties").inputStream().use { load(it) }
}
val (backendVersion, adapterSource, annotationSource) = adapterDefinitions.getProperty(backendFamily).split(",")
// Matrix probes change the host compiler, never the adapter's compile classpath.
// Probe jars are deliberately unpublishable: passing tests must precede promotion.
val probeVersion = providers.gradleProperty("dootahCompatibilityProbe").orNull
val testCompilerVersion = probeVersion ?: backendVersion
if (probeVersion != null) {
    tasks.withType<org.gradle.api.publish.maven.tasks.AbstractPublishToMaven>().configureEach {
        doFirst { error("Compatibility probe artifacts cannot be published") }
    }
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(rootProject.file("dootah-compiler-plugin/src/main/kotlin"))
    adapterSource.split('+').forEach { kotlin.srcDir(rootProject.file("dootah-compiler-plugin/src/backend-$it/kotlin")) }
    kotlin.srcDir(rootProject.file("dootah-compiler-plugin/src/$annotationSource/kotlin"))
    kotlin.srcDir(rootProject.file("dootah-compiler-plugin/src/backend-common/kotlin"))
}
sourceSets.named("main") {
    // The service a backend advertises is part of its ABI: which registration
    // interface the running compiler orders plugins by differs by line, so the
    // service file lives with the adapter rather than with the shared code.
    resources.setSrcDirs(
        listOf(
            rootProject.file("dootah-compiler-plugin/src/main/resources"),
            rootProject.file("dootah-compiler-plugin/src/backend-${adapterSource.substringBefore('+')}/resources"),
        )
    )
}
kotlin.sourceSets.named("test") {
    kotlin.srcDir(rootProject.file("dootah-compiler-plugin/src/test/kotlin"))
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        // Kotlin's compiler plugin API is explicitly experimental. Opting in
        // here rather than at every use site keeps the opt-in a property of
        // the module, which is where the version coupling actually lives.
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")

        // Lowering reads resolved FIR declarations -- a parameter's type, a
        // function's body -- which the compiler marks as internal to discourage
        // reaching past symbols. Reading them is the job: a checker is handed
        // the declaration, and there is no symbol-level view of a body.
        optIn.add("org.jetbrains.kotlin.fir.symbols.SymbolInternals")
        // The legacy argument bridge restores parameter indices explicitly.
        // Transitional compilers retain that API but require an opt-in.
        if (adapterSource.contains("scalar-modern")) {
            optIn.add("org.jetbrains.kotlin.ir.declarations.DelicateIrParameterIndexSetter")
        }

        // The FIR checker API declares its receivers as context parameters,
        // so implementing it requires the language feature.
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

/**
 * The contract module on its own, for the tests.
 *
 * A real build resolves it transitively from this plugin's own POM onto the
 * compiler's plugin classpath. The tests build that classpath by hand, so they
 * need the jar as a file -- and without it the plugin loads and then fails
 * inside the compiler, which is a much worse place to find out.
 */
val contractJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

// The Kotlin/JS standard library, as the klib a bundle compiles against.
val fixtureClasspath by configurations.creating
val composeCompiler by configurations.creating
val composeRuntime by configurations.creating

val jsStdlib: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    composeCompiler("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:$testCompilerVersion")
    composeRuntime("org.jetbrains.compose.runtime:runtime-desktop:1.7.3")
    fixtureClasspath("org.jetbrains.kotlin:kotlin-stdlib:$testCompilerVersion")
    contractJar(project(":dootah-contract"))
    contractJar(project(":dootah-compiler-core"))
    jsStdlib("org.jetbrains.kotlin:kotlin-stdlib-js:$testCompilerVersion@klib")
    // Fixtures compile against the real annotation, not a stub of it.
    implementation(project(":dootah-contract"))
    implementation(project(":dootah-compiler-core"))
    testImplementation(project(":dootah-annotations"))
    // compileOnly on purpose: the host compiler supplies these classes at run
    // time. Bundling them would put a second copy of the compiler onto the
    // compiler's own classpath.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$backendVersion")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$testCompilerVersion")
}

publishing {
    publications.create<MavenPublication>("maven") { from(components["java"]) }
}

tasks.test {
    // The tests attach the plugin the same way a real build does: as a jar on
    // the compiler's plugin classpath. Passing classes directories instead
    // would skip the service-loader registration that wiring depends on.
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    val annotationJar = project(":dootah-annotations").tasks
        .named<org.gradle.jvm.tasks.Jar>("jar").flatMap { it.archiveFile }
    inputs.file(pluginJar)
    inputs.file(annotationJar)
    inputs.files(contractJar)

    // Guard the shipped API and its documentation against obsolete opt-in paths.
    val apiSources = rootProject.fileTree(rootProject.projectDir) {
        include("dootah-*/src/main/**", "dootah-*/src/jsMain/**")
        include("*.md", "examples/**/*.md", "validation/**/*.md")
        exclude("**/build/**")
    }
    inputs.files(apiSources)

    // Generated bundle Kotlin is compiled against the real bundle runtime
    // sources, so the test proves the generator's output works with the code it
    // will actually ship beside -- not with a stand-in.
    val runtimeSources = rootProject.file("dootah-bundle-runtime/src/jsMain/kotlin")
    inputs.dir(runtimeSources)
    inputs.files(jsStdlib, fixtureClasspath, composeCompiler, composeRuntime)

    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Ddootah.backend.ordering=${adapterDefinitions.getProperty(backendFamily).split(',').last()}",
                "-Ddootah.backend.family=$backendFamily",
                "-Ddootah.backend.probe=${probeVersion.orEmpty()}",
                "-Ddootah.compose.compiler=${composeCompiler.asPath}",
                "-Ddootah.compose.runtime=${composeRuntime.asPath}",
                "-Ddootah.fixture.classpath=${fixtureClasspath.asPath}${File.pathSeparator}${annotationJar.get().asFile.absolutePath}",
                "-Ddootah.plugin.jar=${pluginJar.get().asFile.absolutePath}",
                "-Ddootah.annotations.jar=${annotationJar.get().asFile.absolutePath}",
                "-Ddootah.repository=${rootProject.projectDir.absolutePath}",
                "-Ddootah.contract.jar=${contractJar.asPath}",
                "-Ddootah.js.stdlib=${jsStdlib.asPath}",
                "-Ddootah.runtime.sources=${runtimeSources.absolutePath}",
            )
        }
    )
    useJUnit()
}

val backendMetadata = tasks.register("generateBackendMetadata") {
    val destination = layout.buildDirectory.file("generated/backend-resources/dev/dootah/backend-versions.txt")
    val versions = probeVersion ?: backendMatrix.getProperty(backendFamily, "")
    inputs.property("versions", versions)
    outputs.file(destination)
    doLast {
        destination.get().asFile.apply { parentFile.mkdirs(); writeText(versions) }
    }
}
tasks.processResources {
    dependsOn(backendMetadata)
    from(layout.buildDirectory.dir("generated/backend-resources"))
}
