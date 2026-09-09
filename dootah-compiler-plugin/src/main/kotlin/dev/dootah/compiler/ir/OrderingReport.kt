package dev.dootah.compiler.ir

import java.io.File

/**
 * Records what the ordering guard observed, for the build to assert on.
 *
 * A file rather than a log line: the Phase 0 spikes and their regression tests
 * need an unambiguous artifact, and grepping build output would couple the test
 * to log formatting.
 */
internal fun writeOrderingReport(
    reportDirectory: File,
    ordering: ComposeOrdering,
    bundlableFunctionNames: List<String>,
) {
    reportDirectory.mkdirs()

    val lines = buildList {
        add("ordering=$ordering")
        add("bundlableCount=${bundlableFunctionNames.size}")
        bundlableFunctionNames.sorted().forEach { add("bundlable=$it") }
    }

    File(reportDirectory, ORDERING_REPORT_NAME)
        .writeText(lines.joinToString("\n", postfix = "\n"))
}

internal const val ORDERING_REPORT_NAME = "dootah-ordering.txt"
