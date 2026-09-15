package com.dootah.ui

/**
 * Something a bundle asked the app to do.
 *
 * The complete native capability vocabulary. It is a closed set on this side as
 * well as on the bundle side, and the parser refuses anything else, so a bundle
 * built against a newer command set cannot have part of its intent executed and
 * the rest dropped.
 *
 * Nothing here carries code, a class name, or a method name to look up. A
 * command names a capability the app already implements, which is what keeps
 * remote content away from arbitrary Android APIs.
 */
sealed interface BundleCommand {

    /**
     * Calls one of the screen's own callback parameters by name.
     *
     * The names come from the composable's signature, fixed when the APK was
     * built. A name the screen does not declare resolves to nothing.
     *
     * [arguments] are values the bundle computed, coerced on arrival to the
     * types that parameter declares. They carry no reference into the app: a
     * bundle has no way to hold one, so the widest thing it can send is a
     * number, a boolean or a string.
     */
    data class InvokeCallback(
        val name: String,
        val arguments: List<CallbackArgument> = emptyList(),
    ) : BundleCommand

    data class Log(val message: String) : BundleCommand

    data class Toast(val message: String) : BundleCommand
}

/**
 * One value a bundle sent with a callback invocation.
 *
 * Three shapes, because JSON has three. Which Kotlin type each becomes is
 * decided by the parameter the app itself declared, the mirror image of how a
 * screen's arguments are decided on the way in -- so a `Long` arrives as text
 * and a `Float` as a number, and neither has to be guessed from its value.
 */
sealed interface CallbackArgument {

    data class Text(val value: String) : CallbackArgument

    data class Number(val value: Double) : CallbackArgument

    data class Bool(val value: Boolean) : CallbackArgument
}
