package dev.dootah.compiler.ir

import dev.dootah.compiler.compat.*
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.ClassId
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
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class DootahRuntimeSymbols private constructor(
    val rememberScreen: IrSimpleFunctionSymbol,
    val hasRemoteImplementation: IrSimpleFunctionSymbol,
    val renderRemoteContent: IrSimpleFunctionSymbol,
    val arguments: IrSimpleFunctionSymbol,
    val modifiedArguments: IrSimpleFunctionSymbol,
    val callbacks: IrSimpleFunctionSymbol,
    val bindings: IrSimpleFunctionSymbol,
    val adapter: IrSimpleFunctionSymbol,
    val adapters: IrSimpleFunctionSymbol,
    val capability: IrSimpleFunctionSymbol,
    val capabilities: IrSimpleFunctionSymbol,
    val handle: IrSimpleFunctionSymbol,
    val handles: IrSimpleFunctionSymbol,
    val resource: IrSimpleFunctionSymbol,
    val resources: IrSimpleFunctionSymbol,
    val anchor: IrSimpleFunctionSymbol,
    val anchors: IrSimpleFunctionSymbol,
    val builder: IrSimpleFunctionSymbol,
    val builders: IrSimpleFunctionSymbol,

    /** `DootahProps`, and the accessors an adapter reads its arguments with. */
    val props: IrClassSymbol,
    private val accessors: Map<String, IrSimpleFunctionSymbol>,
) {

    /**
     * The accessor an adapter uses for one argument.
     *
     * Chosen by the parameter's declared type, so the adapter hands the
     * composable the type it asked for and the cast, where there is one, is
     * written by the compiler rather than hoped for at run time.
     */
    fun accessor(name: String): IrSimpleFunctionSymbol? = accessors[name]

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

            val propsClass = pluginContext.referenceClass(
                ClassId(DOOTAH_UI_PACKAGE, Name.identifier("DootahProps"))
            ) ?: return null

            return DootahRuntimeSymbols(
                rememberScreen = finder.singleFunction("rememberDootahScreen") ?: return null,
                hasRemoteImplementation =
                    finder.singleFunction("hasRemoteImplementation") ?: return null,
                renderRemoteContent = finder.singleFunction("DootahRemoteContent") ?: return null,
                arguments = finder.singleFunction("dootahArguments") ?: return null,
                modifiedArguments =
                    finder.singleFunction("dootahModifiedArguments") ?: return null,
                callbacks = finder.singleFunction("dootahCallbacks") ?: return null,
                bindings = finder.singleFunction("dootahBindings") ?: return null,
                adapter = finder.singleFunction("dootahAdapter") ?: return null,
                adapters = finder.singleFunction("dootahAdapters") ?: return null,
                capability = finder.singleFunction("dootahCapability") ?: return null,
                capabilities = finder.singleFunction("dootahCapabilities") ?: return null,
                handle = finder.singleFunction("dootahHandle") ?: return null,
                handles = finder.singleFunction("dootahHandles") ?: return null,
                resource = finder.singleFunction("dootahResource") ?: return null,
                resources = finder.singleFunction("dootahResources") ?: return null,
                anchor = finder.singleFunction("dootahAnchor") ?: return null,
                anchors = finder.singleFunction("dootahAnchors") ?: return null,
                builder = finder.singleFunction("dootahBuilder") ?: return null,
                builders = finder.singleFunction("dootahBuilders") ?: return null,
                props = propsClass,
                accessors = propsClass.functions
                    .filter { accessor -> accessor.owner.parameters.size == 2 }
                    .associateBy { accessor -> accessor.owner.name.asString() },
            )
        }

        private fun DeclarationFinder.singleFunction(name: String): IrSimpleFunctionSymbol? =
            findFunctions(
                CallableId(DOOTAH_UI_PACKAGE, Name.identifier(name))
            ).singleOrNull()
    }
}

internal fun missingRuntimeMessage(): String =
    "Dootah's runtime is not on this module's compile classpath, so eligible " +
        "functions cannot be intercepted.\n" +
        "Add the Dootah runtime dependency to the module that declares them:\n" +
        "    implementation(\"dev.dootah:dootah-android:<version>\")"
