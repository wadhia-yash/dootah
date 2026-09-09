package dev.dootah.compiler.ir

import java.io.File

internal const val INTERCEPTION_REPORT_NAME = "dootah-ordering.txt"

/**
 * Records what the compiler observed and did, for the build to assert on.
 *
 * A file rather than a log line: the spikes and their regression tests need an
 * unambiguous artifact, and grepping build output would couple the tests to log
 * formatting. Entries are sorted so the same input always produces the same
 * report.
 *
 * Describes **one compilation, not the module**. Under incremental compilation
 * an unchanged file is not recompiled, so a report can legitimately say zero
 * `@Bundlable` functions while the app's classes are all correctly intercepted
 * -- the previously compiled class is reused, interception included. This is a
 * diagnostic, never an inventory of what the app contains, and it is why bundle
 * extraction runs as its own non-incremental pass instead of reading anything
 * the app's compilation leaves behind.
 */
internal fun writeInterceptionReport(
    reportDirectory: File,
    ordering: ComposeOrdering,
    bundlableFunctionNames: List<String>,
    interceptedScreenIds: List<String>,
) {
    reportDirectory.mkdirs()

    val lines = buildList {
        add("ordering=$ordering")
        add("bundlableCount=${bundlableFunctionNames.size}")
        bundlableFunctionNames.sorted().forEach { add("bundlable=$it") }
        add("interceptedCount=${interceptedScreenIds.size}")
        interceptedScreenIds.sorted().forEach { add("intercepted=$it") }
    }

    File(reportDirectory, INTERCEPTION_REPORT_NAME)
        .writeText(lines.joinToString("\n", postfix = "\n"))
}
