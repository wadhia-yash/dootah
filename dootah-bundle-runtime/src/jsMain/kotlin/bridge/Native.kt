package bridge

import json.escapeJson

@JsName("__dootahNativeCall")
private external fun nativeCall(
    message: String
)

object Native {

    fun toast(message: String) {

        nativeCall(
            """
            {
                "type":"toast",
                "message":"${message.escapeJson()}"
            }
            """.trimIndent()
        )
    }

    fun log(message: String) {

        nativeCall(
            """
            {
                "type":"log",
                "message":"${message.escapeJson()}"
            }
            """.trimIndent()
        )
    }
}
