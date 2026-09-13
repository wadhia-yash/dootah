package dev.dootah.gradle

import dev.dootah.gradle.tasks.DootahBundleTask
import dev.dootah.gradle.tasks.DootahRecordContractTask
import dev.dootah.gradle.tasks.DootahValidateBundleTask
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

    // Where the app's own compilation left its account of what it can be asked
    // for, and where the developer keeps the copy that outlives this build.
    val fragments = project.layout.buildDirectory.dir("dootah/reports/contract")
    val recorded = project.layout.projectDirectory.file("dootah/contract.json")

    project.tasks.register("dootahRecordContract", DootahRecordContractTask::class.java) { task ->
        task.group = "dootah"
        task.description = "Records what the app you are about to ship can be asked for"
        task.fragmentsDirectory.set(fragments)
        task.contractFile.set(recorded)
    }

    val validate = project.tasks.register(
        "dootahValidateBundle",
        DootahValidateBundleTask::class.java,
    ) { task ->
        task.group = "dootah"
        task.description = "Checks this bundle against the app it will be delivered to"
        task.dependsOn(extractTaskName)
        task.requirementsDirectory.set(
            project.layout.buildDirectory.dir("dootah/extract/requirements")
        )
        task.contractFile.set(recorded)
        task.reportFile.set(project.layout.buildDirectory.file("dootah/contract-check.txt"))
    }

    project.tasks.register("dootahBundle", DootahBundleTask::class.java) { task ->

        task.group = "dootah"
        task.description = "Builds the Dootah bundle from this app's Compose functions"

        // Through validation rather than straight to extraction, so that a
        // bundle the installed app could not render is not something anyone has
        // to remember to check for.
        task.dependsOn(validate)

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
