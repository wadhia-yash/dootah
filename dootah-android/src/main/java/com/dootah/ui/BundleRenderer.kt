// Dootah's own runtime, which Dootah must never take over. Discovery
// finds every eligible composable in a compilation; an app that built
// this module from source rather than resolving it as a library would
// otherwise have these intercepted, including the one that renders a
// remote screen.
@file:dev.dootah.DootahNative

package com.dootah.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dootah.DOOTAH_LOG_TAG
import dev.dootah.contract.Arrangements
import dev.dootah.contract.Dimension
import dev.dootah.contract.LayoutArrangement
import dev.dootah.contract.ModifierOps
import dev.dootah.contract.PropValue
import dev.dootah.contract.Shapes

/**
 * Draws a bundle's UI tree with Compose.
 *
 * This is the whole renderer, and it is meant to stay small. Dootah describes
 * layout and logic itself and hands everything else to a native adapter, which
 * is what keeps it from becoming a second implementation of Compose.
 *
 * [inherited] is the `Modifier` the native caller passed to the screen. It is
 * spliced in wherever the bundle says so, which lets a remote implementation
 * honour the layout its caller asked for without ever seeing what that layout is.
 */
@Composable
internal fun BundleRenderer(
    node: BundleUiNode,
    bindings: DootahNativeBindings,
    inherited: Modifier,
    onAction: (String) -> Unit,
) {
    RenderNode(
        node = node,
        bindings = bindings,
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
    bindings: DootahNativeBindings,
    inherited: Modifier,
    onAction: (String) -> Unit,
    weightApplier: WeightApplier,
) {

    val modifier = node.modifiers().toModifier(inherited, weightApplier, bindings)

    when (node) {

        // Compose's own defaults are named here rather than sent, so a bundle
        // that says nothing about alignment gets whatever this build of Compose
        // considers default -- not whatever the compiling machine's did.
        is BundleUiNode.Column -> Column(
            modifier = modifier,
            horizontalAlignment = node.horizontalAlignment
                ?.let { horizontalAlignment(it) } ?: Alignment.Start,
            verticalArrangement = node.verticalArrangement
                ?.let { verticalArrangement(it, bindings) } ?: Arrangement.Top,
        ) {
            val applier = WeightApplier { child, weight -> child.weight(weight) }
            node.children.forEach { child ->
                RenderNode(child, bindings, inherited, onAction, applier)
            }
        }

        is BundleUiNode.Row -> Row(
            modifier = modifier,
            verticalAlignment = node.verticalAlignment
                ?.let { verticalAlignment(it) } ?: Alignment.Top,
            horizontalArrangement = node.horizontalArrangement
                ?.let { horizontalArrangement(it, bindings) } ?: Arrangement.Start,
        ) {
            val applier = WeightApplier { child, weight -> child.weight(weight) }
            node.children.forEach { child ->
                RenderNode(child, bindings, inherited, onAction, applier)
            }
        }

        is BundleUiNode.Box -> Box(
            modifier = modifier,
            contentAlignment = node.contentAlignment
                ?.let { boxAlignment(it) } ?: Alignment.TopStart,
        ) {
            node.children.forEach { child ->
                RenderNode(child, bindings, inherited, onAction, IgnoreWeight)
            }
        }

        is BundleUiNode.Text -> Text(text = node.text, modifier = modifier)

        is BundleUiNode.Button -> Button(
            onClick = { onAction(node.action) },
            modifier = modifier,
        ) {
            Text(node.text)
        }

        // Drawn straight into whatever encloses the screen, keeping the
        // enclosing scope's weight applier: these children sit exactly where the
        // screen's own call site does.
        is BundleUiNode.Fragment -> node.children.forEach { child ->
            RenderNode(child, bindings, inherited, onAction, weightApplier)
        }

        is BundleUiNode.Component ->
            RenderComponent(node, bindings, inherited, onAction)
    }
}

/**
 * Places one instance of a native adapter.
 *
 * The props are resolved here, in the composition, because that is the only
 * place a theme colour, a string resource and a painter can be read -- and
 * because resolving them here means a themed component follows dark mode and a
 * localised string stays localised, exactly as the native code being replaced
 * did. An adapter is handed finished objects, never descriptions of them.
 */
@Composable
private fun RenderComponent(
    node: BundleUiNode.Component,
    bindings: DootahNativeBindings,
    inherited: Modifier,
    onAction: (String) -> Unit,
) {

    val adapter = bindings.adapters[node.adapterId]

    if (adapter == null) {
        // Already reported by the check that runs before anything is drawn; a
        // component arriving here is not worth a second crash.
        Log.w(DOOTAH_LOG_TAG, "no adapter for ${node.adapterId}")
        return
    }

    val resolved = LinkedHashMap<String, Any?>(node.props.size)

    for ((name, value) in node.props) {
        resolved[name] = value.resolve(bindings)
    }

    val childContent = LinkedHashMap<String, @Composable () -> Unit>(node.children.size)

    for ((name, children) in node.children) {
        childContent[name] = {
            children.forEach { child ->
                RenderNode(child, bindings, inherited, onAction, IgnoreWeight)
            }
        }
    }

    adapter.content(
        DootahProps(
            values = resolved,
            childContent = childContent,
            invoker = bindings.capabilities::invoke,
        )
    )
}

/**
 * Turns one prop into the object the adapter's parameter actually takes.
 *
 * Exhaustive with no `else`: every case here is a deliberate widening of what a
 * bundle is allowed to say, and a new one must not be able to arrive silently
 * unresolved.
 */
@Composable
private fun PropValue.resolve(bindings: DootahNativeBindings): Any? = when (this) {

    is PropValue.NullValue -> null
    is PropValue.BoolValue -> value
    is PropValue.IntValue -> value
    is PropValue.LongValue -> value
    is PropValue.FloatValue -> value
    is PropValue.DoubleValue -> value
    is PropValue.StringValue -> value

    is PropValue.DpValue -> value.toFloat().dp
    is PropValue.ColorValue -> Color(argb)
    is PropValue.ThemeColorValue -> themeColor(token)
    is PropValue.ShapeValue -> shapeOf(token)

    is PropValue.PainterResourceValue ->
        bindings.resources[key]?.let { id -> painterResource(id) }

    is PropValue.StringResourceValue ->
        bindings.resources[key]?.let { id -> stringResource(id) }

    is PropValue.ModifierValue -> operations.toModifier(Modifier, IgnoreWeight, bindings)

    is PropValue.ListValue -> elements.map { element -> element.resolve(bindings) }

    // A coordinate into the table the app built at its own call site, never a
    // reference the bundle could have constructed.
    is PropValue.HandleValue -> bindings.handles[name]

    // Read inside the composition, so the component recomposes when the state
    // changes -- which is what the source being replaced did.
    is PropValue.StateValue -> (bindings.handles[name] as? State<*>)?.value

    // Carried as the capability's name. DootahProps turns it into a function of
    // the shape the adapter's parameter declares.
    is PropValue.CallbackValue -> capability
}

private fun BundleUiNode.modifiers(): List<BundleUiModifier> = when (this) {
    is BundleUiNode.Container -> modifiers
    is BundleUiNode.Text -> modifiers
    is BundleUiNode.Button -> modifiers
    is BundleUiNode.Fragment -> emptyList()
    is BundleUiNode.Component -> emptyList()
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
    bindings: DootahNativeBindings,
): Modifier = fold(Modifier as Modifier) { modifier, entry ->

    when (entry) {

        is BundleUiModifier.Inherited -> modifier.then(inherited)

        is BundleUiModifier.Padding -> modifier.padding(
            PaddingValues(
                start = entry.start.resolve(bindings),
                top = entry.top.resolve(bindings),
                end = entry.end.resolve(bindings),
                bottom = entry.bottom.resolve(bindings),
            )
        )

        is BundleUiModifier.FillMaxWidth -> modifier.fillMaxWidth(entry.fraction)
        is BundleUiModifier.FillMaxHeight -> modifier.fillMaxHeight(entry.fraction)
        is BundleUiModifier.FillMaxSize -> modifier.fillMaxSize(entry.fraction)

        is BundleUiModifier.Size -> modifier.size(
            width = entry.width.resolve(bindings),
            height = entry.height.resolve(bindings),
        )
        is BundleUiModifier.Width -> modifier.width(entry.value.resolve(bindings))
        is BundleUiModifier.Height -> modifier.height(entry.value.resolve(bindings))

        is BundleUiModifier.Weight -> weightApplier.apply(modifier, entry.value)

        is BundleUiModifier.Background -> modifier.background(Color(entry.color))
    }
}

/**
 * Builds a modifier a bundle supplied as an adapter's argument.
 *
 * Separate from the node-level chain above because the steps here may carry
 * props of their own -- a themed background colour, a shape -- which the node
 * model's fixed fields cannot express.
 */
@Composable
private fun List<dev.dootah.contract.ModifierOp>.toModifier(
    inherited: Modifier,
    weightApplier: WeightApplier,
    bindings: DootahNativeBindings,
): Modifier {

    var modifier: Modifier = Modifier

    for (operation in this) {

        val arguments = LinkedHashMap<String, Any?>(operation.arguments.size)

        for ((name, value) in operation.arguments) {
            arguments[name] = value.resolve(bindings)
        }

        modifier = when (operation.name) {

            ModifierOps.INHERITED -> modifier.then(inherited)

            ModifierOps.SIZE -> modifier.size(
                width = arguments.dp("width"),
                height = arguments.dp("height"),
            )

            ModifierOps.WIDTH -> modifier.width(arguments.dp("value"))
            ModifierOps.HEIGHT -> modifier.height(arguments.dp("value"))

            ModifierOps.PADDING -> modifier.padding(
                PaddingValues(
                    start = arguments.dp("start"),
                    top = arguments.dp("top"),
                    end = arguments.dp("end"),
                    bottom = arguments.dp("bottom"),
                )
            )

            ModifierOps.FILL_MAX_WIDTH -> modifier.fillMaxWidth(arguments.fraction())
            ModifierOps.FILL_MAX_HEIGHT -> modifier.fillMaxHeight(arguments.fraction())
            ModifierOps.FILL_MAX_SIZE -> modifier.fillMaxSize(arguments.fraction())

            ModifierOps.WEIGHT ->
                weightApplier.apply(modifier, arguments.fraction())

            ModifierOps.BACKGROUND -> {
                val color = arguments["color"] as? Color ?: Color.Unspecified
                val shape = arguments["shape"] as? Shape
                if (shape == null) modifier.background(color)
                else modifier.background(color, shape)
            }

            else -> {
                Log.w(DOOTAH_LOG_TAG, "unknown modifier '${operation.name}'")
                modifier
            }
        }
    }

    return modifier
}

private fun Map<String, Any?>.dp(name: String): androidx.compose.ui.unit.Dp =
    this[name] as? androidx.compose.ui.unit.Dp ?: 0.dp

private fun Map<String, Any?>.fraction(): Float =
    (this["fraction"] ?: this["value"]) as? Float ?: 1f

/**
 * Resolves an alignment or arrangement name to the value this build has.
 *
 * Exhaustive lists rather than a lookup by name, for the same reason
 * [themeColor] is one: the bundle names a token from a closed vocabulary and
 * what that token means is decided entirely here. There is no reflection, no
 * `valueOf`, and nothing a bundle can name that this file does not spell out.
 *
 * A name outside the vocabulary never reaches these -- the parser refuses the
 * whole response first -- so the `else` branches exist to satisfy the compiler
 * and to fail loudly if that ever stops being true, rather than to guess.
 *
 * Each axis is separate because Compose's types are: `Alignment.Start` is a
 * `Horizontal` and cannot be a `Row`'s `verticalAlignment`, and the compiler
 * enforces here what the vocabulary asserts in the contract.
 */
private fun horizontalAlignment(token: String): Alignment.Horizontal = when (token) {
    "Start" -> Alignment.Start
    "CenterHorizontally" -> Alignment.CenterHorizontally
    "End" -> Alignment.End
    else -> unknownLayoutToken("horizontal alignment", token, Alignment.Start)
}

private fun verticalAlignment(token: String): Alignment.Vertical = when (token) {
    "Top" -> Alignment.Top
    "CenterVertically" -> Alignment.CenterVertically
    "Bottom" -> Alignment.Bottom
    else -> unknownLayoutToken("vertical alignment", token, Alignment.Top)
}

private fun boxAlignment(token: String): Alignment = when (token) {
    "TopStart" -> Alignment.TopStart
    "TopCenter" -> Alignment.TopCenter
    "TopEnd" -> Alignment.TopEnd
    "CenterStart" -> Alignment.CenterStart
    "Center" -> Alignment.Center
    "CenterEnd" -> Alignment.CenterEnd
    "BottomStart" -> Alignment.BottomStart
    "BottomCenter" -> Alignment.BottomCenter
    "BottomEnd" -> Alignment.BottomEnd
    else -> unknownLayoutToken("alignment", token, Alignment.TopStart)
}

private fun verticalArrangement(
    value: LayoutArrangement,
    bindings: DootahNativeBindings,
): Arrangement.Vertical =
    when (value.token) {
        "Top" -> Arrangement.Top
        "Bottom" -> Arrangement.Bottom
        "Center" -> Arrangement.Center
        "SpaceBetween" -> Arrangement.SpaceBetween
        "SpaceAround" -> Arrangement.SpaceAround
        "SpaceEvenly" -> Arrangement.SpaceEvenly
        Arrangements.SPACED_BY -> Arrangement.spacedBy(value.gap(bindings))
        else -> unknownLayoutToken("vertical arrangement", value.token, Arrangement.Top)
    }

private fun horizontalArrangement(
    value: LayoutArrangement,
    bindings: DootahNativeBindings,
): Arrangement.Horizontal =
    when (value.token) {
        "Start" -> Arrangement.Start
        "End" -> Arrangement.End
        "Center" -> Arrangement.Center
        "SpaceBetween" -> Arrangement.SpaceBetween
        "SpaceAround" -> Arrangement.SpaceAround
        "SpaceEvenly" -> Arrangement.SpaceEvenly
        Arrangements.SPACED_BY -> Arrangement.spacedBy(value.gap(bindings))
        else -> unknownLayoutToken("horizontal arrangement", value.token, Arrangement.Start)
    }

/**
 * The gap a `spacedBy` carries.
 *
 * The parser refuses a `spacedBy` that arrives without one, so this is reached
 * only when the wire said so; zero is the value that changes the layout least if
 * that ever stops holding.
 */
private fun LayoutArrangement.gap(bindings: DootahNativeBindings): Dp =
    spacing?.resolve(bindings) ?: 0.dp

/**
 * A length, as the number to draw with.
 *
 * A number the bundle carried is used as it stands. A name is looked up in the
 * table this screen registered, which is what keeps the app's own spacing scale
 * in the app: the bundle said which value, and the APK says what it is today.
 *
 * A name with no row cannot normally arrive -- it is a missing requirement, and
 * a bundle carrying one is refused when it is published and again when it is
 * loaded. Reaching here means the two sides disagree anyway, so it is reported
 * and drawn at zero rather than throwing: one collapsed gap on a screen that
 * otherwise works beats no screen at all.
 */
private fun Dimension.resolve(bindings: DootahNativeBindings): Dp {

    value?.let { return it.toFloat().dp }

    val name = anchor ?: return 0.dp

    return bindings.anchors[name] ?: run {
        Log.w(DOOTAH_LOG_TAG, "no value named '$name' on this screen")
        0.dp
    }
}

private fun <T> unknownLayoutToken(kind: String, token: String, fallback: T): T {
    Log.w(DOOTAH_LOG_TAG, "unknown $kind '$token'")
    return fallback
}

private fun shapeOf(token: String): Shape? = when (token) {
    Shapes.CIRCLE -> CircleShape
    Shapes.RECTANGLE -> RectangleShape
    else -> {
        Log.w(DOOTAH_LOG_TAG, "unknown shape '$token'")
        null
    }
}

/**
 * Reads a colour out of the installed app's own Material theme.
 *
 * An exhaustive list rather than a lookup by name: a bundle names a token from a
 * closed vocabulary, and what that token means is decided entirely here, by the
 * app's own theme.
 */
@Composable
private fun themeColor(token: String): Color {

    val scheme = MaterialTheme.colorScheme

    return when (token) {
        "primary" -> scheme.primary
        "onPrimary" -> scheme.onPrimary
        "primaryContainer" -> scheme.primaryContainer
        "onPrimaryContainer" -> scheme.onPrimaryContainer
        "inversePrimary" -> scheme.inversePrimary
        "secondary" -> scheme.secondary
        "onSecondary" -> scheme.onSecondary
        "secondaryContainer" -> scheme.secondaryContainer
        "onSecondaryContainer" -> scheme.onSecondaryContainer
        "tertiary" -> scheme.tertiary
        "onTertiary" -> scheme.onTertiary
        "tertiaryContainer" -> scheme.tertiaryContainer
        "onTertiaryContainer" -> scheme.onTertiaryContainer
        "background" -> scheme.background
        "onBackground" -> scheme.onBackground
        "surface" -> scheme.surface
        "onSurface" -> scheme.onSurface
        "surfaceVariant" -> scheme.surfaceVariant
        "onSurfaceVariant" -> scheme.onSurfaceVariant
        "surfaceTint" -> scheme.surfaceTint
        "inverseSurface" -> scheme.inverseSurface
        "inverseOnSurface" -> scheme.inverseOnSurface
        "error" -> scheme.error
        "onError" -> scheme.onError
        "errorContainer" -> scheme.errorContainer
        "onErrorContainer" -> scheme.onErrorContainer
        "outline" -> scheme.outline
        "outlineVariant" -> scheme.outlineVariant
        "scrim" -> scheme.scrim
        "surfaceBright" -> scheme.surfaceBright
        "surfaceDim" -> scheme.surfaceDim
        "surfaceContainer" -> scheme.surfaceContainer
        "surfaceContainerHigh" -> scheme.surfaceContainerHigh
        "surfaceContainerHighest" -> scheme.surfaceContainerHighest
        "surfaceContainerLow" -> scheme.surfaceContainerLow
        "surfaceContainerLowest" -> scheme.surfaceContainerLowest
        else -> {
            Log.w(DOOTAH_LOG_TAG, "unknown theme colour '$token'")
            Color.Unspecified
        }
    }
}
