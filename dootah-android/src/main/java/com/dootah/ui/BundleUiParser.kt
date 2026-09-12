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

/** What one call into a bundle produced. */
data class BundleResponse(
    val ui: BundleUiNode,
    val commands: List<BundleCommand>,

    /**
     * How many components of each shape the bundle's source contained.
     *
     * Checked against this build's own counts before anything is drawn. A
     * component's name ends in its position among those sharing its shape, so a
     * source that dropped one renumbers the rest.
     */
    val componentShapes: Map<String, Int> = emptyMap(),
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
            componentShapes = root["shapes"]?.jsonObject
                ?.mapValues { (_, count) -> count.jsonPrimitive.int }
                .orEmpty(),
        )
    }

    /** Reads the screen ids a bundle reports, in the order it reported them. */
    fun parseScreenIds(payload: String): List<String> =
        (json.parseToJsonElement(payload) as JsonArray).map { it.jsonPrimitive.content }

    private fun parseNode(node: JsonObject): BundleUiNode {

        val modifiers = node["modifiers"]?.jsonArray?.map { parseModifier(it.jsonObject) }.orEmpty()

        return when (val type = node.string("type")) {

            "column" -> BundleUiNode.Column(modifiers, node.children())
            "row" -> BundleUiNode.Row(modifiers, node.children())
            "box" -> BundleUiNode.Box(modifiers, node.children())

            "text" -> BundleUiNode.Text(text = node.string("text"), modifiers = modifiers)

            "button" -> BundleUiNode.Button(
                text = node.string("text"),
                action = node.string("action"),
                modifiers = modifiers,
            )

            "fragment" -> BundleUiNode.Fragment(children = node.children())

            "native" -> BundleUiNode.NativeSlot(slot = node.string("slot"))

            else -> throw BundleProtocolException("Unknown UI node type '$type'")
        }
    }

    private fun JsonObject.children(): List<BundleUiNode> =
        this["children"]?.jsonArray?.map { parseNode(it.jsonObject) }.orEmpty()

    private fun parseModifier(modifier: JsonObject): BundleUiModifier =
        when (val type = modifier.string("type")) {

            "inherited" -> BundleUiModifier.Inherited

            "padding" -> BundleUiModifier.Padding(
                start = modifier.float("start"),
                top = modifier.float("top"),
                end = modifier.float("end"),
                bottom = modifier.float("bottom"),
            )

            "fillMaxWidth" -> BundleUiModifier.FillMaxWidth(modifier.float("fraction"))
            "fillMaxHeight" -> BundleUiModifier.FillMaxHeight(modifier.float("fraction"))
            "fillMaxSize" -> BundleUiModifier.FillMaxSize(modifier.float("fraction"))

            "size" -> BundleUiModifier.Size(
                width = modifier.float("width"),
                height = modifier.float("height"),
            )

            "width" -> BundleUiModifier.Width(modifier.float("value"))
            "height" -> BundleUiModifier.Height(modifier.float("value"))
            "weight" -> BundleUiModifier.Weight(modifier.float("value"))

            "background" -> BundleUiModifier.Background(modifier.long("color"))

            else -> throw BundleProtocolException("Unknown modifier '$type'")
        }

    private fun parseCommand(command: JsonObject): BundleCommand =
        when (val type = command.string("type")) {

            "invokeCallback" -> BundleCommand.InvokeCallback(command.string("name"))
            "log" -> BundleCommand.Log(command.string("message"))
            "toast" -> BundleCommand.Toast(command.string("message"))

            else -> throw BundleProtocolException("Unknown command '$type'")
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
}
