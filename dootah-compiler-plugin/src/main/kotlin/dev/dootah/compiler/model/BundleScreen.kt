package dev.dootah.compiler.model

/**
 * One `@Bundlable` screen, reduced to the subset Dootah can send over the air.
 *
 * This is the whole contract between the frontend that reads the developer's
 * Kotlin and the writer that emits bundle Kotlin. Nothing outside this model can
 * reach a bundle, which is what makes "unsupported" a decision taken once,
 * during lowering, rather than a surprise discovered at run time.
 */
internal data class BundleScreen(
    val screenId: String,
    val functionName: String,

    /** Local `val`s in declaration order; later ones may reference earlier ones. */
    val locals: List<BundleLocal>,

    val ui: BundleUi,
)

internal data class BundleLocal(
    val name: String,
    val type: BundleType,
    val value: BundleExpression,
)

/** The value types Milestone 1 can evaluate inside a bundle. */
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
}
