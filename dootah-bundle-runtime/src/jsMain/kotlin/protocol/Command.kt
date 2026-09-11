package protocol

import json.escapeJson

/**
 * Something a bundle asks the app to do.
 *
 * The whole native capability surface, and an allowlist by construction: a
 * bundle cannot express a command that is not one of these, and the Android side
 * refuses any command type it does not recognise. There is no reflection, no
 * name-to-method lookup, and no route from remote code to a `Context`.
 *
 * Commands are returned from an action rather than pushed during one. The app
 * receives them, validates them, and runs them itself, which is what lets this
 * work without the message-port support no tested WebView provides.
 */
sealed interface Command {

    /**
     * Invokes one of the screen's own callback parameters.
     *
     * The name is a parameter of the composable the developer wrote, so the set
     * of names is fixed by the APK. An unknown name reaches nothing.
     */
    data class InvokeCallback(val name: String) : Command

    data class Log(val message: String) : Command

    data class Toast(val message: String) : Command
}

internal fun Command.toJson(): String = when (this) {

    is Command.InvokeCallback ->
        "{\"type\":\"invokeCallback\",\"name\":\"${name.escapeJson()}\"}"

    is Command.Log ->
        "{\"type\":\"log\",\"message\":\"${message.escapeJson()}\"}"

    is Command.Toast ->
        "{\"type\":\"toast\",\"message\":\"${message.escapeJson()}\"}"
}
