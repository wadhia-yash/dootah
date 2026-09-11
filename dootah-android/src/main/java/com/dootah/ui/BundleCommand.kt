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
     */
    data class InvokeCallback(val name: String) : BundleCommand

    data class Log(val message: String) : BundleCommand

    data class Toast(val message: String) : BundleCommand
}
