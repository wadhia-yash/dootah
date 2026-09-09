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
    }
}

dependencies {
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
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Ddootah.plugin.jar=${pluginJar.get().asFile.absolutePath}")
        }
    )
    useJUnit()
}
