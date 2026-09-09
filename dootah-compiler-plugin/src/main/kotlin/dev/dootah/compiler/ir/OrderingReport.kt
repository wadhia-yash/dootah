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
