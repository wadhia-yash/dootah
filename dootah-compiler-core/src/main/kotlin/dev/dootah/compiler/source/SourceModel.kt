package dev.dootah.compiler.source

/** Read-only compiler input. No compiler object or API is exposed to semantic lowering. */
interface SourceElement {
    val source: SourceLocation?
    val children: List<SourceElement>
    fun accept(visitor: SourceVisitor) = when (this) {
        is SourceFunctionCall -> visitor.visitFunctionCall(this)
        is SourceLiteralExpression -> visitor.visitLiteralExpression(this)
        is SourceWhenExpression -> visitor.visitWhenExpression(this)
        is SourceStringConcatenationCall -> visitor.visitStringConcatenationCall(this)
        else -> visitor.visitElement(this)
    }
    fun acceptChildren(visitor: SourceVisitor) { children.forEach { it.accept(visitor) } }
}
abstract class SourceVisitor {
    abstract fun visitElement(element: SourceElement)
    open fun visitFunctionCall(functionCall: SourceFunctionCall) = visitElement(functionCall)
    open fun visitLiteralExpression(literalExpression: SourceLiteralExpression) = visitElement(literalExpression)
    open fun visitWhenExpression(whenExpression: SourceWhenExpression) = visitElement(whenExpression)
    open fun visitStringConcatenationCall(stringConcatenationCall: SourceStringConcatenationCall) = visitElement(stringConcatenationCall)
}
data class SourceLocation(val startOffset: Int, val endOffset: Int)
data class FqName(private val value: String) {
    fun asString(): String = value
    fun shortName(): FqName = FqName(value.substringAfterLast('.'))
    fun parent(): FqName = FqName(value.substringBeforeLast('.', ""))
    val isRoot: Boolean get() = value.isEmpty()
    companion object { val ROOT = FqName("") }
}
data class SourceClassId(val packageName: FqName, val relativeClassFqName: FqName) {
    fun asSingleFqName(): FqName = FqName(listOf(packageName.asString(), relativeClassFqName.asString()).filter { it.isNotEmpty() }.joinToString("."))
    fun asString(): String = (if (packageName.isRoot) "" else packageName.asString().replace('.', '/') + "/") + relativeClassFqName.asString()
}
data class SourceCallableId(val packageName: FqName, val classId: SourceClassId?, val callableName: FqName) {
    fun asSingleFqName(): FqName = FqName(listOf(classId?.asSingleFqName()?.asString() ?: packageName.asString(), callableName.asString()).filter { it.isNotEmpty() }.joinToString("."))
}
interface SourceSymbol
interface SourceCallableSymbol : SourceSymbol {
    val callableId: SourceCallableId?
    val name: FqName
    val declaration: SourceElement
    val resolvedAnnotationClassIds: List<SourceClassId>
}
interface SourcePropertySymbol : SourceCallableSymbol {
    val isLocal: Boolean

    /**
     * Whether the compiler replaces every reference to this with its value.
     *
     * The one fact the IR pass cannot recover. A `const val` is folded away
     * before that pass runs, so it sees a literal where this pass sees a name,
     * and a capability named after the name would never match the one the app
     * registered. Knowing it is const is what lets both passes name the value.
     */
    val isConst: Boolean
}
interface SourceReceiverParameterSymbol : SourceSymbol { val containingDeclarationSymbol: SourceSymbol }
interface SourceReference {
    val callableSymbol: SourceCallableSymbol?
    val boundSymbol: SourceSymbol?
}
interface SourceResolvedNamedReference : SourceReference { val name: FqName }
fun SourceReference.toResolvedCallableSymbol(): SourceCallableSymbol? = callableSymbol
interface SourceTypeRef { val type: SourceType? }
inline fun <reified T : SourceType> SourceTypeRef.coneTypeSafe(): T? = type as? T
interface SourceType {
    val classId: SourceClassId?
    val isMarkedNullable: Boolean
    val isExtensionFunctionType: Boolean
    val typeArguments: List<SourceType?>
    val customAnnotations: List<SourceAnnotation>
}
interface SourceAnnotation { val annotationTypeRef: SourceTypeRef }
interface SourceExpression : SourceElement { val resolvedType: SourceType? }
interface SourceBlock : SourceExpression { val statements: List<SourceElement> }
interface SourceFunction : SourceElement {
    val valueParameters: List<SourceValueParameter>
    val body: SourceBlock?
    val returnTypeRef: SourceTypeRef
}
interface SourceNamedFunction : SourceFunction { val symbol: SourceCallableSymbol; val qualifiedName: String }
interface SourceAnonymousFunction : SourceFunction {
    val symbol: SourceSymbol
    val typeRef: SourceTypeRef
}
interface SourceValueParameter : SourceElement {
    val name: FqName
    val returnTypeRef: SourceTypeRef
}
interface SourceProperty : SourceElement {
    val name: FqName
    val returnTypeRef: SourceTypeRef
    val initializer: SourceExpression?
    val delegate: SourceExpression?
    val isVal: Boolean
}
interface SourceFunctionCall : SourceExpression {
    val calleeReference: SourceReference
    val explicitReceiver: SourceExpression?
    val arguments: List<SourceExpression>
    val resolvedArgumentMapping: Map<SourceExpression, SourceValueParameter>?
}
interface SourcePropertyAccessExpression : SourceExpression {
    val calleeReference: SourceReference
    val explicitReceiver: SourceExpression?
}
interface SourceResolvedQualifier : SourceExpression { val classId: SourceClassId?; val relativeClassFqName: FqName? }
interface SourceAnonymousFunctionExpression : SourceExpression { val anonymousFunction: SourceAnonymousFunction }
interface SourceLiteralExpression : SourceExpression { val value: Any?; val kind: ConstantValueKind }
enum class ConstantValueKind { Int, IntegerLiteral, Long, Float, Double, String, Boolean, Other }
interface SourceReturnExpression : SourceExpression { val result: SourceExpression }
interface SourceUnitExpression : SourceExpression
interface SourceElseIfTrueCondition : SourceExpression
interface SourceStringConcatenationCall : SourceExpression { val arguments: List<SourceExpression> }
interface SourceVariableAssignment : SourceElement { val lValue: SourceExpression; val rValue: SourceExpression }
interface SourceWhenExpression : SourceExpression { val subjectName: String?; val subjectInitializer: SourceExpression?; val branches: List<SourceWhenBranch> }
interface SourceWhenBranch : SourceElement { val condition: SourceExpression; val result: SourceBlock }
interface SourceThisReceiverExpression : SourceExpression { val calleeReference: SourceReference }
data class SourceOperation(val operator: String)
interface SourceEqualityOperatorCall : SourceExpression { val arguments: List<SourceExpression>; val operation: SourceOperation }
interface SourceComparisonExpression : SourceExpression { val compareToCall: SourceFunctionCall; val operation: SourceOperation }
interface SourceBooleanOperatorExpression : SourceExpression { val leftOperand: SourceExpression; val rightOperand: SourceExpression; val kind: LogicOperationKind }
enum class LogicOperationKind { AND, OR }
val COMPOSABLE_ANNOTATION = FqName("androidx.compose.runtime.Composable")

interface SourceWhenSubjectExpression : SourceExpression { val subjectName: String }
