package dev.dootah.compiler.compat

import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.extensions.ProjectExtensionDescriptor
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.name.CallableId

typealias FirNamedFunction = org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
typealias FirBooleanOperatorExpression = org.jetbrains.kotlin.fir.expressions.FirBinaryLogicExpression
typealias IrConst = org.jetbrains.kotlin.ir.expressions.IrConst<*>
abstract class IrVisitorVoid : org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
/**
 * Registration on this compiler line, where the order is decided differently.
 *
 * `KotlinCoreEnvironment` runs every legacy `ComponentRegistrar` against the
 * project first, and only then installs what the `CompilerPluginRegistrar`s
 * collected. On this line the Compose compiler plugin is still a
 * `ComponentRegistrar`, so an IR extension registered the modern way lands
 * after Compose's no matter where Dootah sits on the plugin classpath -- and
 * Dootah's whole transform depends on running before it.
 *
 * Registering the same way Compose does puts both back under one rule, the one
 * the Gradle plugin already enforces: plugin classpath order, which follows the
 * order the app declares its plugins in. On the newer line Compose registers
 * through `ExtensionStorage` and the modern path is the one that agrees, which
 * is why this lives here and not in the shared registrar.
 */
abstract class BackendRegistrar : ComponentRegistrar {

    final override fun registerProjectComponents(
        project: MockProject,
        configuration: CompilerConfiguration,
    ) {
        ExtensionStorage(project).registerExtensions(configuration)
    }

    protected abstract fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration)

    /**
     * The shape the shared registrar writes against, registering eagerly.
     *
     * Same member-extension signature as the compiler's own storage of that
     * name, so the shared code reads identically on both lines.
     */
    class ExtensionStorage(private val project: MockProject) {
        fun <T : Any> ProjectExtensionDescriptor<T>.registerExtension(extension: T) =
            registerExtension(project, extension)
    }
}
internal abstract class BackendScreenChecker : FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {
    final override fun check(declaration: FirNamedFunction, context: CheckerContext, reporter: DiagnosticReporter) = inspect(declaration, context)
    abstract fun inspect(declaration: FirNamedFunction, context: CheckerContext)
}
// In this ABI a receiver is bound directly to its containing function symbol.
internal fun receiverOwner(symbol: FirBasedSymbol<*>): FirBasedSymbol<*>? = null
internal fun CheckerContext.enclosingClassAnnotations() = containingDeclarations.filterIsInstance<FirRegularClass>().flatMap { it.annotations }
internal fun CheckerContext.fileAnnotations() = containingFile?.annotations.orEmpty()

internal enum class IrParameterKind { DispatchReceiver, ExtensionReceiver, Regular }
internal val IrFunction.parameters: List<IrValueParameter>
    get() = listOfNotNull(dispatchReceiverParameter, extensionReceiverParameter) + valueParameters
internal var IrValueParameter.kind: IrParameterKind
    get() {
        val function = parent as? IrFunction ?: return IrParameterKind.Regular
        return when (this) {
            function.dispatchReceiverParameter -> IrParameterKind.DispatchReceiver
            function.extensionReceiverParameter -> IrParameterKind.ExtensionReceiver
            else -> IrParameterKind.Regular
        }
    }
    set(value) {
        val function = parent as IrFunction
        require(value == IrParameterKind.ExtensionReceiver) { "Unsupported receiver conversion: $value" }
        function.valueParameters = function.valueParameters.filter { it !== this }.onEachIndexed { index, parameter -> parameter.index = index }
        index = -1
        function.extensionReceiverParameter = this
    }
/** Match the newer ABI's unified argument indexes, including both receiver slots. */
internal val IrMemberAccessExpression<*>.arguments: MutableList<IrExpression?>
    get() {
        val function = symbol.owner as IrFunction
        val dispatch = if (function.dispatchReceiverParameter != null) 1 else 0
        val extension = if (function.extensionReceiverParameter != null) 1 else 0
        return object : AbstractMutableList<IrExpression?>() {
            override val size get() = dispatch + extension + valueArgumentsCount
            override fun get(index: Int): IrExpression? {
                require(index in indices)
                return when {
                    dispatch == 1 && index == 0 -> dispatchReceiver
                    extension == 1 && index == dispatch -> extensionReceiver
                    else -> getValueArgument(index - dispatch - extension)
                }
            }
            override fun set(index: Int, element: IrExpression?): IrExpression? {
                val previous = get(index)
                when {
                    dispatch == 1 && index == 0 -> dispatchReceiver = element
                    extension == 1 && index == dispatch -> extensionReceiver = element
                    else -> putValueArgument(index - dispatch - extension, element)
                }
                return previous
            }
            override fun add(index: Int, element: IrExpression?) = error("Compiler argument slots are fixed")
            override fun removeAt(index: Int): IrExpression? = error("Compiler argument slots are fixed")
        }
    }
internal class DeclarationFinder(private val context: IrPluginContext) {
    fun findFunctions(id: CallableId) = context.referenceFunctions(id)
}
internal fun IrPluginContext.finderForSource(file: IrFile) = DeclarationFinder(this)

internal val FirNamedFunction.isLocal: Boolean get() = status.visibility == org.jetbrains.kotlin.descriptors.Visibilities.Local

internal fun whenSubjectName(expression: org.jetbrains.kotlin.fir.expressions.FirWhenExpression): String? =
    if (expression.subjectVariable != null || expression.subject != null) "<when-${expression.source?.startOffset}>" else null
internal fun whenSubjectInitializer(expression: org.jetbrains.kotlin.fir.expressions.FirWhenExpression) =
    expression.subjectVariable?.initializer ?: expression.subject
internal fun legacyWhenSubjectName(expression: org.jetbrains.kotlin.fir.expressions.FirExpression): String? =
    (expression as? org.jetbrains.kotlin.fir.expressions.FirWhenSubjectExpression)?.whenRef?.value?.let(::whenSubjectName)
