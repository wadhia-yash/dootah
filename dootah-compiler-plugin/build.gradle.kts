plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "dev.dootah"
version = rootProject.extra["dootahVersion"] as String

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
val jsStdlib: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    contractJar(project(":dootah-contract"))
    jsStdlib("org.jetbrains.kotlin:kotlin-stdlib-js:${libs.versions.kotlin.get()}@klib")
    // Fixtures compile against the real annotation, not a stub of it.
    implementation(project(":dootah-contract"))
    testImplementation(project(":dootah-annotations"))
    // compileOnly on purpose: the host compiler supplies these classes at run
    // time. Bundling them would put a second copy of the compiler onto the
    // compiler's own classpath.
    compileOnly(libs.kotlin.compiler.embeddable)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.compiler.embeddable)
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
    inputs.files(jsStdlib)

    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
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
