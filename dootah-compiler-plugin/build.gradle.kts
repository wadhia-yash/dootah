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

        // The FIR checker API declares its receivers as context parameters,
        // so implementing it requires the language feature.
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

// The Kotlin/JS standard library, as the klib a bundle compiles against.
val jsStdlib: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    jsStdlib("org.jetbrains.kotlin:kotlin-stdlib-js:${libs.versions.kotlin.get()}@klib")
    // Fixtures compile against the real annotation, not a stub of it.
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
    inputs.file(pluginJar)

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
                "-Ddootah.js.stdlib=${jsStdlib.asPath}",
                "-Ddootah.runtime.sources=${runtimeSources.absolutePath}",
            )
        }
    )
    useJUnit()
}
