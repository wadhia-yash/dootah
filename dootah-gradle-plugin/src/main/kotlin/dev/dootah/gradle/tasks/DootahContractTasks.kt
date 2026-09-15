package dev.dootah.gradle.tasks

import dev.dootah.contract.AdapterContract
import dev.dootah.contract.BundleRequirements
import dev.dootah.contract.ContractJson
import dev.dootah.contract.ContractValidation
import dev.dootah.contract.InstalledContract
import dev.dootah.contract.RuntimeVersion
import dev.dootah.contract.ScreenContract
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File

/**
 * Records what the app this build produced can be asked for.
 *
 * Run by the developer when they ship a build, and the file it writes is
 * committed beside the source. That is the whole trick: a bundle is published
 * from source that has moved on from the installed app, so the only trustworthy
 * account of what the app can do is one written down while it was being built.
 *
 * Deliberately untracked and never part of `build`. Writing into the source tree
 * from an ordinary build task would mean the record silently follows whatever
 * the source says today, which is exactly the thing it exists not to do.
 */
@UntrackedTask(because = "writes into the source tree, on request rather than as part of a build")
abstract class DootahRecordContractTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fragmentsDirectory: DirectoryProperty

    @get:OutputFile
    abstract val contractFile: RegularFileProperty

    @TaskAction
    fun record() {

        val contract = readContract(fragmentsDirectory.get().asFile)

        if (contract.screens.isEmpty()) {
            throw GradleException(
                "Dootah found no screens in this build, so there is nothing to record. " +
                    "Build the app first: the contract is written while the app is compiled."
            )
        }

        val file = contractFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(ContractJson.write(contract))

        logger.lifecycle(
            "Dootah recorded what this build can be asked for: " +
                "${contract.screens.size} screen(s) in ${file.path}. Commit it -- a bundle " +
                "is checked against it before it may publish."
        )
    }
}

/**
 * Refuses a bundle the installed app could not render.
 *
 * The check that turns a class of silent failure into a build error. A bundle
 * may rearrange, repeat and drop what the app has, and may change what it gives
 * it -- but only among the arguments the app's own source passes through. An
 * argument no call site ever supplied has no slot in the binary, so the value
 * would be dropped on the device and the screen would render as though nothing
 * had been published.
 *
 * Skipped, with a warning, when no contract has been recorded. Adoption should
 * not begin with a failure, and until an app has shipped there is nothing a
 * bundle could be incompatible with.
 */
@CacheableTask
abstract class DootahValidateBundleTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val requirementsDirectory: DirectoryProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun validate() {

        val contract = contractFile.orNull?.asFile?.takeIf { it.exists() }

        if (contract == null) {
            logger.warn(
                "Dootah has no recorded contract for this app, so this bundle is not " +
                    "checked against one. Run `dootahRecordContract` when you build the " +
                    "app you intend to ship, and commit what it writes."
            )
            reportFile.get().asFile.apply { parentFile.mkdirs() }.writeText("unchecked\n")
            return
        }

        val installed = ContractJson.readContract(contract.readText())
        val required = readRequirements(requirementsDirectory.get().asFile)

        val findings = ContractValidation.validate(installed, required)
        val fatal = findings.filter { finding -> finding.isFatal }

        reportFile.get().asFile.apply { parentFile.mkdirs() }.writeText(
            findings.joinToString("\n", postfix = "\n") { finding ->
                "${finding.code}: ${finding.render()}"
            }.ifBlank { "ok\n" }
        )

        findings.filterNot { it.isFatal }.forEach { logger.info(it.render()) }

        if (fatal.isEmpty()) return

        throw GradleException(
            buildString {
                appendLine("This bundle asks the installed app for things it has not got.")
                appendLine()
                fatal.forEach { finding -> appendLine("  " + finding.render()) }
                appendLine()
                appendLine(
                    "These are not layout changes. Publishing would leave the screens " +
                        "involved rendering their native implementations, or rendering " +
                        "without the values above. Ship a new build of the app, or take " +
                        "the change back out of the bundle."
                )
            }
        )
    }
}

/** Merges the per-screen fragments one compilation left behind. */
private fun readContract(directory: File): InstalledContract {

    val screens = directory.listFiles()
        .orEmpty()
        .filter { file -> file.extension == "json" }
        .flatMap { file -> ContractJson.readContract(file.readText()).screens }
        .mergedById()

    return InstalledContract(runtimeVersion = RuntimeVersion.CURRENT, screens = screens)
}

private fun readRequirements(directory: File): BundleRequirements {

    val screens = directory.listFiles()
        .orEmpty()
        .filter { file -> file.extension == "json" }
        .flatMap { file -> ContractJson.readRequirements(file.readText()).screens }

    return BundleRequirements(runtimeVersion = RuntimeVersion.CURRENT, screens = screens)
}

/**
 * One entry per screen, keeping the widest account of each.
 *
 * An incremental build recompiles some files and not others, so fragments from
 * several builds sit side by side. Two fragments for one screen mean one is
 * stale, and the union is the safe reading: claiming the app cannot do something
 * it can turns an ordinary update into a refusal.
 */
private fun List<ScreenContract>.mergedById(): List<ScreenContract> =
    groupBy { screen -> screen.id }
        .map { (id, duplicates) ->
            ScreenContract(
                id = id,
                adapters = duplicates.flatMap { it.adapters }
                    .groupBy { adapter -> adapter.id }
                    .map { (adapterId, uses) ->
                        AdapterContract(
                            id = adapterId,
                            supportedProps = uses.flatMap { it.supportedProps }.distinct().sorted(),
                        )
                    },
                capabilities = duplicates.flatMap { it.capabilities }.distinctBy { it.id },
                handles = duplicates.flatMap { it.handles }.distinct().sorted(),
                resources = duplicates.flatMap { it.resources }.distinct().sorted(),
                anchors = duplicates.flatMap { it.anchors }.distinct().sorted(),
                builders = duplicates.flatMap { it.builders }.distinct().sorted(),
                callbacks = duplicates.flatMap { it.callbacks }.distinct().sorted(),
            )
        }
        .sortedBy { screen -> screen.id }
