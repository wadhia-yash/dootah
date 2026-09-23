package dev.dootah.contract

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Build metadata only; never part of the OTA wire contract.
 * A recompilation replaces ALL screens owned by a source, including an empty set.
 * Ownership paths are relative to the project so restored build outputs can move.
 */
object SourceContractFragments {
    fun replace(reportDirectory: File, sourceRoot: File, source: File, contract: InstalledContract) {
        val path = source.absoluteFile.normalize().relativeTo(sourceRoot.absoluteFile.normalize()).invariantSeparatorsPath
        val key = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        val contracts = File(reportDirectory, "contract").apply { mkdirs() }
        val owners = File(reportDirectory, "contract-sources").apply { mkdirs() }
        writeAtomic(File(owners, "$key.source"), path)
        val fragment = File(contracts, "$key.json")
        if (contract.screens.isEmpty()) {
            check(!fragment.exists() || fragment.delete()) { "Could not remove obsolete contract fragment: $fragment" }
        } else writeAtomic(fragment, ContractJson.write(contract))
    }

    /** Also removes legacy unowned fragments, which cannot safely describe a current APK. */
    fun prune(reportDirectory: File, sourceRoot: File, currentSources: Set<File>? = null) {
        val contracts = File(reportDirectory, "contract")
        val owners = File(reportDirectory, "contract-sources")
        val current = currentSources?.map { it.absoluteFile.normalize() }?.toSet()
        val retained = mutableSetOf<String>()
        owners.listFiles().orEmpty().filter { it.extension == "source" }.forEach { owner ->
            val source = sourceRoot.resolve(owner.readText()).absoluteFile.normalize()
            if (source.isFile && (current == null || source in current)) retained += owner.nameWithoutExtension
            else {
                File(contracts, "${owner.nameWithoutExtension}.json").delete()
                owner.delete()
            }
        }
        contracts.listFiles().orEmpty().filter { it.extension == "json" && it.nameWithoutExtension !in retained }
            .forEach { check(it.delete()) { "Could not remove obsolete contract fragment: $it" } }
    }

    private fun writeAtomic(target: File, text: String) {
        val temporary = Files.createTempFile(target.parentFile.toPath(), ".contract-", ".tmp")
        try {
            Files.writeString(temporary, text)
            Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }
}
