package protocol

import json.escapeJson
import ui.BundleNode
import ui.toJson

/**
 * The wire format between a bundle and the installed app.
 *
 * Every call returns one envelope: the UI to draw, and the commands the app
 * should run. One shape for render and for action keeps the Android side from
 * having two parsers, and gives a render somewhere to report a failure that is
 * not an exception crossing the JavaScript boundary.
 */
fun screenIdsJson(screenIds: List<String>): String =
    "[" + screenIds.joinToString(",") { "\"${it.escapeJson()}\"" } + "]"

/**
 * One response: what to draw, and what to run.
 *
 * The app works out what this response needs from the tree itself -- which
 * adapters, actions, values and resources it names -- so there is nothing to
 * declare alongside it. An earlier version also sent a count of each component
 * shape, which existed only to detect that the two sources had numbered their
 * components differently. Components are no longer numbered.
 */
fun envelope(
    ui: BundleNode,
    commands: List<Command>,
): String =
    "{\"ui\":" + ui.toJson() +
        ",\"commands\":[" + commands.joinToString(",") { it.toJson() } + "]}"

/**
 * The answer to a request naming a screen this bundle does not implement.
 *
 * Reported rather than thrown: the app must be able to tell "this bundle has no
 * such screen", which is an ordinary reason to render the native implementation,
 * apart from "this bundle is broken", which is not.
 */
fun unknownScreen(screenId: String): String =
    "{\"error\":\"unknownScreen\",\"screenId\":\"${screenId.escapeJson()}\"}"

/**
 * Reports a failure inside a bundle as data.
 *
 * A Kotlin exception crossing into the isolate surfaces as an opaque JavaScript
 * error with no message the app can log. Catching it here keeps the reason.
 */
fun failure(message: String): String =
    "{\"error\":\"screenFailed\",\"message\":\"${message.escapeJson()}\"}"
