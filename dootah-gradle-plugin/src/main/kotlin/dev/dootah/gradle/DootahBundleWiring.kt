package dev.dootah.gradle

import dev.dootah.gradle.internal.BUNDLE_BUILD_NAME
import dev.dootah.gradle.internal.BUNDLE_MODULE_NAME
import dev.dootah.gradle.tasks.DootahBundleTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

private const val WEBPACK_TASK = ":jsBrowserProductionWebpack"

/**
 * Wires `dootahBundle` to the bundle build the settings plugin added.
 *
 * Fails with the missing line rather than silently registering no task: a build
 * where `dootahBundle` simply does not exist is far harder to diagnose than one
 * that says what to add.
 */
internal fun registerBundleTask(project: Project) {

    val bundleBuild = project.gradle.includedBuilds
        .firstOrNull { it.name == BUNDLE_BUILD_NAME }
        ?: throw GradleException(missingSettingsPluginMessage())

    project.tasks.register("dootahBundle", DootahBundleTask::class.java) { task ->

        task.group = "dootah"
        task.description = "Builds the Dootah bundle from this app's @Bundlable functions"

        task.dependsOn(project.gradle.includedBuild(BUNDLE_BUILD_NAME).task(WEBPACK_TASK))

        task.compiledBundle.set(
            File(
                bundleBuild.projectDir,
                "build/kotlin-webpack/js/productionExecutable/$BUNDLE_MODULE_NAME.js",
            )
        )
        task.outputDirectory.set(project.layout.buildDirectory.dir("dootah/out"))
    }
}

internal fun missingSettingsPluginMessage(): String =
    "Dootah's bundle build is missing, so the bundle cannot be compiled.\n" +
        "Add the Dootah settings plugin to settings.gradle.kts:\n" +
        "    plugins {\n" +
        "        id(\"dev.dootah.settings\") version \"$DOOTAH_VERSION\"\n" +
        "    }"
