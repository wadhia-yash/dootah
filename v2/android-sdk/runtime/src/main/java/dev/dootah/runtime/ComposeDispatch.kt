package dev.dootah.runtime

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.dootah.portable.UiTree
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

@Composable
@ReadOnlyComposable
fun dispatchValue(): RenderValue? {
    val view = LocalView.current
    return (view.context.applicationContext as? DootahApplication)?.dispatchValue
}

@Composable
fun renderOverride(result: UiTree.Result, value: RenderValue) {
    when {
        result.basicText != null -> BasicText(result.basicText, Modifier.dootahFrame(value))
        result.root.kind == "box" || result.root.kind == "text" -> renderTree(result.root, result.slots, Modifier.dootahFrame(value), null)
        else -> {
            // A conditional or app-owned root may draw nothing of its own: acknowledge the committed frame.
            renderTree(result.root, result.slots, Modifier, null)
            CommitAcknowledgement(value)
        }
    }
}

/** Every app-owned component the artifact names must be installed in this APK, as signed; else the update is refused. */
internal fun requireInstalledAdapters(program: dev.dootah.portable.PortableProgram, registry: NativeAdapterRegistry) {
    program.nativeAdapters().forEach { (adapter, signature) ->
        require(NativeAdapterHost.installed(registry, adapter, signature)) { "Native adapter not installed" }
    }
}

/** Every active native call names an installed adapter with matching scalar values. Checked before rendering. */
internal fun nativeCallsAccepted(result: UiTree.Result, registry: NativeAdapterRegistry): Boolean {
    fun accepted(node: UiTree.Node): Boolean = when (node.kind) {
        "native" -> NativeAdapterHost.accepts(registry, node.adapter, adapterArguments(node, result.slots))
        "if" -> (if (result.slots[node.slot] as Boolean) node.children else node.otherwise).all(::accepted)
        else -> node.children.all(::accepted)
    }
    return result.root == null || accepted(result.root)
}

private fun adapterArguments(node: UiTree.Node, slots: List<Any>): List<Any?> =
    node.arguments.map { index -> slots[index].takeUnless { it == org.json.JSONObject.NULL } }

@Composable
@ReadOnlyComposable
fun portableText(id: String, types: String, input: Array<Any?>, value: RenderValue): UiTree.Result? {
    val app = LocalView.current.context.applicationContext as DootahApplication
    return app.lookup(value, id, if (types.isEmpty()) emptyList() else types.split(','), input.toList())
}

/** Render only immutable, completely validated native data, in the caller's composition locals. */
@Composable
internal fun renderTree(node: UiTree.Node, slots: List<Any>, root: Modifier = Modifier, box: BoxScope? = null,
                        registry: NativeAdapterRegistry = InstalledAdapters.registry) {
    if (node.kind == "native") {
        // Verified before this result was published to composition; a refusal here composes nothing.
        NativeAdapterHost.render(registry, node.adapter, adapterArguments(node, slots))
        return
    }
    if (node.kind == "if") {
        val children = if (slots[node.slot] as Boolean) node.children else node.otherwise
        children.forEach { renderTree(it, slots, root, box, registry) }
        return
    }
    var modifier: Modifier = Modifier
    node.modifiers.forEach { m ->
        modifier = when (m.op) {
            "fillMaxWidth" -> modifier.fillMaxWidth()
            "padding" -> modifier.padding(start = m.start.dp, top = m.top.dp, end = m.end.dp, bottom = m.bottom.dp)
            "align" -> with(requireNotNull(box)) { modifier.align(alignment(m.alignment)) }
            else -> error("Unvalidated modifier")
        }
    }
    modifier = root.then(modifier)
    when (node.kind) {
        "box" -> Box(modifier = modifier, contentAlignment = alignment(node.alignment)) {
            node.children.forEach { renderTree(it, slots, box = this, registry = registry) }
        }
        "text" -> Text(
            text = slots[node.slot] as String,
            modifier = modifier,
            color = when (node.color) {
                null -> Color.Unspecified
                "secondary" -> MaterialTheme.colorScheme.secondary
                "tertiary" -> MaterialTheme.colorScheme.tertiary
                else -> error("Unvalidated color")
            },
            style = when (node.style) {
                null -> LocalTextStyle.current
                "bodyMedium" -> MaterialTheme.typography.bodyMedium
                "labelSmall" -> MaterialTheme.typography.labelSmall
                else -> error("Unvalidated typography")
            },
        )
        else -> error("Unvalidated node")
    }
}

private fun alignment(token: String): Alignment = when (token) {
    "TopStart" -> Alignment.TopStart
    "TopCenter" -> Alignment.TopCenter
    "TopEnd" -> Alignment.TopEnd
    "CenterStart" -> Alignment.CenterStart
    "Center" -> Alignment.Center
    "CenterEnd" -> Alignment.CenterEnd
    "BottomStart" -> Alignment.BottomStart
    "BottomCenter" -> Alignment.BottomCenter
    "BottomEnd" -> Alignment.BottomEnd
    else -> error("Unvalidated alignment")
}
