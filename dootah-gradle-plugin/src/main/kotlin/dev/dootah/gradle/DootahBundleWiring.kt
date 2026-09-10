package dev.dootah.gradle

import dev.dootah.gradle.tasks.DootahBundleTask
import org.gradle.api.Project

private const val BUNDLE_RUNTIME_CONFIGURATION = "dootahBundleRuntime"

/**
 * Registers `dootahBundle`, which compiles the generated bundle Kotlin.
 *
 * The bundle's Kotlin/JS dependencies are requested as `@klib` artifacts
 * directly. Resolving them through Gradle metadata would need a configuration
 * carrying Kotlin/JS attributes, which is a lot of machinery for two fixed
 * files whose classified artifacts can simply be named.
 */
internal fun registerBundleTask(
    project: Project,
    extractTaskName: String,
    generatedSourceDirectory: org.gradle.api.file.Directory,
) {

    val bundleRuntime = project.configurations
        .maybeCreate(BUNDLE_RUNTIME_CONFIGURATION).apply {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

    project.dependencies.add(
        BUNDLE_RUNTIME_CONFIGURATION,
        "org.jetbrains.kotlin:kotlin-stdlib-js:$SUPPORTED_KOTLIN_VERSION@klib",
    )
    project.dependencies.add(
        BUNDLE_RUNTIME_CONFIGURATION,
        "dev.dootah:dootah-bundle-runtime-js:$DOOTAH_VERSION@klib",
    )

    val extension = project.extensions.getByType(DootahExtension::class.java)

    project.tasks.register("dootahBundle", DootahBundleTask::class.java) { task ->

        task.group = "dootah"
        task.description = "Builds the Dootah bundle from this app's @Bundlable functions"

        // Same build, so this ordering is guaranteed rather than hoped for.
        task.dependsOn(extractTaskName)

        task.generatedSourceDirectory.set(generatedSourceDirectory)
        task.kotlinCompilerClasspath.from(
            project.configurations.getByName(KOTLIN_COMPILER_CONFIGURATION)
        )
        task.bundleRuntimeClasspath.from(bundleRuntime)

        task.runtimeVersion.set(extension.runtimeVersion)
        task.bundleVersion.set(extension.bundleVersion)
        task.bundleUrl.set(extension.bundleUrl)

        task.outputDirectory.set(project.layout.buildDirectory.dir("dootah/out"))
    }
}
