package dev.dootah.compiler

import dev.dootah.compiler.fir.DootahFirExtensionRegistrar
import dev.dootah.compiler.ir.DootahIrExtension
import dev.dootah.contract.ScreenFilter
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.io.File

/**
 * Registers the extensions for whichever job this invocation performs.
 *
 * The two modes register disjoint extensions on purpose. Interception belongs to
 * the app's real compilation and must leave a working APK behind; extraction is
 * an analysis-only pass that produces bundle sources. Sharing one extension set
 * between them would put bundle generation inside the app build, where
 * incremental compilation does not track it.
 */
class DootahCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = DOOTAH_PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {

        val messageCollector = configuration.get(
            CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY,
            MessageCollector.NONE,
        )

        val reportDirectory = configuration
            .get(DootahConfigurationKeys.REPORT_DIR)
            ?.let(::File)

        // Read once and handed to whichever extension runs. Both passes have to
        // discover the same set of functions from the same rule; reading the
        // options in two places is how they would quietly stop doing that.
        val discovery = configuration.get(DootahConfigurationKeys.DISCOVERY)
            ?: DootahDiscovery.AUTO

        val filter = configuration.get(DootahConfigurationKeys.FILTER)
            ?.let(ScreenFilter::parse)
            ?: ScreenFilter.EVERYTHING

        when (configuration.get(DootahConfigurationKeys.MODE) ?: DootahMode.INTERCEPT) {

            DootahMode.INTERCEPT ->
                IrGenerationExtension.registerExtension(
                    DootahIrExtension(
                        messageCollector = messageCollector,
                        reportDirectory = reportDirectory,
                        discovery = discovery,
                        filter = filter,
                    )
                )

            DootahMode.EXTRACT -> {

                // Extraction has nowhere to write without one, and silently
                // producing no bundle would look like a screen that simply
                // cannot be patched.
                val directory = reportDirectory
                    ?: error("Dootah extraction requires the reportDir option")

                FirExtensionRegistrarAdapter.registerExtension(
                    DootahFirExtensionRegistrar(
                        reportDirectory = directory,
                        generatedDirectory = configuration
                            .get(DootahConfigurationKeys.GENERATED_DIR)
                            ?.let(::File),
                        discovery = discovery,
                        filter = filter,
                    )
                )
            }
        }
    }
}
