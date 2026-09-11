package dev.dootah.compiler.model

/**
 * One `@Bundlable` screen, reduced to the subset Dootah can send over the air.
 *
 * The whole contract between the frontend that reads the developer's Kotlin and
 * the writer that emits bundle Kotlin. Nothing outside this model can reach a
 * bundle, which is what makes "unsupported" a decision taken once, during
 * lowering, rather than a surprise discovered at run time.
 */
internal data class BundleScreen(
    val screenId: String,
    val functionName: String,

    /** Parameters whose values travel to the bundle, in declaration order. */
    val parameters: List<BundleParameter>,

    /** `() -> Unit` parameters the bundle may ask the app to invoke, by name. */
    val callbacks: List<String>,

    /** Declarations evaluated before the UI is built, in source order. */
    val prelude: List<BundleStatement>,

    val ui: BundleUi,

    /** What each button does, keyed by the action name the app sends back. */
    val actions: List<BundleAction>,

    /** Functions this screen calls, lowered alongside it. */
    val functions: List<BundleFunction>,

    /**
     * Components kept native, named as the developer wrote them.
     *
     * Reported by the build. A component Dootah keeps native keeps working
     * exactly as it did, but it will not change when a bundle is published, and
     * a developer who edits one and sees nothing happen deserves to have been
     * told which ones those are.
     */
    val nativeComponents: List<String>,
)

internal data class BundleParameter(
    val name: String,
    val type: BundleType,
    val isNullable: Boolean,
)

internal data class BundleAction(
    val name: String,
    val body: List<BundleStatement>,
)

/**
 * A function a screen calls, lowered into the bundle beside it.
 *
 * Limited to functions whose body is one expression over the supported types.
 * Anything more is a program rather than a value, and Dootah's remote surface is
 * deliberately the latter.
 */
internal data class BundleFunction(
    val name: String,
    val parameters: List<BundleParameter>,
    val returnType: BundleType,
    val body: BundleExpression,
)

/** The value types Dootah can evaluate inside a bundle. */
internal enum class BundleType {
    INT,
    STRING,
    BOOLEAN;

    /** The Kotlin type name to emit in generated source. */
    val kotlinName: String
        get() = when (this) {
            INT -> "Int"
            STRING -> "String"
            BOOLEAN -> "Boolean"
        }

    /** The accessor suffix used by the bundle's argument and state stores. */
    val accessor: String
        get() = when (this) {
            INT -> "int"
            STRING -> "string"
            BOOLEAN -> "boolean"
        }
}
