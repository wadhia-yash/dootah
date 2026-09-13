package dev.dootah.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Translates the `-P plugin:dev.dootah:...` arguments the Gradle plugin passes
 * into typed configuration keys.
 *
 * An unknown option is an error rather than a silently ignored argument: a
 * misspelled option would otherwise leave the plugin running in its default
 * mode, which is the kind of failure that surfaces much later as a wrong bundle.
 */
class DootahCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = DOOTAH_PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(
            optionName = OPTION_MODE,
            valueDescription = "intercept|extract",
            description = "Which Dootah compiler job this invocation performs",
            required = true,
        ),
        CliOption(
            optionName = OPTION_REPORT_DIR,
            valueDescription = "<path>",
            description = "Directory for Dootah compiler reports",
            required = false,
        ),
        CliOption(
            optionName = OPTION_GENERATED_DIR,
            valueDescription = "<path>",
            description = "Directory for generated Dootah bundle sources",
            required = false,
        ),
        CliOption(
            optionName = OPTION_DISCOVERY,
            valueDescription = "auto|annotated",
            description = "How Dootah finds the Compose functions it may take over",
            required = false,
        ),
        CliOption(
            optionName = OPTION_FILTER,
            valueDescription = "+include,-exclude",
            description = "Which fully qualified names Dootah may consider",
            required = false,
        ),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {

            OPTION_MODE -> {
                val mode = DootahMode.fromCliValue(value)
                    ?: throw CliOptionProcessingException(
                        "Unknown Dootah mode '$value'; expected one of " +
                            DootahMode.entries.joinToString(", ") { it.name.lowercase() }
                    )
                configuration.put(DootahConfigurationKeys.MODE, mode)
            }

            OPTION_REPORT_DIR ->
                configuration.put(DootahConfigurationKeys.REPORT_DIR, value)

            OPTION_GENERATED_DIR ->
                configuration.put(DootahConfigurationKeys.GENERATED_DIR, value)

            OPTION_DISCOVERY -> {
                val discovery = DootahDiscovery.fromCliValue(value)
                    ?: throw CliOptionProcessingException(
                        "Unknown Dootah discovery mode '$value'; expected one of " +
                            DootahDiscovery.entries.joinToString(", ") { it.name.lowercase() }
                    )
                configuration.put(DootahConfigurationKeys.DISCOVERY, discovery)
            }

            OPTION_FILTER ->
                configuration.put(DootahConfigurationKeys.FILTER, value)

            else -> throw CliOptionProcessingException(
                "Unknown Dootah compiler option '${option.optionName}'"
            )
        }
    }

    private companion object {
        const val OPTION_MODE = "mode"
        const val OPTION_REPORT_DIR = "reportDir"
        const val OPTION_GENERATED_DIR = "generatedDir"
        const val OPTION_DISCOVERY = "discovery"
        const val OPTION_FILTER = "filter"
    }
}
