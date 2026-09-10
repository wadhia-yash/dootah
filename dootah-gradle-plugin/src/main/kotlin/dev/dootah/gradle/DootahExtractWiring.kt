package dev.dootah.gradle

import dev.dootah.gradle.tasks.DootahExtractTask
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * KSP is deliberately left out of the extraction pass.
 *
 * Its processors already ran, and their output is on the source path. Running
 * them again would regenerate the same files from a second compilation for no
 * benefit.
 */
private const val KSP_PLUGIN_MARKER = "symbol-processing"

/** Shared with the bundle task, which runs the same compiler. */
internal const val KOTLIN_COMPILER_CONFIGURATION = "dootahKotlinCompiler"

/**
 * Registers `dootahExtract` against a host app's Kotlin compilation.
 *
 * The task's inputs are read from that compilation rather than rebuilt, so
 * extraction sees the same sources and the same classpath the app itself is
 * compiled with.
 */
internal fun registerExtractTask(
    project: Project,
    compileTaskName: String,
    generatedSourceDirectory: org.gradle.api.file.Directory,
) {

    val compilerClasspath = project.configurations
        .maybeCreate(KOTLIN_COMPILER_CONFIGURATION).apply {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

    project.dependencies.add(
        KOTLIN_COMPILER_CONFIGURATION,
        "org.jetbrains.kotlin:kotlin-compiler-embeddable:$SUPPORTED_KOTLIN_VERSION",
    )

    val compileTask = project.tasks.named(compileTaskName, KotlinJvmCompile::class.java)

    project.tasks.register("dootahExtract", DootahExtractTask::class.java) { task ->

        task.group = "dootah"
        task.description = "Analyses @Bundlable functions and extracts the Dootah bundle model"

        // Generated sources -- KSP output, resources, view bindings -- only
        // exist once the app's own compilation has run.
        task.dependsOn(compileTask)

        task.sources.from(compileTask.map { it.sources })
        task.compileClasspath.from(compileTask.map { it.libraries })
        task.compilerPluginClasspath.from(
            compileTask.map { compile ->
                compile.pluginClasspath.filter { jar ->
                    !jar.name.contains(KSP_PLUGIN_MARKER)
                }
            }
        )
        task.kotlinCompilerClasspath.from(compilerClasspath)
        task.jvmTarget.set(
            compileTask.flatMap { compile ->
                compile.compilerOptions.jvmTarget.map { it.target }
            }
        )
        task.outputDirectory.set(project.layout.buildDirectory.dir("dootah/extract"))
        task.generatedSourceDirectory.set(generatedSourceDirectory)
    }
}
