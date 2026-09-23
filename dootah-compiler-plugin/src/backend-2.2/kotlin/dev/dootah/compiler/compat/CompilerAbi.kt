package dev.dootah.compiler.compat

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.name.CallableId

typealias FirNamedFunction = org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
typealias FirBooleanOperatorExpression = org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
typealias IrParameterKind = org.jetbrains.kotlin.ir.declarations.IrParameterKind
typealias IrVisitorVoid = org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
typealias IrConst = org.jetbrains.kotlin.ir.expressions.IrConst

abstract class BackendRegistrar : CompilerPluginRegistrar() {
    // This virtual getter also implements the abstract member added by later
    // compilers. On older compilers it is simply an unused public property.
    open val pluginId: String = dev.dootah.compiler.DOOTAH_PLUGIN_ID
}
internal abstract class BackendScreenChecker : FirDeclarationChecker<org.jetbrains.kotlin.fir.declarations.FirFunction>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    final override fun check(declaration: org.jetbrains.kotlin.fir.declarations.FirFunction) {
        if (declaration is FirNamedFunction) inspect(declaration, context)
    }
    abstract fun inspect(declaration: FirNamedFunction, context: CheckerContext)
}

internal class DeclarationFinder(private val context: IrPluginContext) {
    fun findFunctions(id: CallableId) = context.referenceFunctions(id)
}
internal fun IrPluginContext.finderForSource(file: IrFile) = DeclarationFinder(this)
internal val FirNamedFunction.isLocal: Boolean get() = status.visibility == org.jetbrains.kotlin.descriptors.Visibilities.Local
internal fun whenSubjectName(expression: org.jetbrains.kotlin.fir.expressions.FirWhenExpression) = expression.subjectVariable?.name?.asString()
internal fun whenSubjectInitializer(expression: org.jetbrains.kotlin.fir.expressions.FirWhenExpression) = expression.subjectVariable?.initializer
internal fun legacyWhenSubjectName(expression: org.jetbrains.kotlin.fir.expressions.FirExpression): String? = null
