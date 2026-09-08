package com.dootah.ui

import kotlinx.serialization.json.*

object BundleUiParser {

    fun parse(json: String): BundleUiNode {
        val element = Json.parseToJsonElement(json)
        return parseNode(element.jsonObject)
    }

    private fun parseNode(
        obj: JsonObject
    ): BundleUiNode {

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

                BundleUiNode.Column(children)
            }

            "text" -> {
                BundleUiNode.Text(
                    text = obj["text"]
                        ?.jsonPrimitive
                        ?.content
                        ?: ""
                )
            }

            "button" -> {
                BundleUiNode.Button(
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
