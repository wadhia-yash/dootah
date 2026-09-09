package dev.dootah.gradle

import dev.dootah.gradle.internal.BUNDLE_BUILD_NAME
import dev.dootah.gradle.internal.writeBundleBuild
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import java.io.File

/**
 * Adds the build that turns generated Kotlin into `bundle.js`.
 *
 * A settings plugin because only settings can introduce a build, and Dootah
 * needs a Kotlin/JS compilation that an Android application build cannot host.
 * This is the one line of setup Dootah cannot infer; a future `dootah init` is
 * expected to write it.
 */
class DootahSettingsPlugin : Plugin<Settings> {

    override fun apply(target: Settings) {

        val buildDirectory = File(target.rootDir, BUNDLE_BUILD_PATH)

        writeBundleBuild(
            buildDirectory = buildDirectory,
            kotlinVersion = SUPPORTED_KOTLIN_VERSION,
            dootahVersion = DOOTAH_VERSION,
        )

        // Named explicitly: an included build otherwise takes its name from
        // its directory, and the host looks it up by name.
        target.includeBuild(buildDirectory) { included ->
            included.name = BUNDLE_BUILD_NAME
        }
    }

    private companion object {
        const val BUNDLE_BUILD_PATH = "build/dootah/bundle-build"
    }
}

/**
 * The Dootah release these plugins belong to.
 *
 * Read from the jar so the compiler plugin, the annotation and the bundle
 * runtime cannot drift apart from the Gradle plugin that wires them.
 */
internal val DOOTAH_VERSION: String
    get() = DootahSettingsPlugin::class.java.`package`?.implementationVersion
        ?: "0.1.0-SNAPSHOT"
