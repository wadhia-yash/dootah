package dev.dootah.gradle.internal

import java.io.File

/** What the compiler made of one Compose function it found. */
data class DiscoveredFunction(
    val fqName: String,
    val outcome: String,
    val reason: String?,
    val forced: Boolean,
)

/** Reads the record the extraction pass wrote for every Compose function. */
internal fun readDiscovery(reportDirectory: File): List<DiscoveredFunction> {

    val directory = File(reportDirectory, "discovery")
    if (!directory.isDirectory) return emptyList()

    return directory.listFiles()
        .orEmpty()
        .filter { it.extension == "txt" }
        .sortedBy { it.name }
        .mapNotNull { file ->

            val values = file.readLines()
                .mapNotNull { line ->
                    line.split("=", limit = 2).takeIf { it.size == 2 }
                        ?.let { it[0] to it[1] }
                }
                .toMap()

            val fqName = values["fqName"] ?: return@mapNotNull null

            DiscoveredFunction(
                fqName = fqName,
                outcome = values["outcome"] ?: "INELIGIBLE",
                reason = values["reason"],
                forced = values["forced"].toBoolean(),
            )
        }
}

/**
 * One line of build output saying how much of this app can move over the air.
 *
 * The number that matters once discovery is automatic. With an annotation on
 * every screen a developer already knew the answer, because they had written it
 * out by hand; now they have not, and a build that said nothing would leave them
 * guessing which of their screens an update can actually reach.
 *
 * The reasons are ranked, because "what would unblock the most screens" is the
 * question the number immediately raises.
 */
internal fun summarise(
    discovered: List<DiscoveredFunction>,
    screens: List<LoweredScreen>,
): String {

    if (discovered.isEmpty()) {
        return "Dootah found no Compose functions in this module."
    }

    val lowered = discovered.count { it.outcome == "LOWERED" }
    val rejected = discovered.count { it.outcome == "REJECTED" }
    val ineligible = discovered.size - lowered - rejected

    val withNativeParts = screens.count { it.nativeComponents.isNotEmpty() }

    return buildString {
        append(
            "Dootah: ${discovered.size} Compose functions, " +
                "$lowered updatable over the air"
        )
        if (withNativeParts > 0) append(" ($withNativeParts with native parts)")
        append(", $rejected not yet describable, $ineligible out of scope.")

        if (rejected > 0) {
            append("\n  run with --info to see what stopped the $rejected.")
        }

        val blockers = discovered
            .filter { it.outcome == "INELIGIBLE" && it.reason != null }
            .groupingBy { it.reason!! }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_REASONS)

        if (blockers.isNotEmpty()) {
            append("\n  out of scope: ")
            append(blockers.joinToString(", ") { "${it.key.lowercase()} ${it.value}" })
        }
    }
}

/** Enough to act on, few enough to stay one line. */
private const val TOP_REASONS = 4
