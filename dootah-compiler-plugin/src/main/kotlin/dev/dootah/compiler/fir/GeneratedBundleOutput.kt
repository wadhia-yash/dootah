package dev.dootah.compiler.fir

import dev.dootah.compiler.generate.BundleSourceWriter
import dev.dootah.compiler.generate.ScreenEntry
import dev.dootah.compiler.generate.sanitizeForIdentifier
import dev.dootah.compiler.model.BundleScreen
import java.io.File

/** Subdirectory of the report directory holding one file per lowered screen. */
internal const val SCREEN_METADATA_DIRECTORY = "screens"

/** Subdirectory of the report directory holding one file per rejected screen. */
internal const val UNSUPPORTED_DIRECTORY = "unsupported"

/** Package path the generated screen implementations are written under. */
private const val GENERATED_PACKAGE_PATH = "dev/dootah/generated"

/** The bundle entry points, which dispatch to every screen in the app. */
private const val EXPORTS_FILE_NAME = "DootahExports.kt"

/**
 * Writes the generated Kotlin for a lowered screen, plus the metadata the build
 * needs to describe it.
 *
 * Generation lives with the compiler rather than in the Gradle plugin: deciding
 * what bundle Kotlin a screen becomes is a semantic question about the
 * developer's code, and the Gradle plugin has no business answering it.
 */
internal fun writeGeneratedBundle(
    generatedDirectory: File,
    reportDirectory: File,
    screen: BundleScreen,
) {
    val packageDirectory = File(generatedDirectory, GENERATED_PACKAGE_PATH)
    packageDirectory.mkdirs()

    File(packageDirectory, "${BundleSourceWriter.screenObjectName(screen)}.kt")
        .writeText(BundleSourceWriter.writeScreen(screen))

    writeScreenMetadata(reportDirectory, screen)
    writeExports(generatedDirectory, reportDirectory)
}

/**
 * Records the screen the build just generated.
 *
 * One file per screen so that concurrently running checkers never contend, and
 * so the build can count what was produced without parsing generated Kotlin.
 */
private fun writeScreenMetadata(reportDirectory: File, screen: BundleScreen) {

    val directory = File(reportDirectory, SCREEN_METADATA_DIRECTORY).apply { mkdirs() }

    val lines = listOf(
        "screenId=${screen.screenId}",
        "functionName=${screen.functionName}",
        "objectName=${BundleSourceWriter.screenObjectName(screen)}",
        "parameters=${screen.parameters.joinToString(",") { it.name }}",
        "callbacks=${screen.callbacks.joinToString(",")}",
        "actions=${screen.actions.joinToString(",") { it.name }}",
        // What the screen needs the installed app to have generated for it.
        // Separated by "," and never by ", ": this file is read back by the
        // build, and a space that crept into one of these once made every entry
        // after the first fail to match.
        "adapters=${screen.adapters.joinToString(",")}",
        "capabilities=${screen.capabilities.joinToString(",") { it.id }}",
        "handles=${screen.handles.joinToString(",")}",
        "resources=${screen.resources.joinToString(",")}",
    )

    File(directory, "${sanitizeForIdentifier(screen.screenId)}.properties")
        .writeText(lines.joinToString("\n", postfix = "\n"))
}

/**
 * Rewrites the entry points to cover every screen lowered so far.
 *
 * A checker is handed one declaration at a time and has no "end of module"
 * hook, so the file is rebuilt from the metadata directory after each screen.
 * The last screen of a compilation therefore writes a file naming all of them,
 * and sorting by id keeps the result identical for identical input.
 */
private fun writeExports(generatedDirectory: File, reportDirectory: File) {

    val screens = File(reportDirectory, SCREEN_METADATA_DIRECTORY)
        .listFiles()
        .orEmpty()
        .filter { it.extension == "properties" }
        .mapNotNull { file ->

            val values = file.readLines()
                .mapNotNull { line ->
                    line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }
                .toMap()

            val screenId = values["screenId"] ?: return@mapNotNull null

            ScreenEntry(
                screenId = screenId,
                objectName = values["objectName"]
                    ?: BundleSourceWriter.screenObjectName(screenId),
            )
        }
        .sortedBy { it.screenId }

    File(generatedDirectory, EXPORTS_FILE_NAME)
        .writeText(BundleSourceWriter.writeExports(screens))
}

/**
 * Records why an eligible screen could not be bundled, for the build to report.
 *
 * Written as data for the Gradle task to format rather than logged here: the
 * extraction pass runs in its own compiler process, and a message buried in that
 * process's output is far easier to miss than a build failure.
 */
internal fun writeUnsupportedReport(
    reportDirectory: File,
    screenId: String,
    reasons: List<UnsupportedConstruct>,
    forced: Boolean,
) {
    if (reasons.isEmpty()) return

    val directory = File(reportDirectory, UNSUPPORTED_DIRECTORY).apply { mkdirs() }

    val lines = reasons.flatMap { reason ->
        listOf(
            // Whether the developer asked for this screen by name. Discovery
            // finds far more functions than anyone marked by hand, and a
            // limitation Dootah ran into on its own is news rather than a
            // failure -- but one hit on a function someone explicitly asked to
            // bundle is exactly the failure they wanted to hear about.
            "forced=$forced",
            "function=${reason.functionName}",
            "file=${reason.filePath}",
            "offset=${reason.sourceOffset ?: -1}",
            "found=${reason.found}",
            "remedy=${reason.remedy}",
            "--",
        )
    }

    File(directory, "${sanitizeForIdentifier(screenId)}.txt")
        .writeText(lines.joinToString("\n", postfix = "\n"))
}
