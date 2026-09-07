package ui

class ColumnScope {

    internal val children =
        mutableListOf<PatchNode>()

    fun Text(text: String) {
        children.add(
            TextNode(text)
        )
    }

    fun Button(
        text: String,
        action: String
    ) {
        children.add(
            ButtonNode(
                text = text,
                action = action
            )
        )
    }
}

fun Column(
    content: ColumnScope.() -> Unit
): PatchNode {

    val scope = ColumnScope()

    scope.content()

    return ColumnNode(
        children = scope.children
    )
}