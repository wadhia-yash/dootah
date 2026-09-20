package dev.dootah.gradle.coverage

import dev.dootah.gradle.BUNDLE_KOTLIN_VERSION
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection

/**
 * Measures how much of an app Dootah could take over, without changing the app.
 *
 * Deliberately a different plugin from the one a host app applies. That one
 * attaches a compiler plugin to the app's own compilation, adds dependencies and
 * refuses any project not on Dootah's exact Kotlin version -- all correct for
 * shipping updates, all fatal for measuring an app that was never built with
 * Dootah in mind. An app that had to be modified to be measured would not be the
 * app any more.
 *
 * So this attaches nothing and adds nothing to the app. It reads the sources and
 * the classpath from the app's own Kotlin compilation and runs Dootah's analysis
 * over them in a separate process, using *Dootah's* compiler rather than the
 * app's. The app goes on building with whatever Kotlin version it already uses;
 * its source only has to be readable by Dootah's.
 *
 * The app's compile task is read by reflection rather than by type. The whole
 * point is to measure apps on Kotlin versions Dootah does not itself support,
 * and those apps load their own Kotlin Gradle plugin: a type named here would
 * either be missing from the init script's classpath or be a different class
 * from the one the app is using, and would match nothing either way.
 */
class DootahCoveragePlugin : Plugin<Project> {

    override fun apply(target: Project) {

        val compilerClasspath = target.configurations
            .maybeCreate(COMPILER_CONFIGURATION).apply {
                isCanBeConsumed = false
                isCanBeResolved = true
            }

        target.dependencies.add(
            COMPILER_CONFIGURATION,
            "org.jetbrains.kotlin:kotlin-compiler-embeddable:$BUNDLE_KOTLIN_VERSION",
        )

        // Dootah's own Compose plugin, not the app's.
        //
        // The analysis has to see Compose the way Dootah's real extraction pass
        // does, or the measurement describes the measurement rather than the
        // app. The app's own Compose plugin cannot be borrowed: it is built
        // against whatever Kotlin the app uses, and loading it into Dootah's
        // compiler is an ABI mismatch. The version-matched one gives the same
        // semantics without that.
        val composePlugin = target.configurations
            .maybeCreate(COMPOSE_PLUGIN_CONFIGURATION).apply {
                isCanBeConsumed = false
                isCanBeResolved = true
                isTransitive = false
            }

        target.dependencies.add(
            COMPOSE_PLUGIN_CONFIGURATION,
            "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:" +
                BUNDLE_KOTLIN_VERSION,
        )

        // Named rather than resolved: coverage has to run against a Dootah built
        // from this checkout, which is the version whose numbers are reported.
        val pluginJars = target.providers.gradleProperty(PLUGIN_JARS_PROPERTY)

        // Everything about the app's compilation is read through a provider,
        // resolved when the task graph is built rather than now. The Android
        // plugin creates its compile tasks long after this runs, so anything
        // that looked for one here would find nothing and silently score every
        // Android module zero -- the one wrong answer this tool must not give.
        val compileTask = target.provider { target.mainKotlinCompileTask() }

        target.tasks.register(TASK_NAME, DootahCoverageTask::class.java) { task ->

            task.group = "dootah"
            task.description = "Measures how much of this module Dootah could describe"

            // The app's own compilation first: generated sources -- KSP output,
            // view bindings, resource accessors -- do not exist until it has
            // run, and a module measured without them reports failures that are
            // artefacts of the measurement.
            task.dependsOn(target.provider { listOfNotNull(compileTask.orNull) })

            task.sources.from(
                target.provider {
                    compileTask.orNull?.fileCollection(SOURCE_ACCESSORS) ?: target.files()
                }
            )
            task.compileClasspath.from(
                target.provider {
                    compileTask.orNull?.fileCollection(LIBRARY_ACCESSORS) ?: target.files()
                }
            )

            task.kotlinCompilerClasspath.from(compilerClasspath)
            task.composePluginClasspath.from(composePlugin)
            task.dootahPluginJars.set(pluginJars)
            task.moduleName.set(target.path)
            task.outputDirectory.set(target.layout.buildDirectory.dir("dootah/coverage"))
        }
    }

    /**
     * The compilation that best represents this module's own production code.
     *
     * Matched by shape rather than by a list of names. A real app has product
     * flavours -- `compileGithubDebugKotlin`, `compileFdroidDebugKotlin` -- and a
     * fixed list of names would quietly measure nothing and report the app as
     * having no Compose in it, which is the most misleading answer available.
     *
     * "contains Kotlin" rather than "ends with Kotlin", because a multiplatform
     * module names its Android compilation `compileDebugKotlinAndroid` -- and a
     * matcher that missed it reported one such app as having no Compose at all,
     * across eighty modules, without a word of complaint.
     *
     * Tests are excluded because they are not what ships, and release variants
     * because they drag in signing and minification that have nothing to do with
     * this. Among equals the shortest name wins, which is the plainest variant.
     * Native and web compilations are excluded for the same reason a release
     * variant is: they are not the Android app being measured.
     */
    private fun Project.mainKotlinCompileTask(): Task? =
        tasks.names
            .filter { it.startsWith("compile") && it.contains("Kotlin") }
            .filterNot { name ->
                EXCLUDED_VARIANT_MARKERS.any { name.contains(it, ignoreCase = true) }
            }
            .sortedWith(compareByDescending<String> { it.contains("Debug") }.thenBy { it.length })
            .firstNotNullOfOrNull { runCatching { tasks.findByName(it) }.getOrNull() }

    /**
     * The first of [names] this task answers with a file collection.
     *
     * Several names because the accessor has moved between Kotlin Gradle plugin
     * versions, and the corpus spans several of them on purpose.
     */
    private fun Task.fileCollection(names: List<String>): FileCollection? =
        names.firstNotNullOfOrNull { name ->
            runCatching {
                javaClass.getMethod(name).invoke(this) as? FileCollection
            }.getOrNull()
        }

    private companion object {
        const val COMPILER_CONFIGURATION = "dootahCoverageCompiler"
        const val COMPOSE_PLUGIN_CONFIGURATION = "dootahCoverageCompose"
        const val PLUGIN_JARS_PROPERTY = "dootah.plugin.jars"
        const val TASK_NAME = "dootahCoverage"

        /** Never the app's own production code. */
        val EXCLUDED_VARIANT_MARKERS = listOf(
            "AndroidTest", "UnitTest", "TestFixtures", "Release", "Benchmark",
            // Multiplatform targets that are not this Android app.
            "Ios", "Js", "Wasm", "Native", "Macos", "Linux", "Mingw", "Metadata",
            "CommonMain", "Desktop",
        )

        val SOURCE_ACCESSORS = listOf("getSources", "getSource")
        val LIBRARY_ACCESSORS = listOf("getLibraries", "getClasspath")
    }
}
