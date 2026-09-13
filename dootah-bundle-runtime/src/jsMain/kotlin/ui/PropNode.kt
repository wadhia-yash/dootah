package ui

/**
 * A value a bundle supplies to a native component.
 *
 * The bundle side of the argument vocabulary the installed app resolves. Closed
 * on purpose: a bundle can hand a component a constant it computed, a name from
 * a table the app built at its own call site, or a token resolved against the
 * app's own theme and resources -- and nothing else. There is no case for an
 * arbitrary object, because there is no way for a bundle to have one.
 *
 * Declared here as well as in the compiler's contract module because this side
 * is Kotlin/JS and cannot depend on a JVM module. The two are held together by a
 * test that reads the vocabulary from both.
 */
sealed interface PropNode

data class NullProp(private val unused: Int = 0) : PropNode

data class BoolProp(val value: Boolean) : PropNode

data class IntProp(val value: Int) : PropNode

/**
 * Written as text, not as a number.
 *
 * Every number here is a JavaScript double. Past 2^53 a Long silently loses its
 * low digits, and an identifier that arrives almost right is worse than one that
 * does not arrive at all.
 */
data class LongProp(val value: String) : PropNode

data class FloatProp(val value: Double) : PropNode

data class DoubleProp(val value: Double) : PropNode

data class StringProp(val value: String) : PropNode

data class DpProp(val value: Double) : PropNode

data class ColorProp(val argb: Long) : PropNode

/** A colour read from the installed app's own Material theme. */
data class ThemeColorProp(val token: String) : PropNode

data class ShapeProp(val token: String) : PropNode

/** A drawable the installed app already contains, by name rather than by id. */
data class PainterResourceProp(val key: String) : PropNode

data class StringResourceProp(val key: String) : PropNode

data class ModifierProp(val operations: List<ModifierOpNode> = emptyList()) : PropNode

data class ListProp(val elements: List<PropNode> = emptyList()) : PropNode

/**
 * One of the screen's own parameters, passed through untouched.
 *
 * A coordinate, not a reference. The bundle says which parameter; the app takes
 * the object out of the table it built at its own call site. This is how a view
 * model or a list of domain objects reaches a native component without the
 * bundle ever seeing it.
 */
data class HandleProp(val name: String) : PropNode

/** The current value of a `MutableState` the screen was given. */
data class StateProp(val name: String) : PropNode

/** A native action, by the name of the capability the APK generated for it. */
data class CallbackProp(val id: String, val arity: Int = 0) : PropNode

/** One step of a `Modifier` chain, in the order it was written. */
data class ModifierOpNode(
    val op: String,
    val arguments: Map<String, PropNode> = emptyMap(),
)
