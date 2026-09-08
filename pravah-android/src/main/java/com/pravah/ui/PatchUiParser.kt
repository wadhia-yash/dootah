package com.pravah.ui

import kotlinx.serialization.json.*

object PatchUiParser {

    fun parse(json: String): PatchUiNode {
        val element = Json.parseToJsonElement(json)
        return parseNode(element.jsonObject)
    }

    private fun parseNode(
        obj: JsonObject
    ): PatchUiNode {

        return when (
            obj["type"]?.jsonPrimitive?.content
        ) {

            "column" -> {
                val children =
                    obj["children"]
                        ?.jsonArray
                        ?.map {
                            parseNode(it.jsonObject)
                        }
                        ?: emptyList()

                PatchUiNode.Column(children)
            }

            "text" -> {
                PatchUiNode.Text(
                    text = obj["text"]
                        ?.jsonPrimitive
                        ?.content
                        ?: ""
                )
            }

            "button" -> {
                PatchUiNode.Button(
                    text = obj["text"]
                        ?.jsonPrimitive
                        ?.content
                        ?: "",
                    action = obj["action"]
                        ?.jsonPrimitive
                        ?.content
                        ?: ""
                )
            }

            else -> {
                throw IllegalArgumentException(
                    "Unknown UI type"
                )
            }
        }
    }
}