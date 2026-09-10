package dev.dootah.compiler.model

/**
 * The UI a bundle can describe.
 *
 * Mirrors the node types the installed renderer already understands, so a
 * lowered screen cannot describe something the app could not draw.
 */
internal sealed interface BundleUi {

    data class ColumnUi(val children: List<BundleUi>) : BundleUi

    data class TextUi(val text: BundleExpression) : BundleUi

    /**
     * A button and the action name the app sends back when it is tapped.
     *
     * Milestone 1 only lowers buttons whose `onClick` is empty, so the action is
     * an identity for the tap and nothing more. It exists now because the
     * installed renderer already requires one.
     */
    data class ButtonUi(
        val label: BundleExpression,
        val action: String,
    ) : BundleUi
}
