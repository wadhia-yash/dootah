package ui

import json.escapeJson

fun BundleNode.toJson(): String {

    return when (this) {

        is ColumnNode -> {
            """
            {
                "type":"column",
                "children":[
                    ${children.joinToString(",") { it.toJson() }}
                ]
            }
            """.trimIndent()
        }

        is TextNode -> {
            """
            {
                "type":"text",
                "text":"${text.escapeJson()}"
            }
            """.trimIndent()
        }

        is ButtonNode -> {
            """
            {
                "type":"button",
                "text":"${text.escapeJson()}",
                "action":"${action.escapeJson()}"
            }
            """.trimIndent()
        }
    }
}
