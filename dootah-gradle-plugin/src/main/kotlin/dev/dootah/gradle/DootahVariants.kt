package dev.dootah.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinSingleTargetExtension
import java.io.File

/** Names a variant for a single invocation, without editing the build file. */
internal const val VARIANT_PROPERTY = "dootahVariant"

/**
 * The build type Dootah falls back to when nothing was asked for.
 *
 * Not a guess about any particular app: `debug` is the build type the Android
 * Gradle plugin creates for every module, and it is the one Dootah worked on
 * for as long as it only knew the name `compileDebugKotlin`.
 */
private const val DEBUG_BUILD_TYPE = "debug"

private const val KOTLIN_EXTENSION = "kotlin"

/** The Android plugins whose variants Dootah can be pointed at. */
internal val ANDROID_PLUGIN_IDS = listOf("com.android.application", "com.android.library")

/** Where the compiler writes its account of a compilation, before any variant is chosen. */
internal const val DEFAULT_REPORTS_PATH = "dootah/reports"

/** Where the compiler writes bundle Kotlin, and the bundle task reads it. */
internal const val GENERATED_SOURCES_PATH = "dootah/generated/jsMain/kotlin"

internal const val EXTRACT_TASK = "dootahExtract"

/**
 * One variant of the host app, as Dootah may be asked to work on it.
 *
 * Identity only. Which Kotlin task compiles it is resolved later, from the
 * Kotlin plugin's own compilations, because at the moment the Android plugin
 * announces a variant the Kotlin compilation for it may not exist yet.
 */
internal class DootahVariant(
    /** The Android variant name, such as `debug` or `genericDebug`. */
    val name: String,
    /** The build type it was assembled from, such as `debug`. */
    val buildType: String?,
    /**
     * This variant's resource directories, as the Android plugin reports them.
     *
     * Null for a project with no Android variants. Taken from the variant rather
     * than from `src/main/res` because a flavoured app layers flavour resources
     * over the main ones, and a bundle that shipped only the main layer would
     * reference drawables the installed app resolves differently.
     */
    val resourceDirectories: Provider<out Iterable<*>>? = null,
)

/**
 * The Kotlin task that compiles [variantName], or null if nothing compiles it.
 *
 * Read from the Kotlin plugin's compilation for the variant, so the name comes
 * from the build rather than from a convention Dootah assumed. The fallback
 * only accepts a task that is really there, so an unusable variant is reported
 * as unusable instead of failing later with a missing task.
 */
internal fun kotlinCompileTaskName(project: Project, variantName: String): String? {

    val kotlin = project.extensions.findByName(KOTLIN_EXTENSION) as? KotlinSingleTargetExtension<*>

    val compilation = runCatching { kotlin?.target?.compilations?.findByName(variantName) }.getOrNull()
    if (compilation != null) return compilation.compileTaskProvider.name

    val conventional = "compile${variantName.replaceFirstChar(Char::uppercaseChar)}Kotlin"
    return conventional.takeIf { project.tasks.findByName(it) != null }
}

/**
 * The variant Dootah works on.
 *
 * One variant, for every task: a bundle is extracted from a variant's own
 * compilation and checked against the contract that same variant recorded. Two
 * variants either side of that comparison would be comparing an app against a
 * different app.
 */
internal fun selectDootahVariant(
    variants: List<DootahVariant>,
    requested: String?,
): DootahVariant {

    if (requested != null) {
        return variants.firstOrNull { it.name == requested }
            ?: throw GradleException(unknownVariantMessage(requested, variants))
    }

    val debuggable = variants.filter { it.buildType == DEBUG_BUILD_TYPE }

    return debuggable.singleOrNull()
        ?: variants.singleOrNull()
        ?: throw GradleException(chooseVariantMessage(debuggable.ifEmpty { variants }))
}

/** What the developer asked for, on the command line first so a single build can override the file. */
internal fun requestedVariant(project: Project, extension: DootahExtension): String? =
    project.providers.gradleProperty(VARIANT_PROPERTY).orNull ?: extension.variant.orNull

/**
 * Registers the Dootah workflow against one variant of this module.
 *
 * Every task in the workflow is wired from the same [DootahVariant]: the same
 * compilation supplies extraction's sources and classpath, the same compilation
 * supplies the contract fragments, and the bundle built from one is checked
 * against the other.
 */
internal fun registerDootahWorkflow(project: Project, discovered: List<DootahVariant>) {

    val usable = discovered.filter { kotlinCompileTaskName(project, it.name) != null }
    if (usable.isEmpty()) return

    val extension = project.extensions.getByType(DootahExtension::class.java)

    val selected = try {
        selectDootahVariant(usable, requestedVariant(project, extension))
    } catch (undecided: GradleException) {
        registerUndecidedWorkflow(project, undecided)
        return
    }

    val compileTaskName = requireNotNull(kotlinCompileTaskName(project, selected.name))
    val generatedSources = project.layout.buildDirectory.dir(GENERATED_SOURCES_PATH).get()

    registerExtractTask(project, compileTaskName, generatedSources)
    registerBundleTask(
        project = project,
        extractTaskName = EXTRACT_TASK,
        generatedSourceDirectory = generatedSources,
        compileTaskName = compileTaskName,
        contractFragments = project.layout.dir(
            reportDirectoryFor(project, extension, selected.name).map { File(it, "contract") }
        ),
        imageResources = imageResourcesOf(project, selected),
    )
}

/**
 * The drawables a bundle may reference, from the selected variant's own resources.
 *
 * Falls back to the conventional main source set only where there are no Android
 * variants to ask, which is the case the Gradle plugin's own fixtures build.
 */
private fun imageResourcesOf(project: Project, variant: DootahVariant): FileTree =
    variant.resourceDirectories
        ?.let { directories -> project.files(directories).asFileTree.matching { it.include("drawable*/**") } }
        ?: project.fileTree("src/main/res") { it.include("drawable*/**") }

/**
 * Where the compiler writes what it made of one compilation.
 *
 * Scoped by compilation because a flavoured module compiles the same screens
 * several times over, once per variant and again for its tests. Merged into one
 * directory, the recorded contract would describe a mixture of apps -- and would
 * claim the installed app can render screens that only exist in another flavour.
 */
internal fun reportDirectoryFor(
    project: Project,
    extension: DootahExtension,
    compilationName: String,
): Provider<String> =
    extension.reportDirectory
        .orElse(project.layout.buildDirectory.dir(DEFAULT_REPORTS_PATH).map { it.asFile.absolutePath })
        .map { it + File.separator + compilationName }

/**
 * Stands the workflow up as tasks that explain the choice that has to be made.
 *
 * Registering nothing was what the old task-name check did, and it is the worst
 * of the options: `tasks` shows no Dootah at all and there is nothing to run
 * that could say why. These carry the same names, so the failure arrives when
 * someone asks for the workflow rather than when they ask for anything at all.
 */
private fun registerUndecidedWorkflow(project: Project, undecided: GradleException) {

    listOf(EXTRACT_TASK, "dootahRecordContract", "dootahValidateBundle", "dootahBundle", "dootahPublish")
        .forEach { name ->
            project.tasks.register(name, DefaultTask::class.java) { task ->
                task.group = "dootah"
                task.description = "Unavailable until a variant is named; run it to see how"
                task.doFirst { throw undecided }
            }
        }
}

private fun unknownVariantMessage(requested: String, variants: List<DootahVariant>): String =
    "Dootah was asked to work on the variant \"$requested\", which this module does not build.\n" +
        "This module builds: ${variants.joinToString { it.name }}.\n" +
        "Name one of those in the app's build file:\n" +
        "    dootah {\n" +
        "        variant = \"${variants.firstOrNull()?.name ?: "debug"}\"\n" +
        "    }\n" +
        "or for a single build, pass -P$VARIANT_PROPERTY=<variant>."

private fun chooseVariantMessage(choices: List<DootahVariant>): String =
    "Dootah could not tell which variant of this app it should work on.\n" +
        "This module builds ${choices.size}: ${choices.joinToString { it.name }}.\n" +
        "A bundle is extracted from one variant and checked against the contract that " +
        "same variant recorded, so choosing for you would mean checking an app against " +
        "a different app.\n" +
        "Name the variant you ship from, in the app's build file:\n" +
        "    dootah {\n" +
        "        variant = \"${choices.first().name}\"\n" +
        "    }\n" +
        "or for a single build, pass -P$VARIANT_PROPERTY=${choices.first().name}."
