package dev.dootah.compiler.fir

import dev.dootah.compiler.model.ArithmeticOperator
import org.jetbrains.kotlin.name.FqName

/**
 * Exactly what Dootah can lower, listed by the fully qualified name a call
 * resolves to.
 *
 * Matching on resolved names rather than on the text at the call site is what
 * makes this trustworthy: a locally declared `Text` is a different function from
 * Compose's, and only the resolved symbol distinguishes them.
 *
 * The list is deliberately short, and it is not a compatibility table to be
 * filled in. Each entry was added because a real screen needed it; widening it
 * is a decision to support, test and document another construct, never a side
 * effect of making one screen compile. Anything outside it either becomes a
 * native slot -- drawn by the code already in the APK -- or is refused.
 */
internal object SupportedCatalog {

    private const val LAYOUT = "androidx.compose.foundation.layout"
    private const val MATERIAL = "androidx.compose.material3"

    val COLUMN: FqName = FqName("$LAYOUT.Column")
    val ROW: FqName = FqName("$LAYOUT.Row")
    val BOX: FqName = FqName("$LAYOUT.Box")

    val TEXT: FqName = FqName("$MATERIAL.Text")
    val BUTTON: FqName = FqName("$MATERIAL.Button")

    val MODIFIER_TYPE: FqName = FqName("androidx.compose.ui.Modifier")

    /** `Modifier`'s companion, which is what a bare `Modifier` resolves to. */
    val MODIFIER_COMPANION: FqName = FqName("androidx.compose.ui.Modifier.Companion")

    /** `16.dp`, whichever numeric type it is written on. */
    val DP_PROPERTY: FqName = FqName("androidx.compose.ui.unit.dp")

    /** `Color(0xFF2196F3)`, the only colour form Dootah reads. */
    val COLOR_FUNCTION: FqName = FqName("androidx.compose.ui.graphics.Color")

    /** `painterResource(R.drawable.x)` and `stringResource(R.string.x)`. */
    val PAINTER_RESOURCE: FqName = FqName("androidx.compose.ui.res.painterResource")
    val STRING_RESOURCE: FqName = FqName("androidx.compose.ui.res.stringResource")

    /** `MaterialTheme.colorScheme.<token>`, resolved by the installed app. */
    val MATERIAL_THEME: FqName = FqName("$MATERIAL.MaterialTheme")
    val COLOR_SCHEME_PROPERTY: FqName = FqName("$MATERIAL.MaterialTheme.colorScheme")

    val CIRCLE_SHAPE: FqName = FqName("androidx.compose.foundation.shape.CircleShape")
    val RECTANGLE_SHAPE: FqName = FqName("androidx.compose.ui.graphics.RectangleShape")

    /**
     * `Color.White` and its siblings, which are compile-time constants Compose
     * declares on the companion. Listed because FIR does not fold them, and a
     * bundle has to carry the number rather than the name.
     */
    val NAMED_COLORS: Map<String, Long> = mapOf(
        "Black" to 0xFF000000L,
        "DarkGray" to 0xFF444444L,
        "Gray" to 0xFF888888L,
        "LightGray" to 0xFFCCCCCCL,
        "White" to 0xFFFFFFFFL,
        "Red" to 0xFFFF0000L,
        "Green" to 0xFF00FF00L,
        "Blue" to 0xFF0000FFL,
        "Yellow" to 0xFFFFFF00L,
        "Cyan" to 0xFF00FFFFL,
        "Magenta" to 0xFFFF00FFL,
        "Transparent" to 0x00000000L,
    )

    val COLOR_COMPANION: FqName = FqName("androidx.compose.ui.graphics.Color.Companion")

    val PADDING: FqName = FqName("$LAYOUT.padding")
    val FILL_MAX_WIDTH: FqName = FqName("$LAYOUT.fillMaxWidth")
    val FILL_MAX_HEIGHT: FqName = FqName("$LAYOUT.fillMaxHeight")
    val FILL_MAX_SIZE: FqName = FqName("$LAYOUT.fillMaxSize")
    val SIZE: FqName = FqName("$LAYOUT.size")
    val WIDTH: FqName = FqName("$LAYOUT.width")
    val HEIGHT: FqName = FqName("$LAYOUT.height")
    val COLUMN_WEIGHT: FqName = FqName("$LAYOUT.ColumnScope.weight")
    val ROW_WEIGHT: FqName = FqName("$LAYOUT.RowScope.weight")
    val BACKGROUND: FqName = FqName("androidx.compose.foundation.background")

    private val ARITHMETIC: Map<FqName, ArithmeticOperator> = buildMap {
        listOf("kotlin.Int", "kotlin.Long").forEach { receiver ->
            put(FqName("$receiver.plus"), ArithmeticOperator.PLUS)
            put(FqName("$receiver.minus"), ArithmeticOperator.MINUS)
            put(FqName("$receiver.times"), ArithmeticOperator.TIMES)
            put(FqName("$receiver.div"), ArithmeticOperator.DIV)
            put(FqName("$receiver.rem"), ArithmeticOperator.REM)
        }
        // String concatenation reuses the arithmetic path: `+` on strings is
        // the same shape, and the generated Kotlin is the same operator.
        put(FqName("kotlin.String.plus"), ArithmeticOperator.PLUS)
    }

    private val NOT: FqName = FqName("kotlin.Boolean.not")
    private val UNARY_MINUS: Set<FqName> =
        setOf(FqName("kotlin.Int.unaryMinus"), FqName("kotlin.Long.unaryMinus"))

    fun arithmeticFor(callable: FqName): ArithmeticOperator? = ARITHMETIC[callable]

    fun isNot(callable: FqName): Boolean = callable == NOT

    fun isUnaryMinus(callable: FqName): Boolean = callable in UNARY_MINUS

    /** The composables a screen body may lower directly, for use in diagnostics. */
    val SUPPORTED_COMPOSABLES: List<FqName> = listOf(COLUMN, ROW, BOX, TEXT, BUTTON)

    /** The modifiers a screen may use, for use in diagnostics. */
    val SUPPORTED_MODIFIERS: List<String> = listOf(
        "padding", "fillMaxWidth", "fillMaxHeight", "fillMaxSize",
        "size", "width", "height", "weight", "background",
    )
}
