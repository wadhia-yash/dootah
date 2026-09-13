package dev.dootah.contract

/**
 * A value a bundle may supply to a native adapter.
 *
 * This is the whole vocabulary. A bundle cannot hand an adapter an arbitrary
 * object, and nothing here is a reference into the app's memory that a bundle
 * chose: every case is either a constant the bundle computed, a name from a
 * table the APK built at the call site, or a token resolved against the
 * installed app's own theme and resources.
 *
 * That closure is what keeps a remote update from becoming remote code with
 * native reach. Adding a case is a deliberate widening of what a bundle can say,
 * and it has to be added in four places that are checked against each other --
 * here, the generator, the bundle runtime, and the renderer.
 */
public sealed interface PropValue {

    public val kind: String

    public data object NullValue : PropValue {
        override val kind: String get() = Kind.NULL
    }

    public data class BoolValue(val value: Boolean) : PropValue {
        override val kind: String get() = Kind.BOOL
    }

    public data class IntValue(val value: Int) : PropValue {
        override val kind: String get() = Kind.INT
    }

    /**
     * Carried as text, not as a number.
     *
     * A bundle's numbers are JavaScript numbers, which are doubles, and a Long
     * past 2^53 loses digits on the way through without anything failing. An id
     * that arrives slightly wrong is worse than one that does not arrive.
     */
    public data class LongValue(val value: Long) : PropValue {
        override val kind: String get() = Kind.LONG
    }

    public data class DoubleValue(val value: Double) : PropValue {
        override val kind: String get() = Kind.DOUBLE
    }

    public data class StringValue(val value: String) : PropValue {
        override val kind: String get() = Kind.STRING
    }

    public data class FloatValue(val value: Float) : PropValue {
        override val kind: String get() = Kind.FLOAT
    }

    public data class DpValue(val value: Double) : PropValue {
        override val kind: String get() = Kind.DP
    }

    public data class ColorValue(val argb: Long) : PropValue {
        override val kind: String get() = Kind.COLOR
    }

    /**
     * A colour read from the installed app's own Material theme.
     *
     * Resolved in the composition rather than baked in, so a remotely described
     * component is themed and follows dark mode the way the native one it
     * replaced did.
     */
    public data class ThemeColorValue(val token: String) : PropValue {
        override val kind: String get() = Kind.THEME_COLOR
    }

    public data class ShapeValue(val token: String) : PropValue {
        override val kind: String get() = Kind.SHAPE
    }

    /** A `Painter` for a drawable the installed app already contains. */
    public data class PainterResourceValue(val key: String) : PropValue {
        override val kind: String get() = Kind.PAINTER_RESOURCE
    }

    /** A string from the installed app's own resources, so it stays localised. */
    public data class StringResourceValue(val key: String) : PropValue {
        override val kind: String get() = Kind.STRING_RESOURCE
    }

    public data class ModifierValue(val operations: List<ModifierOp>) : PropValue {
        override val kind: String get() = Kind.MODIFIER
    }

    public data class ListValue(val elements: List<PropValue>) : PropValue {
        override val kind: String get() = Kind.LIST
    }

    /**
     * One of the screen's own parameters, passed through untouched.
     *
     * A coordinate, not a reference: the bundle says which of the screen's
     * parameters to pass, and the app takes the object out of the table it built
     * from its own call site. A name that is not in that table resolves to
     * nothing. The bundle never sees the object, cannot construct one, and
     * cannot name a parameter of a different screen.
     *
     * This is how a `DrawingCanvasViewModel`, a `List<CustomBrush>` or a
     * `MutableState` reaches a native component that needs it.
     */
    public data class HandleValue(val name: String) : PropValue {
        override val kind: String get() = Kind.HANDLE
    }

    /**
     * The current value of a `MutableState` the screen was given.
     *
     * Read inside the composition, so the component recomposes when the state
     * changes -- which is the behaviour the source being replaced had.
     */
    public data class StateValue(val name: String) : PropValue {
        override val kind: String get() = Kind.STATE
    }

    /** A native action, by the name of the capability the APK generated. */
    public data class CallbackValue(val capability: String, val arity: Int) : PropValue {
        override val kind: String get() = Kind.CALLBACK
    }

    /** The wire discriminator for each case. */
    public object Kind {
        public const val NULL: String = "null"
        public const val BOOL: String = "bool"
        public const val INT: String = "int"
        public const val LONG: String = "long"
        public const val DOUBLE: String = "double"
        public const val STRING: String = "string"
        public const val FLOAT: String = "float"
        public const val DP: String = "dp"
        public const val COLOR: String = "color"
        public const val THEME_COLOR: String = "themeColor"
        public const val SHAPE: String = "shape"
        public const val PAINTER_RESOURCE: String = "painterRes"
        public const val STRING_RESOURCE: String = "stringRes"
        public const val MODIFIER: String = "modifier"
        public const val LIST: String = "list"
        public const val HANDLE: String = "handle"
        public const val STATE: String = "state"
        public const val CALLBACK: String = "callback"

        /** Every kind, for the agreement test the four implementations share. */
        public val ALL: List<String> = listOf(
            NULL, BOOL, INT, LONG, FLOAT, DOUBLE, STRING, DP, COLOR,
            THEME_COLOR, SHAPE, PAINTER_RESOURCE, STRING_RESOURCE, MODIFIER,
            LIST, HANDLE, STATE, CALLBACK,
        )
    }
}

/**
 * One step of a `Modifier` chain, in the order it was written.
 *
 * Order is carried end to end because Compose modifiers are order-sensitive:
 * padding before a background paints differently from padding after it.
 */
public data class ModifierOp(
    val name: String,
    val arguments: Map<String, PropValue> = emptyMap(),
)

/** The modifier steps a bundle may describe, and what each one takes. */
public object ModifierOps {

    /** The `Modifier` the screen's caller passed in, spliced into the chain. */
    public const val INHERITED: String = "inherited"
    public const val SIZE: String = "size"
    public const val WIDTH: String = "width"
    public const val HEIGHT: String = "height"
    public const val PADDING: String = "padding"
    public const val FILL_MAX_WIDTH: String = "fillMaxWidth"
    public const val FILL_MAX_HEIGHT: String = "fillMaxHeight"
    public const val FILL_MAX_SIZE: String = "fillMaxSize"
    public const val WEIGHT: String = "weight"
    public const val BACKGROUND: String = "background"

    public val ARGUMENTS: Map<String, List<String>> = mapOf(
        INHERITED to emptyList(),
        SIZE to listOf("width", "height"),
        WIDTH to listOf("value"),
        HEIGHT to listOf("value"),
        PADDING to listOf("start", "top", "end", "bottom"),
        FILL_MAX_WIDTH to listOf("fraction"),
        FILL_MAX_HEIGHT to listOf("fraction"),
        FILL_MAX_SIZE to listOf("fraction"),
        WEIGHT to listOf("value"),
        BACKGROUND to listOf("color", "shape"),
    )

    public val ALL: List<String> = ARGUMENTS.keys.sorted()
}
