package com.dootah.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Draws a bundle's UI tree with Compose.
 *
 * This is the whole renderer, and it is meant to stay small. It maps a closed
 * node model onto real Compose composables, which keeps Dootah from becoming a
 * second implementation of Compose and leaves room for a different rendering
 * backend behind the same node model later.
 *
 * [inherited] is the `Modifier` the native caller passed to the screen. It is
 * spliced in wherever the bundle says [BundleUiModifier.Inherited], so a
 * remote implementation honours the layout its caller asked for without ever
 * seeing what that layout is.
 */
@Composable
internal fun BundleRenderer(
    node: BundleUiNode,
    slots: DootahSlots,
    inherited: Modifier,
    onAction: (String) -> Unit,
) {
    RenderNode(
        node = node,
        slots = slots,
        inherited = inherited,
        onAction = onAction,
        weightApplier = IgnoreWeight,
    )
}

/**
 * Applies a weight in the layout scope that has one.
 *
 * `weight` is declared on `ColumnScope` and `RowScope`, so a node cannot apply
 * its own: only its parent knows whether weight means anything here. The parent
 * passes down the applier for its scope, and the root passes one that ignores
 * weight because there is no scope to take it.
 */
private fun interface WeightApplier {
    fun apply(modifier: Modifier, weight: Float): Modifier
}

private val IgnoreWeight = WeightApplier { modifier, _ -> modifier }

@Composable
private fun RenderNode(
    node: BundleUiNode,
    slots: DootahSlots,
    inherited: Modifier,
    onAction: (String) -> Unit,
    weightApplier: WeightApplier,
) {

    val modifier = node.modifiers().toModifier(inherited, weightApplier)

    when (node) {

        is BundleUiNode.Column -> Column(modifier = modifier) {
            val applier = WeightApplier { child, weight -> child.weight(weight) }
            node.children.forEach { child ->
                RenderNode(child, slots, inherited, onAction, applier)
            }
        }

        is BundleUiNode.Row -> Row(modifier = modifier) {
            val applier = WeightApplier { child, weight -> child.weight(weight) }
            node.children.forEach { child ->
                RenderNode(child, slots, inherited, onAction, applier)
            }
        }

        is BundleUiNode.Box -> Box(modifier = modifier) {
            node.children.forEach { child ->
                RenderNode(child, slots, inherited, onAction, IgnoreWeight)
            }
        }

        is BundleUiNode.Text -> Text(text = node.text, modifier = modifier)

        is BundleUiNode.Button -> Button(
            onClick = { onAction(node.action) },
            modifier = modifier,
        ) {
            Text(node.text)
        }

        is BundleUiNode.NativeSlot -> slots.Render(node.slot)
    }
}

private fun BundleUiNode.modifiers(): List<BundleUiModifier> = when (this) {
    is BundleUiNode.Container -> modifiers
    is BundleUiNode.Text -> modifiers
    is BundleUiNode.Button -> modifiers
    is BundleUiNode.NativeSlot -> emptyList()
}

/**
 * Builds one Compose modifier by applying the bundle's entries in order.
 *
 * Order is the whole reason this is a fold rather than a set of properties: the
 * same entries in a different order describe a different layout, and a bundle
 * that says "background, then padding" must not be drawn as its opposite.
 */
private fun List<BundleUiModifier>.toModifier(
    inherited: Modifier,
    weightApplier: WeightApplier,
): Modifier = fold(Modifier as Modifier) { modifier, entry ->

    when (entry) {

        is BundleUiModifier.Inherited -> modifier.then(inherited)

        is BundleUiModifier.Padding -> modifier.padding(
            PaddingValues(
                start = entry.start.dp,
                top = entry.top.dp,
                end = entry.end.dp,
                bottom = entry.bottom.dp,
            )
        )

        is BundleUiModifier.FillMaxWidth -> modifier.fillMaxWidth(entry.fraction)
        is BundleUiModifier.FillMaxHeight -> modifier.fillMaxHeight(entry.fraction)
        is BundleUiModifier.FillMaxSize -> modifier.fillMaxSize(entry.fraction)

        is BundleUiModifier.Size -> modifier.size(width = entry.width.dp, height = entry.height.dp)
        is BundleUiModifier.Width -> modifier.width(entry.value.dp)
        is BundleUiModifier.Height -> modifier.height(entry.value.dp)

        is BundleUiModifier.Weight -> weightApplier.apply(modifier, entry.value)

        is BundleUiModifier.Background -> modifier.background(Color(entry.color))
    }
}
