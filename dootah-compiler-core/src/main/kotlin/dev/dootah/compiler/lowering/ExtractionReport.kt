package dev.dootah.compiler.lowering

import dev.dootah.compiler.generate.sanitizeForIdentifier
import dev.dootah.compiler.source.*
import java.io.File

/**
 * Records what extraction observed for one screen.
 *
 * One file per screen id rather than one shared file: checkers may run
 * concurrently, and a per-screen file removes the contention without needing a
 * lock, while keeping the output identical for identical input.
 */
fun writeExtractionReport(
    reportDirectory: File,
    screenId: String,
    functionName: String,
    body: ScreenBody,
) {
    val directory = File(reportDirectory, EXTRACTION_DIRECTORY).apply { mkdirs() }

    val lines = buildList {
        add("screen=$screenId")
        add("function=$functionName")
        add("hasConditional=${body.hasConditional}")
        add("hasStringInterpolation=${body.hasStringInterpolation}")
        body.resolvedCalls.forEach { add("call=$it") }
        body.literals.forEach { add("literal=$it") }
    }

    File(directory, "${sanitizeForIdentifier(screenId)}.txt")
        .writeText(lines.joinToString("\n", postfix = "\n"))
}

const val EXTRACTION_DIRECTORY = "extract"
