package dev.dootah.compiler.ir

import dev.dootah.compiler.compat.*
import dev.dootah.contract.ContractJson
import dev.dootah.contract.InstalledContract
import dev.dootah.contract.ScreenContract
import java.io.File

/** Where the app's build leaves what it can be asked for. */
internal const val CONTRACT_DIRECTORY = "contract"

/**
 * Records what this build of the app can be asked for.
 *
 * One file per screen. A checker has no end-of-module hook and, more to the
 * point, an incremental build recompiles only the files that changed -- so a
 * single merged file written by whichever compilation ran last would list only
 * the screens in it, and a bundle validated against that would be refused for
 * needing components the app has had all along.
 *
 * Per-screen files accumulate across incremental builds instead, and the Gradle
 * task that assembles them is what decides which are current.
 */
internal fun writeContractFragment(
    reportDirectory: File,
    runtimeVersion: String,
    screen: ScreenContract,
) {
    val directory = File(reportDirectory, CONTRACT_DIRECTORY).apply { mkdirs() }

    val contract = InstalledContract(
        runtimeVersion = runtimeVersion,
        screens = listOf(screen),
    )

    File(directory, "${sanitizeForFileName(screen.id)}.json")
        .writeText(ContractJson.write(contract))
}

/** A screen id as a file name, by the same rule the other reports use. */
private fun sanitizeForFileName(id: String): String =
    id.map { character -> if (character.isLetterOrDigit()) character else '_' }.joinToString("")
