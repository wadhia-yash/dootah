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
     *
     * [arguments] are values this bundle computed, one per parameter the app's
     * own source declared. They are data, never a reference: a bundle has no way
     * to name something in the app's memory, so the widest thing it can send is
     * a number, a boolean or a string.
     */
    data class InvokeCallback(
        val name: String,
        val arguments: List<CallbackArgument> = emptyList(),
    ) : Command

    data class Log(val message: String) : Command

    data class Toast(val message: String) : Command
}

/**
 * One value sent with a callback invocation.
 *
 * Three shapes rather than six, because JSON has three. Which Kotlin type the
 * app turns each back into is decided by the parameter the app itself declared,
 * exactly as screen arguments are decided on the way in. A `Long` travels as
 * text for the same reason it does there: every number here is a JavaScript
 * double, and past 2^53 an identifier arrives with its low digits gone.
 */
sealed interface CallbackArgument {

    data class Text(val value: String) : CallbackArgument

    data class Number(val value: Double) : CallbackArgument

    data class Bool(val value: Boolean) : CallbackArgument
}

private fun CallbackArgument.toJson(): String = when (this) {
    is CallbackArgument.Text -> "\"${value.escapeJson()}\""
    is CallbackArgument.Number -> value.toString()
    is CallbackArgument.Bool -> value.toString()
}

internal fun Command.toJson(): String = when (this) {

    is Command.InvokeCallback ->
        "{\"type\":\"invokeCallback\",\"name\":\"${name.escapeJson()}\"" +
            ",\"arguments\":[" + arguments.joinToString(",") { it.toJson() } + "]}"

    is Command.Log ->
        "{\"type\":\"log\",\"message\":\"${message.escapeJson()}\"}"

    is Command.Toast ->
        "{\"type\":\"toast\",\"message\":\"${message.escapeJson()}\"}"
}
