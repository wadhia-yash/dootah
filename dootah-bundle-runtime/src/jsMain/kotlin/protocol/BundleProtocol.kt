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
 * One response: what to draw, what to run, and how many components of each
 * shape this screen has.
 *
 * The shape table is what the app checks the component numbering against. A
 * component's name ends in its position among those sharing its shape, so a
 * source that dropped one renumbers the rest -- and an app still registering all
 * of them would draw the wrong component under a name it recognises. Sent with
 * every response because the app has no other way to know what the bundle's
 * source looked like.
 */
fun envelope(
    ui: BundleNode,
    commands: List<Command>,
    componentShapes: String = "{}",
): String =
    "{\"ui\":" + ui.toJson() +
        ",\"commands\":[" + commands.joinToString(",") { it.toJson() } + "]" +
        ",\"shapes\":" + componentShapes + "}"

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
