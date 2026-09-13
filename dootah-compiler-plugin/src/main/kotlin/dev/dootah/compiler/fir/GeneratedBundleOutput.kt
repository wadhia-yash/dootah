package dev.dootah.compiler.fir

import dev.dootah.compiler.generate.BundleSourceWriter
import dev.dootah.compiler.generate.ScreenEntry
import dev.dootah.compiler.generate.sanitizeForIdentifier
import dev.dootah.compiler.model.BundleScreen
import dev.dootah.compiler.model.BundleUi
import dev.dootah.contract.AdapterUse
import dev.dootah.contract.BundleRequirements
import dev.dootah.contract.CapabilityContract
import dev.dootah.contract.ContractJson
import dev.dootah.contract.RuntimeVersion
import dev.dootah.contract.ScreenRequirements
import java.io.File

/** Subdirectory of the report directory holding one file per lowered screen. */
internal const val SCREEN_METADATA_DIRECTORY = "screens"

/** Subdirectory of the report directory holding one file per rejected screen. */
internal const val UNSUPPORTED_DIRECTORY = "unsupported"

/** Where the extraction pass leaves what each screen needs the app to have. */
internal const val REQUIREMENTS_DIRECTORY = "requirements"

/**
 * Where the regions a screen kept native are recorded.
 *
 * Separate from `unsupported/`, and the separation is the point: that directory
 * holds the screens Dootah could not take on, this one holds the parts of the
 * screens it did. Counting them together would put a screen that updates fine
 * with one native icon in it next to a screen that does not update at all.
 */
internal const val DEGRADED_DIRECTORY = "degraded"

/** Package path the generated screen implementations are written under. */
private const val GENERATED_PACKAGE_PATH = "dev/dootah/generated"

/** The bundle entry points, which dispatch to every screen in the app. */
private const val EXPORTS_FILE_NAME = "DootahExports.kt"

/**
 * Writes the generated Kotlin for a lowered screen, plus the metadata the build
 * needs to describe it.
 *
 * Generation lives with the compiler rather than in the Gradle plugin: deciding
 * what bundle Kotlin a screen becomes is a semantic question about the
 * developer's code, and the Gradle plugin has no business answering it.
 */
internal fun writeGeneratedBundle(
    generatedDirectory: File,
    reportDirectory: File,
    screen: BundleScreen,
) {
    val packageDirectory = File(generatedDirectory, GENERATED_PACKAGE_PATH)
    packageDirectory.mkdirs()

    File(packageDirectory, "${BundleSourceWriter.screenObjectName(screen)}.kt")
        .writeText(BundleSourceWriter.writeScreen(screen))

    writeScreenMetadata(reportDirectory, screen)
    writeRequirementsFragment(reportDirectory, screen)
    writeExports(generatedDirectory, reportDirectory)
}

/**
 * Records the screen the build just generated.
 *
 * One file per screen so that concurrently running checkers never contend, and
 * so the build can count what was produced without parsing generated Kotlin.
 */
private fun writeScreenMetadata(reportDirectory: File, screen: BundleScreen) {

    val directory = File(reportDirectory, SCREEN_METADATA_DIRECTORY).apply { mkdirs() }

    val lines = listOf(
        "screenId=${screen.screenId}",
        "functionName=${screen.functionName}",
        "objectName=${BundleSourceWriter.screenObjectName(screen)}",
        "parameters=${screen.parameters.joinToString(",") { it.name }}",
        "callbacks=${screen.callbacks.joinToString(",")}",
        "actions=${screen.actions.joinToString(",") { it.name }}",
        // What the screen needs the installed app to have generated for it.
        // Separated by "," and never by ", ": this file is read back by the
        // build, and a space that crept into one of these once made every entry
        // after the first fail to match.
        "adapters=${screen.adapters.joinToString(",")}",
        "capabilities=${screen.capabilities.joinToString(",") { it.id }}",
        "handles=${screen.handles.joinToString(",")}",
        "resources=${screen.resources.joinToString(",")}",
    )

    File(directory, "${sanitizeForIdentifier(screen.screenId)}.properties")
        .writeText(lines.joinToString("\n", postfix = "\n"))
}

/**
 * Records what this screen needs the installed app to have.
 *
 * The other half of the check that catches an edit the binary cannot carry. A
 * tint added to an icon no call site ever tinted is a perfectly good bundle and
 * a perfectly good app, and only these two files put side by side show that the
 * one cannot ask the other for it.
 *
 * Which props each component is given is recorded per adapter, because that is
 * the granularity the failure has: the component exists, the argument does not.
 */
private fun writeRequirementsFragment(reportDirectory: File, screen: BundleScreen) {

    val directory = File(reportDirectory, REQUIREMENTS_DIRECTORY).apply { mkdirs() }

    val requirements = BundleRequirements(
        runtimeVersion = RuntimeVersion.CURRENT,
        screens = listOf(
            ScreenRequirements(
                id = screen.screenId,
                adapters = screen.propsByAdapter().map { (id, props) ->
                    AdapterUse(id = id, props = props.sorted())
                },
                capabilities = screen.capabilities.map { capability ->
                    CapabilityContract(id = capability.id, arity = capability.arity)
                },
                handles = screen.handles.sorted(),
                resources = screen.resources.sorted(),
            )
        ),
    )

    File(directory, "${sanitizeForIdentifier(screen.screenId)}.json")
        .writeText(ContractJson.write(requirements))
}

/** Every argument the screen gives each component, gathered across its tree. */
private fun BundleScreen.propsByAdapter(): Map<String, Set<String>> {

    val used = linkedMapOf<String, MutableSet<String>>()

    fun walk(node: BundleUi) {
        when (node) {
            is BundleUi.ComponentUi -> {
                used.getOrPut(node.adapterId) { linkedSetOf() } += node.props.keys
                node.children.values.flatten().forEach(::walk)
            }
            is BundleUi.ConditionalUi -> {
                node.ifTrue.forEach(::walk)
                node.ifFalse.forEach(::walk)
            }
            is BundleUi.FragmentUi -> node.children.forEach(::walk)
            is BundleUi.ColumnUi -> node.children.forEach(::walk)
            is BundleUi.RowUi -> node.children.forEach(::walk)
            is BundleUi.BoxUi -> node.children.forEach(::walk)
            is BundleUi.TextUi, is BundleUi.ButtonUi -> Unit
        }
    }

    walk(ui)

    // A component the tree never places is still a component the app must have,
    // so the adapters the screen recorded are the floor.
    adapters.forEach { id -> used.getOrPut(id) { linkedSetOf() } }

    return used
}

/**
 * Rewrites the entry points to cover every screen lowered so far.
 *
 * A checker is handed one declaration at a time and has no "end of module"
 * hook, so the file is rebuilt from the metadata directory after each screen.
 * The last screen of a compilation therefore writes a file naming all of them,
 * and sorting by id keeps the result identical for identical input.
 */
private fun writeExports(generatedDirectory: File, reportDirectory: File) {

    val screens = File(reportDirectory, SCREEN_METADATA_DIRECTORY)
        .listFiles()
        .orEmpty()
        .filter { it.extension == "properties" }
        .mapNotNull { file ->

            val values = file.readLines()
                .mapNotNull { line ->
                    line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }
                .toMap()

            val screenId = values["screenId"] ?: return@mapNotNull null

            ScreenEntry(
                screenId = screenId,
                objectName = values["objectName"]
                    ?: BundleSourceWriter.screenObjectName(screenId),
            )
        }
        .sortedBy { it.screenId }

    File(generatedDirectory, EXPORTS_FILE_NAME)
        .writeText(BundleSourceWriter.writeExports(screens))
}

/**
 * Records why an eligible screen could not be bundled, for the build to report.
 *
 * Written as data for the Gradle task to format rather than logged here: the
 * extraction pass runs in its own compiler process, and a message buried in that
 * process's output is far easier to miss than a build failure.
 */
/** Records the regions a lowered screen kept native, and why. */
internal fun writeDegradationReport(
    reportDirectory: File,
    screenId: String,
    regions: List<UnsupportedConstruct>,
) {
    if (regions.isEmpty()) return

    val directory = File(reportDirectory, DEGRADED_DIRECTORY).apply { mkdirs() }

    val lines = regions.flatMap { region ->
        listOf(
            "code=${region.code}",
            "detail=${region.detail.orEmpty()}",
            "function=${region.functionName}",
            "file=${region.filePath}",
            "offset=${region.sourceOffset ?: -1}",
            "found=${region.found}",
            "remedy=${region.remedy}",
            "--",
        )
    }

    File(directory, "${sanitizeForIdentifier(screenId)}.txt")
        .writeText(lines.joinToString("\n", postfix = "\n"))
}

internal fun writeUnsupportedReport(
    reportDirectory: File,
    screenId: String,
    reasons: List<UnsupportedConstruct>,
    forced: Boolean,
) {
    if (reasons.isEmpty()) return

    val directory = File(reportDirectory, UNSUPPORTED_DIRECTORY).apply { mkdirs() }

    val lines = reasons.flatMap { reason ->
        listOf(
            // Whether the developer asked for this screen by name. Discovery
            // finds far more functions than anyone marked by hand, and a
            // limitation Dootah ran into on its own is news rather than a
            // failure -- but one hit on a function someone explicitly asked to
            // bundle is exactly the failure they wanted to hear about.
            "forced=$forced",
            // The stable name for the refusal, and the concrete thing refused.
            // Counting across apps needs these; the prose beside them is for
            // whoever is reading one build.
            "code=${reason.code}",
            "detail=${reason.detail.orEmpty()}",
            "function=${reason.functionName}",
            "file=${reason.filePath}",
            "offset=${reason.sourceOffset ?: -1}",
            "found=${reason.found}",
            "remedy=${reason.remedy}",
            "--",
        )
    }

    File(directory, "${sanitizeForIdentifier(screenId)}.txt")
        .writeText(lines.joinToString("\n", postfix = "\n"))
}
