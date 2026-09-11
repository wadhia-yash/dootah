package dev.dootah.gradle.internal

import java.io.File

/** One screen the extraction pass lowered and generated bundle Kotlin for. */
data class LoweredScreen(
    val screenId: String,
    val functionName: String,
    val objectName: String,

    /**
     * Components of this screen that stay in the APK.
     *
     * Reported to the developer because they are the parts a published bundle
     * cannot change. Editing one and seeing nothing happen over the air is a
     * confusing afternoon that one build line prevents.
     */
    val nativeComponents: List<String>,
)

/** One reason a screen could not be bundled, as recorded by the compiler. */
data class RejectedConstruct(
    val functionName: String,
    val filePath: String,
    val sourceOffset: Int,
    val found: String,
    val remedy: String,
)

/**
 * Reads what the extraction pass produced.
 *
 * The compiler writes data and the build formats it. Extraction runs in its own
 * process, so a message printed there competes with the compiler's own output;
 * reported as a build failure it cannot be missed.
 */
internal fun readLoweredScreens(reportDirectory: File): List<LoweredScreen> {

    val directory = File(reportDirectory, "screens")
    if (!directory.isDirectory) return emptyList()

    return directory.listFiles()
        .orEmpty()
        .filter { it.extension == "properties" }
        .sortedBy { it.name }
        .mapNotNull { file ->

            val values = file.readLines()
                .mapNotNull { line ->
                    line.split("=", limit = 2).takeIf { it.size == 2 }
                        ?.let { it[0] to it[1] }
                }
                .toMap()

            val screenId = values["screenId"] ?: return@mapNotNull null

            LoweredScreen(
                screenId = screenId,
                functionName = values["functionName"] ?: screenId,
                objectName = values["objectName"] ?: screenId,
                nativeComponents = values["nativeComponents"]
                    .orEmpty()
                    .split(", ")
                    .filter { it.isNotBlank() },
            )
        }
}

internal fun readRejectedConstructs(reportDirectory: File): List<RejectedConstruct> {

    val directory = File(reportDirectory, "unsupported")
    if (!directory.isDirectory) return emptyList()

    return directory.listFiles()
        .orEmpty()
        .filter { it.extension == "txt" }
        .sortedBy { it.name }
        .flatMap { file -> parseRejections(file.readText()) }
}

private fun parseRejections(contents: String): List<RejectedConstruct> {

    val rejections = mutableListOf<RejectedConstruct>()
    var current = mutableMapOf<String, String>()

    contents.lineSequence().forEach { line ->
        when {
            line == "--" -> {
                current["found"]?.let { found ->
                    rejections += RejectedConstruct(
                        functionName = current["function"].orEmpty(),
                        filePath = current["file"].orEmpty(),
                        sourceOffset = current["offset"]?.toIntOrNull() ?: -1,
                        found = found,
                        remedy = current["remedy"].orEmpty(),
                    )
                }
                current = mutableMapOf()
            }

            line.contains("=") -> {
                val (key, value) = line.split("=", limit = 2)
                current[key] = value
            }
        }
    }

    return rejections
}

/**
 * Formats rejections as a build failure, resolving source offsets to line and
 * column.
 *
 * The offset alone is useless to a developer; the compiler records it because
 * only the build still has the file to resolve it against.
 */
internal fun describeRejections(rejections: List<RejectedConstruct>): String {

    val byFunction = rejections.groupBy { it.functionName }

    return buildString {
        byFunction.forEach { (function, reasons) ->

            appendLine("Dootah cannot bundle `${function.substringAfterLast('.')}`.")
            appendLine()

            reasons.forEach { reason ->
                appendLine("  ${location(reason)}")
                appendLine("  found:  ${reason.found}")
                appendLine("  remedy: ${reason.remedy}")
                appendLine()
            }
        }
        append(
            "The app is unaffected: `${byFunction.keys.first().substringAfterLast('.')}` " +
                "keeps rendering its native implementation."
        )
    }
}

private fun location(reason: RejectedConstruct): String {

    val file = File(reason.filePath)
    val name = file.name.ifEmpty { "unknown" }

    if (reason.sourceOffset < 0 || !file.isFile) return name

    val prefix = runCatching { file.readText().take(reason.sourceOffset) }.getOrNull()
        ?: return name

    val line = prefix.count { it == '\n' } + 1
    val column = prefix.length - prefix.lastIndexOf('\n')

    return "$name:$line:$column"
}
