package com.dootah.bridge

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.json.JSONObject

/**
 * The allowlisted native capabilities a bundle can reach.
 *
 * Every capability is a named method here, implemented against a `Context` the
 * bundle never sees. There is no reflection and no dynamic dispatch: a command
 * this class does not implement reaches nothing, which is what keeps remote
 * content inside a boundary Play policy can be reasoned about.
 *
 * Reachable two ways. [handle] serves the JavaScript message-port path, which
 * requires a WebView feature no device tested so far reports. The typed methods
 * serve the command model, where a bundle returns what it wants done and the app
 * performs it -- the path that works everywhere.
 */
class NativeBridge(
    private val context: Context
) {

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun log(message: String) {
        Log.d(BUNDLE_LOG_TAG, message)
    }

    /** Entry point for the message-port bridge, which delivers JSON text. */
    fun handle(message: String) {

        val command = JSONObject(message)

        when (command.getString("type")) {
            "toast" -> toast(command.getString("message"))
            "log" -> log(command.getString("message"))
            else -> Log.w(BRIDGE_LOG_TAG, "Unknown command: $message")
        }
    }

    private companion object {
        const val BUNDLE_LOG_TAG = "DootahBundle"
        const val BRIDGE_LOG_TAG = "DootahBridge"
    }
}
