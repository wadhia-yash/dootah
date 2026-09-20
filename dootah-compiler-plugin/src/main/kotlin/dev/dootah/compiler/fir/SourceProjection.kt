package dev.dootah.compiler.fir

import dev.dootah.compiler.compat.*
import dev.dootah.compiler.compat.FirNamedFunction
import dev.dootah.compiler.compat.FirBooleanOperatorExpression
import dev.dootah.compiler.source.*
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirUnitExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.references.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import java.util.IdentityHashMap

/** Projects resolved compiler facts lazily, preserving node and symbol identity within a screen. */
internal class SourceProjection {
    private val nodes = IdentityHashMap<FirElement, SourceElement>()
    private val symbols = IdentityHashMap<FirBasedSymbol<*>, SourceSymbol>()

    private open inner class Node(val node: FirElement) : SourceElement {
        override val source get() = node.source?.let { SourceLocation(it.startOffset, it.endOffset) }
        override val children: List<SourceElement> get() = buildList {
            node.acceptChildren(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) { add(element(element)) }
            })
        }
    }
    private open inner class Expression(node: FirExpression) : Node(node), SourceExpression {
        override val resolvedType get() = runCatching { (node as FirExpression).resolvedType }.getOrNull()?.let(::type)
    }
    private open inner class Function(val function: FirFunction) : Node(function), SourceFunction {
        override val valueParameters get() = function.valueParameters.map { element(it) as SourceValueParameter }
        override val body get() = function.body?.let { element(it) as SourceBlock }
        override val returnTypeRef get() = typeRef(function.returnTypeRef)
    }
    fun element(node: FirElement): SourceElement = nodes.getOrPut(node) {
        when (node) {
            is FirNamedFunction -> object : Function(node), SourceNamedFunction {
                override val symbol get() = symbol(node.symbol) as SourceCallableSymbol
                override val qualifiedName get() = node.symbol.callableId.asSingleFqName().asString()
            }
            is FirAnonymousFunction -> object : Function(node), SourceAnonymousFunction {
                override val symbol get() = symbol(node.symbol)
                override val typeRef get() = typeRef(node.typeRef)
            }
            is FirFunction -> object : Function(node), SourceFunction {
            }
            is FirValueParameter -> object : Node(node), SourceValueParameter {
                override val name get() = name(node.name.asString())
                override val returnTypeRef get() = typeRef(node.returnTypeRef)
            }
            is FirProperty -> object : Node(node), SourceProperty {
                override val name get() = name(node.name.asString())
                override val returnTypeRef get() = typeRef(node.returnTypeRef)
                override val initializer get() = node.initializer?.let { element(it) as SourceExpression }
                override val delegate get() = node.delegate?.let { element(it) as SourceExpression }
                override val isVal get() = node.isVal
            }
            is FirBlock -> object : Expression(node), SourceBlock {
                override val statements get() = node.statements.map(::element)
            }
            is FirFunctionCall -> object : Expression(node), SourceFunctionCall {
                override val calleeReference get() = reference(node.calleeReference)
                override val explicitReceiver get() = node.explicitReceiver?.let { element(it) as SourceExpression }
                override val arguments get() = node.arguments.map { element(it) as SourceExpression }
                override val resolvedArgumentMapping get() = node.resolvedArgumentMapping?.entries?.associate { (expression, parameter) -> (element(expression) as SourceExpression) to (element(parameter) as SourceValueParameter) }
            }
            is FirPropertyAccessExpression -> object : Expression(node), SourcePropertyAccessExpression {
                override val calleeReference get() = reference(node.calleeReference)
                override val explicitReceiver get() = node.explicitReceiver?.let { element(it) as SourceExpression }
            }
            is FirAnonymousFunctionExpression -> object : Expression(node), SourceAnonymousFunctionExpression {
                override val anonymousFunction get() = element(node.anonymousFunction) as SourceAnonymousFunction
            }
            is FirLiteralExpression -> object : Expression(node), SourceLiteralExpression {
                override val value get() = node.value
                override val kind get() = ConstantValueKind.entries.firstOrNull { it.name == node.kind.toString() } ?: ConstantValueKind.Other
            }
            is FirReturnExpression -> object : Expression(node), SourceReturnExpression {
                override val result get() = element(node.result) as SourceExpression
            }
            is FirUnitExpression -> object : Expression(node), SourceUnitExpression {
            }
            is FirElseIfTrueCondition -> object : Expression(node), SourceElseIfTrueCondition {
            }
            is FirStringConcatenationCall -> object : Expression(node), SourceStringConcatenationCall {
                override val arguments get() = node.arguments.map { element(it) as SourceExpression }
            }
            is FirVariableAssignment -> object : Node(node), SourceVariableAssignment {
                override val lValue get() = element(node.lValue) as SourceExpression
                override val rValue get() = element(node.rValue) as SourceExpression
            }
            is FirWhenExpression -> object : Expression(node), SourceWhenExpression {
                override val subjectName get() = whenSubjectName(node)
                override val subjectInitializer get() = whenSubjectInitializer(node)?.let { element(it) as SourceExpression }
                override val branches get() = node.branches.map { element(it) as SourceWhenBranch }
            }
            is FirWhenBranch -> object : Node(node), SourceWhenBranch {
                override val condition get() = element(node.condition) as SourceExpression
                override val result get() = element(node.result) as SourceBlock
            }
            is FirThisReceiverExpression -> object : Expression(node), SourceThisReceiverExpression {
                override val calleeReference get() = reference(node.calleeReference)
            }
            is FirEqualityOperatorCall -> object : Expression(node), SourceEqualityOperatorCall {
                override val arguments get() = node.arguments.map { element(it) as SourceExpression }
                override val operation get() = SourceOperation(node.operation.operator)
            }
            is FirComparisonExpression -> object : Expression(node), SourceComparisonExpression {
                override val compareToCall get() = element(node.compareToCall) as SourceFunctionCall
                override val operation get() = SourceOperation(node.operation.operator)
            }
            is FirBooleanOperatorExpression -> object : Expression(node), SourceBooleanOperatorExpression {
                override val leftOperand get() = element(node.leftOperand) as SourceExpression
                override val rightOperand get() = element(node.rightOperand) as SourceExpression
                override val kind get() = LogicOperationKind.valueOf(node.kind.name)
            }
            is FirResolvedQualifier -> object : Expression(node), SourceResolvedQualifier {
                override val classId get() = node.classId?.let(::classId)
                override val relativeClassFqName get() = node.relativeClassFqName?.let { name(it.asString()) }
            }
            is FirExpression -> legacyWhenSubjectName(node)?.let { subject ->
                object : Expression(node), SourceWhenSubjectExpression {
                    override val subjectName = subject
                }
            } ?: Expression(node)
            else -> Node(node)
        }
    }
    private fun name(value: String) = FqName(value)
    private fun classId(id: org.jetbrains.kotlin.name.ClassId) = SourceClassId(name(id.packageFqName.asString()), name(id.relativeClassName.asString()))
    private fun callableId(id: org.jetbrains.kotlin.name.CallableId) = SourceCallableId(name(id.packageName.asString()), id.classId?.let(::classId), name(id.callableName.asString()))
    private fun typeRef(ref: FirTypeRef): SourceTypeRef = object : SourceTypeRef {
        override val type get() = ref.coneTypeSafe<ConeKotlinType>()?.let(::type)
    }
    private fun type(value: ConeKotlinType): SourceType = object : SourceType {
        override val classId get() = value.classId?.let(::classId)
        override val isMarkedNullable get() = value.isMarkedNullable
        override val isExtensionFunctionType get() = value.isExtensionFunctionType
        override val typeArguments get() = value.typeArguments.map { (it as? ConeKotlinType)?.let(::type) }
        override val customAnnotations get() = value.customAnnotations.map { annotation ->
            object : SourceAnnotation { override val annotationTypeRef get() = typeRef(annotation.annotationTypeRef) }
        }
    }
    private fun reference(ref: FirReference): SourceReference {
        fun callable() = ref.toResolvedCallableSymbol()?.let { symbol(it) as SourceCallableSymbol }
        fun bound() = (ref as? FirThisReference)?.boundSymbol?.let(::symbol)
        return if (ref is FirResolvedNamedReference) object : SourceResolvedNamedReference {
            override val name get() = name(ref.name.asString())
            override val callableSymbol get() = callable()
            override val boundSymbol get() = bound()
        } else object : SourceReference {
            override val callableSymbol get() = callable()
            override val boundSymbol get() = bound()
        }
    }
    private open inner class Callable(val value: FirCallableSymbol<*>) : SourceCallableSymbol {
        override val callableId get() = value.callableId?.let(::callableId)
        override val name get() = name(value.name.asString())
        override val declaration get() = element(value.fir)
        override val resolvedAnnotationClassIds get() = value.resolvedAnnotationClassIds.map(::classId)
    }
    private fun symbol(value: FirBasedSymbol<*>): SourceSymbol = symbols.getOrPut(value) {
        when (value) {
            is FirPropertySymbol -> object : Callable(value), SourcePropertySymbol {
                override val isLocal get() = value.isLocal
                override val isConst get() = (value.fir as? FirProperty)?.status?.isConst == true
            }
            is FirCallableSymbol<*> -> Callable(value)
            else -> receiverOwner(value)?.let { owner ->
                object : SourceReceiverParameterSymbol { override val containingDeclarationSymbol get() = symbol(owner) }
            } ?: object : SourceSymbol {}
        }
    }
}
