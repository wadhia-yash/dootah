package dev.dootah.compiler.fir

import dev.dootah.compiler.generate.BundleSourceWriter
import dev.dootah.compiler.generate.sanitizeForIdentifier
import dev.dootah.compiler.model.BundleScreen
import java.io.File

/** Subdirectory of the report directory holding one file per lowered screen. */
internal const val SCREEN_METADATA_DIRECTORY = "screens"

/** Subdirectory of the report directory holding one file per rejected screen. */
internal const val UNSUPPORTED_DIRECTORY = "unsupported"

/** Package path the generated screen implementations are written under. */
private const val GENERATED_PACKAGE_PATH = "dev/dootah/generated"

/**
 * The bundle entry points. A fixed name because there is exactly one set of
 * them; a second lowered screen would overwrite this file, which is why the
 * build refuses to continue with more than one.
 */
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

    File(generatedDirectory, EXPORTS_FILE_NAME)
        .writeText(BundleSourceWriter.writeExports(screen))

    writeScreenMetadata(reportDirectory, screen)
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
    )

    File(directory, "${sanitizeForIdentifier(screen.screenId)}.properties")
        .writeText(lines.joinToString("\n", postfix = "\n"))
}

/**
 * Records why a screen could not be bundled, for the build to report.
 *
 * Written as data for the Gradle task to format rather than logged here: the
 * extraction pass runs in its own compiler process, and a message buried in that
 * process's output is far easier to miss than a build failure.
 */
internal fun writeUnsupportedReport(
    reportDirectory: File,
    screenId: String,
    reasons: List<UnsupportedConstruct>,
) {
    if (reasons.isEmpty()) return

    val directory = File(reportDirectory, UNSUPPORTED_DIRECTORY).apply { mkdirs() }

    val lines = reasons.flatMap { reason ->
        listOf(
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
