package dev.dootah.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val DOOTAH_UI_PACKAGE = FqName("com.dootah.ui")

/**
 * The runtime functions the generated interception calls.
 *
 * Resolved once per compilation and reported as a group: if the Dootah runtime
 * is missing from the host app's classpath, saying so once is more useful than
 * failing separately at each call site.
 */
internal class DootahRuntimeSymbols private constructor(
    val rememberScreen: IrSimpleFunctionSymbol,
    val hasRemoteImplementation: IrSimpleFunctionSymbol,
    val renderRemoteContent: IrSimpleFunctionSymbol,
) {

    companion object {

        /** Null when the Dootah runtime is not on the compilation classpath. */
        fun resolve(pluginContext: IrPluginContext): DootahRuntimeSymbols? {

            val rememberScreen = pluginContext.singleFunction("rememberDootahScreen")
            val hasRemote = pluginContext.singleFunction("hasRemoteImplementation")
            val renderRemote = pluginContext.singleFunction("DootahRemoteContent")

            if (rememberScreen == null || hasRemote == null || renderRemote == null) return null

            return DootahRuntimeSymbols(
                rememberScreen = rememberScreen,
                hasRemoteImplementation = hasRemote,
                renderRemoteContent = renderRemote,
            )
        }

        private fun IrPluginContext.singleFunction(name: String): IrSimpleFunctionSymbol? =
            referenceFunctions(
                CallableId(DOOTAH_UI_PACKAGE, Name.identifier(name))
            ).singleOrNull()
    }
}

internal fun missingRuntimeMessage(): String =
    "Dootah's runtime is not on this module's compile classpath, so @Bundlable " +
        "functions cannot be intercepted.\n" +
        "Add the Dootah runtime dependency to the module that declares them:\n" +
        "    implementation(\"dev.dootah:dootah-android:<version>\")"
