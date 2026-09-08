package com.dootah.bridge

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.json.JSONObject

class NativeBridge(
    private val context: Context
) {

    fun handle(message: String) {

        val command = JSONObject(message)

        when (command.getString("type")) {

            "toast" -> {
                Toast.makeText(
                    context,
                    command.getString("message"),
                    Toast.LENGTH_SHORT
                ).show()
            }

            "log" -> {
                Log.d(
                    "DootahBundle",
                    command.getString("message")
                )
            }

            else -> {
                Log.w(
                    "DootahBridge",
                    "Unknown command: $message"
                )
            }
        }
    }
}
