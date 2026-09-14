// Dootah's own runtime, which Dootah must never take over. Discovery
// finds every eligible composable in a compilation; an app that built
// this module from source rather than resolving it as a library would
// otherwise have these intercepted, including the one that renders a
// remote screen.
@file:dev.dootah.DootahNative

package com.dootah.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import com.dootah.DOOTAH_LOG_TAG

/**
 * A native composable the installed app can be asked to place.
 *
 * An adapter is *reusable*. It is not a copy of one call written in the source;
 * it is an entry point into a composable that shipped in the APK, taking its
 * arguments as data. A bundle may place the same adapter nowhere, once, or five
 * times, in any order, and supply different arguments to each -- none of which
 * the APK has to have anticipated.
 *
 * That is the difference from naming a component by where it was written. A
 * positional name cannot survive its neighbour being deleted: the app still
 * finds something under the name it is handed, so nothing looks wrong, and the
 * screen draws the wrong component. An adapter has no position to lose.
 */
class DootahAdapter internal constructor(
    internal val id: String,

    /**
     * The arguments this adapter passes through to the composable.
     *
     * An adapter is a copy of a call the source wrote, with the arguments that
     * call supplied replaced by reads from the bundle. A parameter no call site
     * supplied has no slot in the binary at all, so a bundle naming one would be
     * ignored -- the screen would draw with the app's own default and nothing
     * would say why. Carrying the set makes that difference visible instead.
     */
    internal val parameters: Set<String>,
    internal val content: @Composable (DootahProps) -> Unit,
)

/** The adapters this build registered, by id. */
class DootahAdapters internal constructor(
    private val byId: Map<String, DootahAdapter>,
) {

    internal fun ids(): Set<String> = byId.keys

    internal fun missingFrom(requested: List<String>): List<String> =
        requested.filterNot { adapter -> adapter in byId }.distinct().sorted()

    /**
     * The arguments a bundle named that the adapter registered here cannot take.
     *
     * Only for adapters this build has: one it has not got is already reported
     * as a missing component, and saying both about the same node would name the
     * same problem twice.
     */
    internal fun unsupportedArguments(requested: List<AdapterArgument>): List<AdapterArgument> =
        requested
            .filter { argument -> byId[argument.adapter]?.parameters?.contains(argument.name) == false }
            .distinct()
            .sortedWith(compareBy({ it.adapter }, { it.name }))

    internal operator fun get(id: String): DootahAdapter? = byId[id]

    companion object {
        internal val EMPTY = DootahAdapters(emptyMap())
    }
}

/**
 * A native action the installed app can run for a bundle.
 *
 * Generated from a lambda written in the screen's own source, closing over that
 * screen's parameters. A bundle chooses which capability to attach to which
 * component; it cannot write one, and it cannot reach anything the original
 * lambda did not already touch. There is no lookup by class or method name and
 * nothing reflective -- a capability exists only because a developer wrote it.
 */
class DootahCapability internal constructor(
    internal val id: String,
    internal val action: (List<Any?>) -> Unit,
)

class DootahCapabilities internal constructor(
    private val byId: Map<String, DootahCapability>,
) {

    internal fun ids(): Set<String> = byId.keys

    internal fun missingFrom(requested: List<String>): List<String> =
        requested.filterNot { capability -> capability in byId }.distinct().sorted()

    /**
     * Runs a capability, reporting rather than throwing when it is not here.
     *
     * A bundle built against a newer version of the screen may name a capability
     * this build has not got. That must degrade to a control that does nothing,
     * not to a crash on a user's device.
     */
    internal fun invoke(id: String, arguments: List<Any?>) {

        val capability = byId[id]

        if (capability == null) {
            Log.w(DOOTAH_LOG_TAG, "bundle asked for capability '$id', which this build has not got")
            return
        }

        capability.action(arguments)
    }

    companion object {
        internal val EMPTY = DootahCapabilities(emptyMap())
    }
}

/**
 * The screen's own parameters, by name, for the ones that cannot be serialised.
 *
 * A view model, a list of domain objects, a `MutableState`: things a bundle must
 * be able to route into a native component without ever seeing them. The bundle
 * names one of these; the app takes the object out of a table it built at its
 * own call site. A name that is not in the table resolves to nothing, and no
 * bundle can name a parameter belonging to a different screen.
 */
class DootahHandles internal constructor(
    private val byName: Map<String, Any?>,
) {

    internal fun names(): Set<String> = byName.keys

    internal fun missingFrom(requested: List<String>): List<String> =
        requested.filterNot { handle -> byName.containsKey(handle) }.distinct().sorted()

    internal operator fun get(name: String): Any? = byName[name]

    companion object {
        internal val EMPTY = DootahHandles(emptyMap())
    }
}

/**
 * The resources a bundle may name, mapped to this build's own identifiers.
 *
 * `R.drawable.brush_24px` is a number the build assigns and does not keep. A
 * bundle therefore carries the name, and this is where it becomes the number
 * this APK is actually using -- which is also why a bundle can choose among the
 * app's existing resources but can never introduce one that was not shipped.
 */
class DootahResources internal constructor(
    private val byKey: Map<String, Int>,
) {

    internal fun keys(): Set<String> = byKey.keys

    internal fun missingFrom(requested: List<String>): List<String> =
        requested.filterNot { key -> byKey.containsKey(key) }.distinct().sorted()

    internal operator fun get(key: String): Int? = byKey[key]

    companion object {
        internal val EMPTY = DootahResources(emptyMap())
    }
}

/**
 * The app's own values a bundle may name on this screen.
 *
 * `MaterialTheme.padding.small` is the app's spacing scale, not a number a
 * bundle can be trusted to carry: read it at build time and ship the number and
 * the screen silently stops following the scale the day someone retunes it.
 * This is the table that keeps the number here -- the bundle names an entry, and
 * what it resolves to is whatever the screen's own source reads today.
 *
 * Built at the screen's call site on every composition, so a value that depends
 * on the theme, the configuration or the window follows all three.
 *
 * A bundle can name any entry, repeat one, and stop naming one. It cannot add an
 * entry: a name with no row here is a missing requirement, reported when the
 * bundle is published rather than found as a collapsed layout on a device.
 */
class DootahAnchors internal constructor(
    private val byName: Map<String, Dp>,
) {

    internal fun names(): Set<String> = byName.keys

    internal fun missingFrom(requested: List<String>): List<String> =
        requested.filterNot { name -> byName.containsKey(name) }.distinct().sorted()

    internal operator fun get(name: String): Dp? = byName[name]

    companion object {
        internal val EMPTY = DootahAnchors(emptyMap())
    }
}

/**
 * One adapter's arguments, already resolved to the objects Compose wants.
 *
 * The renderer does the resolving, inside the composition, because that is where
 * a theme colour, a string resource and a painter can be read. By the time an
 * adapter sees a prop it is a `Color`, a `String`, a `Painter` -- not a
 * description of one.
 *
 * Every accessor has a defined answer for a prop that is absent or of the wrong
 * type, and none of them throws. The two sides are two compilations of two
 * versions of the source, so a disagreement is a thing that happens; it is
 * reported when the bundle is published, and survived quietly if it reaches a
 * device anyway.
 */
class DootahProps internal constructor(
    private val values: Map<String, Any?>,
    private val childContent: Map<String, @Composable () -> Unit>,
    private val invoker: (String, List<Any?>) -> Unit,
) {

    fun string(name: String): String = of(name, "")

    fun boolean(name: String): Boolean = of(name, false)

    fun int(name: String): Int = of(name, 0)

    fun long(name: String): Long = of(name, 0L)

    fun float(name: String): Float = of(name, 0f)

    fun double(name: String): Double = of(name, 0.0)

    /**
     * The type argument is written out because the fallback is narrower.
     *
     * `Modifier` as a value is `Modifier.Companion`, so leaving it to inference
     * asks whether the supplied modifier is the companion -- which nothing ever
     * is. Every size, padding and background a bundle sent was quietly dropped
     * and the component drew at its natural size.
     */
    fun modifier(name: String): Modifier = of<Modifier>(name, Modifier)

    fun color(name: String): Color = of(name, Color.Unspecified)

    fun dp(name: String): Dp = of(name, Dp.Unspecified)

    /**
     * Non-null, with a fallback that draws nothing.
     *
     * A component's `painter` is usually declared non-null, so handing one a
     * null would not compile into the adapter at all. A bundle that omits it
     * gets an empty image rather than a crash, and the omission is caught when
     * the bundle is published.
     */
    fun painter(name: String): Painter = values[name] as? Painter ?: EmptyPainter

    fun shape(name: String): Shape = values[name] as? Shape ?: RectangleShape

    fun painterOrNull(name: String): Painter? = values[name] as? Painter

    fun shapeOrNull(name: String): Shape? = values[name] as? Shape

    /** A `String?` prop, where absent and explicitly null are the same answer. */
    fun stringOrNull(name: String): String? = values[name] as? String

    /**
     * A screen parameter routed straight through to a native component.
     *
     * Returned untyped; the generated adapter casts it to the type its own
     * parameter declares. What it can be is decided entirely by the table the
     * app built from its own call site.
     */
    fun handle(name: String): Any? = values[name]

    fun callback(name: String): () -> Unit {
        val capability = values[name] as? String ?: return {}
        return { invoker(capability, emptyList()) }
    }

    /** A callback the native component calls with one argument of its own. */
    fun callback1(name: String): (Any?) -> Unit {
        val capability = values[name] as? String ?: return {}
        return { argument -> invoker(capability, listOf(argument)) }
    }

    /** Draws whatever the bundle put in this component's content slot. */
    @Composable
    fun children(name: String) {
        childContent[name]?.invoke()
    }

    private inline fun <reified T> of(name: String, fallback: T): T {

        val value = values[name] ?: return fallback

        if (value !is T) {
            Log.w(
                DOOTAH_LOG_TAG,
                "prop '$name' arrived as ${value::class.java.simpleName}, " +
                    "which is not what this component takes",
            )
            return fallback
        }

        return value
    }
}

/** Draws nothing, for a painter a bundle did not supply. */
private object EmptyPainter : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified
    override fun DrawScope.onDraw() = Unit
}

@DootahGeneratedApi
fun dootahAdapter(
    id: String,
    parameters: String,
    content: @Composable (DootahProps) -> Unit,
): DootahAdapter = DootahAdapter(
    id = id,
    parameters = if (parameters.isEmpty()) emptySet() else parameters.split(",").toSet(),
    content = content,
)

@DootahGeneratedApi
fun dootahAdapters(vararg adapters: DootahAdapter): DootahAdapters =
    DootahAdapters(adapters.associateBy { adapter -> adapter.id })

@DootahGeneratedApi
fun dootahCapability(id: String, action: (List<Any?>) -> Unit): DootahCapability =
    DootahCapability(id, action)

@DootahGeneratedApi
fun dootahCapabilities(vararg capabilities: DootahCapability): DootahCapabilities =
    DootahCapabilities(capabilities.associateBy { capability -> capability.id })

@DootahGeneratedApi
fun dootahHandles(vararg handles: Pair<String, Any?>): DootahHandles =
    DootahHandles(handles.toMap())

@DootahGeneratedApi
fun dootahHandle(name: String, value: Any?): Pair<String, Any?> = name to value

@DootahGeneratedApi
fun dootahResources(vararg resources: Pair<String, Int>): DootahResources =
    DootahResources(resources.toMap())

@DootahGeneratedApi
fun dootahResource(key: String, id: Int): Pair<String, Int> = key to id

@DootahGeneratedApi
fun dootahAnchors(vararg anchors: Pair<String, Dp>): DootahAnchors =
    DootahAnchors(anchors.toMap())

@DootahGeneratedApi
fun dootahAnchor(name: String, value: Dp): Pair<String, Dp> = name to value

@DootahGeneratedApi
fun dootahBindings(
    adapters: DootahAdapters,
    capabilities: DootahCapabilities,
    handles: DootahHandles,
    resources: DootahResources,
    anchors: DootahAnchors,
): DootahNativeBindings =
    DootahNativeBindings(adapters, capabilities, handles, resources, anchors)

/**
 * Everything a screen lets a bundle reach, in one place.
 *
 * Grouped because they are one decision, not four: this is the complete set of
 * native things a remote implementation of this screen can name. A bundle can
 * use any of it and nothing beyond it, and every entry got here because the
 * screen's own source referred to it.
 */
class DootahNativeBindings internal constructor(
    internal val adapters: DootahAdapters,
    internal val capabilities: DootahCapabilities,
    internal val handles: DootahHandles,
    internal val resources: DootahResources,
    internal val anchors: DootahAnchors,
) {

    /** What this build cannot supply, out of what a bundle asked for. */
    internal fun shortfall(required: BundleRequirements): List<String> =
        adapters.missingFrom(required.adapters).map { "component $it" } +
            adapters.unsupportedArguments(required.arguments)
                .map { "argument ${it.name} on component ${it.adapter}" } +
            capabilities.missingFrom(required.capabilities).map { "action $it" } +
            handles.missingFrom(required.handles).map { "value $it" } +
            resources.missingFrom(required.resources).map { "resource $it" } +
            anchors.missingFrom(required.anchors).map { "value $it" }

    companion object {
        internal val EMPTY = DootahNativeBindings(
            adapters = DootahAdapters.EMPTY,
            capabilities = DootahCapabilities.EMPTY,
            handles = DootahHandles.EMPTY,
            resources = DootahResources.EMPTY,
            anchors = DootahAnchors.EMPTY,
        )
    }
}
