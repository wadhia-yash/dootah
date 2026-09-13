package dev.dootah.compiler.fir

import dev.dootah.compiler.generate.sanitizeForIdentifier
import dev.dootah.contract.IneligibleReason
import java.io.File

/** Subdirectory of the report directory holding one file per Compose function seen. */
internal const val DISCOVERY_DIRECTORY = "discovery"

/** What became of one Compose function. */
internal enum class DiscoveryOutcome {

    /** Bundled: this screen can be replaced over the air. */
    LOWERED,

    /** Eligible, but something in the body is not describable yet. */
    REJECTED,

    /** Not a shape Dootah can ever take over, or excluded deliberately. */
    INELIGIBLE,
}

/**
 * Records every Compose function the extraction pass looked at.
 *
 * Written for all of them, not only the ones that worked, because the number
 * that matters when discovery is automatic is the proportion -- how much of an
 * app can move over the air, and what is standing in the way of the rest. With
 * an annotation on every screen that question did not arise; a developer had
 * already answered it by hand, one function at a time.
 *
 * One file per function so that concurrently running checkers never contend.
 */
internal fun writeDiscoveryRecord(
    reportDirectory: File,
    fqName: String,
    outcome: DiscoveryOutcome,
    reason: IneligibleReason? = null,
    forced: Boolean = false,
    adapters: Int = 0,
) {
    val directory = File(reportDirectory, DISCOVERY_DIRECTORY).apply { mkdirs() }

    val lines = buildList {
        add("fqName=$fqName")
        add("outcome=$outcome")
        reason?.let { add("reason=$it") }
        add("forced=$forced")
        // How much of a lowered screen is still native. A screen with no
        // adapters is describable end to end; one with adapters is a remote
        // layout around components the APK keeps.
        add("adapters=$adapters")
    }

    File(directory, "${sanitizeForIdentifier(fqName)}.txt")
        .writeText(lines.joinToString("\n", postfix = "\n"))
}
