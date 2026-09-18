package com.dootah.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import dev.dootah.contract.BuilderEntries
import dev.dootah.contract.Alignments
import dev.dootah.contract.AnchorId
import dev.dootah.contract.Arrangements
import dev.dootah.contract.Dimension
import dev.dootah.contract.LayoutArrangement
import dev.dootah.contract.ModifierOp
import dev.dootah.contract.PropValue

/** What one call into a bundle produced. */
data class BundleResponse(
    val ui: BundleUiNode,
    val commands: List<BundleCommand>,
)

/** A bundle answered, but not with a screen. */
class BundleProtocolException(message: String) : Exception(message)

/**
 * Reads what a bundle returned.
 *
 * Strict everywhere. An unknown node type, an unknown modifier, an unknown
 * command or a missing field fails the whole response rather than being skipped,
 * and the app falls back to native. The alternative -- drawing the parts it
 * understood -- would render a screen the bundle did not describe, which is the
 * one failure mode worse than showing the native implementation.
 */
object BundleUiParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): BundleResponse {

        val root = json.parseToJsonElement(payload).jsonObject

        root["error"]?.jsonPrimitive?.content?.let { error ->
            throw BundleProtocolException(
                when (error) {
                    "unknownScreen" ->
                        "The bundle has no implementation for screen " +
                            "'${root["screenId"]?.jsonPrimitive?.content}'"
                    else -> root["message"]?.jsonPrimitive?.content ?: error
                }
            )
        }

        val ui = root["ui"]?.jsonObject
            ?: throw BundleProtocolException("Bundle response has no 'ui'")

        return BundleResponse(
            ui = parseNode(ui),
            commands = root["commands"]?.jsonArray?.map { parseCommand(it.jsonObject) }.orEmpty(),
        )
    }

    /** Reads the screen ids a bundle reports, in the order it reported them. */
    fun parseScreenIds(payload: String): List<String> =
        (json.parseToJsonElement(payload) as JsonArray).map { it.jsonPrimitive.content }

    private fun parseNode(node: JsonObject): BundleUiNode {

        val modifiers = node["modifiers"]?.jsonArray?.map { parseModifier(it.jsonObject) }.orEmpty()

        return when (val type = node.string("type")) {

            "column" -> BundleUiNode.Column(
                modifiers = modifiers,
                children = node.children(),
                horizontalAlignment = node.alignment(
                    "horizontalAlignment", Alignments::isKnownHorizontal,
                ),
                verticalArrangement = node.arrangement(
                    "verticalArrangement", Arrangements::isKnownVertical,
                ),
            )

            "row" -> BundleUiNode.Row(
                modifiers = modifiers,
                children = node.children(),
                verticalAlignment = node.alignment(
                    "verticalAlignment", Alignments::isKnownVertical,
                ),
                horizontalArrangement = node.arrangement(
                    "horizontalArrangement", Arrangements::isKnownHorizontal,
                ),
            )

            "box" -> BundleUiNode.Box(
                modifiers = modifiers,
                children = node.children(),
                contentAlignment = node.alignment("contentAlignment", Alignments::isKnownBox),
            )

            "text" -> BundleUiNode.Text(text = node.string("text"), modifiers = modifiers)

            "button" -> BundleUiNode.Button(
                text = node.string("text"),
                action = node.string("action"),
                modifiers = modifiers,
            )

            "fragment" -> BundleUiNode.Fragment(children = node.children())

            "component" -> BundleUiNode.Component(
                adapterId = node.string("adapter"),
                props = node["props"]?.jsonObject
                    ?.mapValues { (_, value) -> parseProp(value.jsonObject) }
                    .orEmpty(),
                children = node["slots"]?.jsonObject
                    ?.mapValues { (_, nodes) -> nodes.jsonArray.map { parseNode(it.jsonObject) } }
                    .orEmpty(),
                entries = node["builders"]?.jsonObject
                    ?.mapValues { (_, list) -> list.jsonArray.map { parseEntry(it.jsonObject) } }
                    .orEmpty(),
            )

            else -> throw BundleProtocolException("Unknown UI node type '$type'")
        }
    }

    /**
     * Reads one declaration in a builder slot.
     *
     * Strict like the rest: an entry kind this build does not know is a bundle
     * describing a list this app cannot build, and the response is refused
     * rather than the list being drawn with an entry quietly missing.
     */
    private fun parseEntry(entry: JsonObject): BundleUiEntry =
        when (val kind = entry.string("kind")) {

            BuilderEntries.ITEM -> BundleUiEntry.Item(
                children = entry["children"]?.jsonArray
                    ?.map { parseNode(it.jsonObject) }
                    .orEmpty(),
            )

            BuilderEntries.REGION -> BundleUiEntry.Region(adapterId = entry.string("adapter"))

            else -> throw BundleProtocolException("Unknown list entry '$kind'")
        }

    /**
     * Reads one argument a bundle supplied to a native component.
     *
     * Strict, like everything else here: a kind this build does not know is a
     * bundle describing something this app cannot draw, and the whole response
     * is refused rather than the component being placed with an argument
     * silently missing.
     */
    private fun parseProp(prop: JsonObject): PropValue =
        when (val kind = prop.string("k")) {

            PropValue.Kind.NULL -> PropValue.NullValue
            PropValue.Kind.BOOL -> PropValue.BoolValue(prop.boolean("v"))
            PropValue.Kind.INT -> PropValue.IntValue(prop.int("v"))
            // Sent as text: a bundle's numbers are doubles, and a Long past 2^53
            // loses digits on the way out without anything failing.
            PropValue.Kind.LONG -> PropValue.LongValue(prop.string("v").toLong())
            PropValue.Kind.FLOAT -> PropValue.FloatValue(prop.float("v"))
            PropValue.Kind.DOUBLE -> PropValue.DoubleValue(prop.double("v"))
            PropValue.Kind.STRING -> PropValue.StringValue(prop.string("v"))

            PropValue.Kind.DP -> PropValue.DpValue(prop.double("v"))

            // Checked for shape here, so a name the app could never resolve is
            // refused with the rest of the response rather than drawn as zero.
            PropValue.Kind.ANCHOR -> PropValue.AnchorValue(
                prop.string("v").also { anchor ->
                    if (!AnchorId.isWellFormed(anchor)) {
                        throw BundleProtocolException("'$anchor' is not a value name")
                    }
                }
            )
            PropValue.Kind.COLOR -> PropValue.ColorValue(prop.long("v"))
            PropValue.Kind.THEME_COLOR -> PropValue.ThemeColorValue(prop.string("token"))
            PropValue.Kind.SHAPE -> PropValue.ShapeValue(prop.string("token"))

            PropValue.Kind.PAINTER_RESOURCE -> PropValue.PainterResourceValue(prop.string("key").also {
                if (dev.dootah.contract.BundleImages.isImage(it)) dev.dootah.contract.BundleImages.hash(it)
            })
            PropValue.Kind.STRING_RESOURCE -> PropValue.StringResourceValue(prop.string("key"))

            PropValue.Kind.MODIFIER -> PropValue.ModifierValue(
                prop["ops"]?.jsonArray?.map { parseModifierOp(it.jsonObject) }.orEmpty()
            )

            PropValue.Kind.LIST -> PropValue.ListValue(
                prop["items"]?.jsonArray?.map { parseProp(it.jsonObject) }.orEmpty()
            )

            PropValue.Kind.HANDLE -> PropValue.HandleValue(prop.string("name"))
            PropValue.Kind.STATE -> PropValue.StateValue(prop.string("name"))

            PropValue.Kind.CALLBACK -> PropValue.CallbackValue(
                capability = prop.string("id"),
                arity = prop["arity"]?.jsonPrimitive?.int ?: 0,
            )

            else -> throw BundleProtocolException("Unknown prop kind '$kind'")
        }

    private fun parseModifierOp(operation: JsonObject): ModifierOp = ModifierOp(
        name = operation.string("op"),
        arguments = operation["args"]?.jsonObject
            ?.mapValues { (_, value) -> parseProp(value.jsonObject) }
            .orEmpty(),
    )

    private fun JsonObject.children(): List<BundleUiNode> =
        this["children"]?.jsonArray?.map { parseNode(it.jsonObject) }.orEmpty()

    /**
     * Reads one alignment name, checked against the set for this axis.
     *
     * Checked here rather than at the renderer so that an unknown name fails the
     * whole response, like every other unknown the parser meets. The renderer's
     * `when` would have to do something with a name it did not recognise, and
     * every available something -- a default, the nearest match, nothing at all
     * -- draws a layout the bundle did not describe.
     *
     * The axis matters as much as the name: `CenterVertically` is a real
     * `Alignment` and still nonsense on a `Column`, so each call passes the set
     * its own parameter accepts.
     */
    private fun JsonObject.alignment(field: String, isKnown: (String) -> Boolean): String? {

        val token = (this[field] as? JsonPrimitive)?.content ?: return null

        if (!isKnown(token)) {
            throw BundleProtocolException("Unknown $field '$token'")
        }

        return token
    }

    /** Reads one arrangement: a name, and for `spacedBy` the gap it must carry. */
    private fun JsonObject.arrangement(
        field: String,
        isKnown: (String) -> Boolean,
    ): LayoutArrangement? {

        val value = this[field]?.jsonObject ?: return null
        val token = value.string("token")

        if (!isKnown(token)) {
            throw BundleProtocolException("Unknown $field '$token'")
        }

        // A spacedBy without its gap is a malformed bundle. Defaulting it to
        // zero would draw a layout nobody wrote, which is worse than refusing.
        if (token == Arrangements.SPACED_BY) {
            return LayoutArrangement(token, value.dimension("space"))
        }

        return LayoutArrangement(token)
    }

    private fun parseModifier(modifier: JsonObject): BundleUiModifier =
        when (val type = modifier.string("type")) {

            "inherited" -> BundleUiModifier.Inherited

            "padding" -> BundleUiModifier.Padding(
                start = modifier.dimension("start"),
                top = modifier.dimension("top"),
                end = modifier.dimension("end"),
                bottom = modifier.dimension("bottom"),
            )

            "fillMaxWidth" -> BundleUiModifier.FillMaxWidth(modifier.float("fraction"))
            "fillMaxHeight" -> BundleUiModifier.FillMaxHeight(modifier.float("fraction"))
            "fillMaxSize" -> BundleUiModifier.FillMaxSize(modifier.float("fraction"))

            "size" -> BundleUiModifier.Size(
                width = modifier.dimension("width"),
                height = modifier.dimension("height"),
            )

            "width" -> BundleUiModifier.Width(modifier.dimension("value"))
            "height" -> BundleUiModifier.Height(modifier.dimension("value"))
            "weight" -> BundleUiModifier.Weight(modifier.float("value"))

            "background" -> BundleUiModifier.Background(modifier.long("color"))

            else -> throw BundleProtocolException("Unknown modifier '$type'")
        }

    private fun parseCommand(command: JsonObject): BundleCommand =
        when (val type = command.string("type")) {

            "invokeCallback" -> BundleCommand.InvokeCallback(
                name = command.string("name"),
                arguments = command.callbackArguments(),
            )
            "log" -> BundleCommand.Log(command.string("message"))
            "toast" -> BundleCommand.Toast(command.string("message"))

            else -> throw BundleProtocolException("Unknown command '$type'")
        }

    /**
     * Reads the values sent with a callback invocation.
     *
     * Absent means none, so a bundle built before callbacks took values still
     * parses and still invokes the no-argument callbacks it was written for.
     * Anything that is not a JSON scalar fails the whole response: a bundle
     * cannot have produced one, so its presence means the two sides disagree.
     */
    private fun JsonObject.callbackArguments(): List<CallbackArgument> {

        val elements = (this["arguments"] as? JsonArray) ?: return emptyList()

        return elements.map { element ->

            val primitive = element as? JsonPrimitive
                ?: throw BundleProtocolException("A callback value is not a scalar")

            when {
                primitive.isString -> CallbackArgument.Text(primitive.content)
                primitive.content == "true" -> CallbackArgument.Bool(true)
                primitive.content == "false" -> CallbackArgument.Bool(false)
                else -> CallbackArgument.Number(
                    primitive.doubleOrNull
                        ?: throw BundleProtocolException(
                            "A callback value is not a number: ${primitive.content}"
                        )
                )
            }
        }
    }

    /**
     * Reads one length: a number, or an object naming a value the app owns.
     *
     * Told apart by JSON type rather than by a discriminator, because there are
     * only two shapes and neither can be mistaken for the other. An anchor whose
     * name is not shaped like one is refused here, so that a malformed name is
     * reported as malformed rather than reaching the renderer and resolving to
     * nothing.
     */
    private fun JsonObject.dimension(field: String): Dimension {

        val element = this[field] ?: throw BundleProtocolException("Missing '$field'")

        (element as? JsonPrimitive)?.doubleOrNull?.let { return Dimension.of(it) }

        val anchor = (element as? JsonObject)?.get("anchor")?.jsonPrimitive?.content
            ?: throw BundleProtocolException("'$field' is not a length")

        if (!AnchorId.isWellFormed(anchor)) {
            throw BundleProtocolException("Malformed anchored value '$anchor' on '$field'")
        }

        return Dimension.anchored(anchor)
    }

    private fun JsonObject.string(field: String): String =
        (this[field] as? JsonPrimitive)?.content
            ?: throw BundleProtocolException("Missing '$field'")

    private fun JsonObject.float(field: String): Float =
        (this[field] as? JsonPrimitive)?.floatOrNull
            ?: throw BundleProtocolException("Missing numeric '$field'")

    private fun JsonObject.long(field: String): Long =
        (this[field] as? JsonPrimitive)?.longOrNull
            ?: throw BundleProtocolException("Missing numeric '$field'")

    private fun JsonObject.double(field: String): Double =
        (this[field] as? JsonPrimitive)?.doubleOrNull
            ?: throw BundleProtocolException("Missing numeric '$field'")

    private fun JsonObject.int(field: String): Int =
        (this[field] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: throw BundleProtocolException("Missing numeric '$field'")

    private fun JsonObject.boolean(field: String): Boolean =
        (this[field] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: throw BundleProtocolException("Missing boolean '$field'")
}
