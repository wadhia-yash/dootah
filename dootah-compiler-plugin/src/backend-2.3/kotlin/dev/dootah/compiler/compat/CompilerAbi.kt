package dev.dootah.compiler.compat

import dev.dootah.compiler.DOOTAH_PLUGIN_ID
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol

typealias FirNamedFunction = org.jetbrains.kotlin.fir.declarations.FirNamedFunction
typealias FirBooleanOperatorExpression = org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
typealias IrParameterKind = org.jetbrains.kotlin.ir.declarations.IrParameterKind
typealias IrVisitorVoid = org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
typealias IrConst = org.jetbrains.kotlin.ir.expressions.IrConst
typealias DeclarationFinder = org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder

abstract class BackendRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = DOOTAH_PLUGIN_ID
}
internal abstract class BackendScreenChecker : FirDeclarationChecker<org.jetbrains.kotlin.fir.declarations.FirFunction>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    final override fun check(declaration: org.jetbrains.kotlin.fir.declarations.FirFunction) {
        if (declaration is FirNamedFunction) inspect(declaration, context)
    }
    abstract fun inspect(declaration: FirNamedFunction, context: CheckerContext)
}

internal fun whenSubjectName(expression: org.jetbrains.kotlin.fir.expressions.FirWhenExpression) = expression.subjectVariable?.name?.asString()
internal fun whenSubjectInitializer(expression: org.jetbrains.kotlin.fir.expressions.FirWhenExpression) = expression.subjectVariable?.initializer
internal fun legacyWhenSubjectName(expression: org.jetbrains.kotlin.fir.expressions.FirExpression): String? = null
