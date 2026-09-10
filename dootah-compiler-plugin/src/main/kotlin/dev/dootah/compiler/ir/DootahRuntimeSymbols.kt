package dev.dootah.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
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

        /**
         * Null when the Dootah runtime is not on the compilation classpath.
         *
         * Resolution is scoped to [fromFile] -- the file whose screens are about
         * to be rewritten -- because the supported API resolves names as they
         * are visible from a given file.
         */
        fun resolve(pluginContext: IrPluginContext, fromFile: IrFile): DootahRuntimeSymbols? {

            val finder = pluginContext.finderForSource(fromFile)

            val rememberScreen = finder.singleFunction("rememberDootahScreen")
            val hasRemote = finder.singleFunction("hasRemoteImplementation")
            val renderRemote = finder.singleFunction("DootahRemoteContent")

            if (rememberScreen == null || hasRemote == null || renderRemote == null) return null

            return DootahRuntimeSymbols(
                rememberScreen = rememberScreen,
                hasRemoteImplementation = hasRemote,
                renderRemoteContent = renderRemote,
            )
        }

        private fun org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
            .singleFunction(name: String): IrSimpleFunctionSymbol? =
            findFunctions(
                CallableId(DOOTAH_UI_PACKAGE, Name.identifier(name))
            ).singleOrNull()
    }
}

internal fun missingRuntimeMessage(): String =
    "Dootah's runtime is not on this module's compile classpath, so @Bundlable " +
        "functions cannot be intercepted.\n" +
        "Add the Dootah runtime dependency to the module that declares them:\n" +
        "    implementation(\"dev.dootah:dootah-android:<version>\")"
