package ui

/**
 * The hand-written bundle DSL.
 *
 * Retained for the hand-written conformance bundle in `:dootah-bundle`, which
 * exercises the runtime protocol without going through the compiler. Generated
 * bundles construct nodes directly instead: a generator has no use for a
 * builder's ergonomics, and one shape fewer is one shape fewer to keep correct.
 */
class ColumnScope {

    internal val children = mutableListOf<BundleNode>()

    fun Text(text: String) {
        children.add(TextNode(text))
    }

    fun Button(text: String, action: String) {
        children.add(ButtonNode(text = text, action = action))
    }
}

fun Column(content: ColumnScope.() -> Unit): BundleNode {

    val scope = ColumnScope()
    scope.content()

    return ColumnNode(children = scope.children)
}
