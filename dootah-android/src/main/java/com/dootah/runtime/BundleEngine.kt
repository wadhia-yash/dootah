package com.dootah.runtime

import com.dootah.ota.BundleExecutionException
import com.dootah.ui.BundleUiParser
import kotlinx.serialization.json.JsonPrimitive

/**
 * The UMD global the Dootah bundle exports.
 *
 * Fixed by the bundle build's `-ir-output-name`, so changing it is a breaking
 * change to the bundle protocol.
 */
private const val BUNDLE_MODULE_NAME = "dootah-bundle"

private const val SCREEN_IDS_FUNCTION = "screenIds"
private const val RENDER_FUNCTION = "renderScreen"
private const val ACTION_FUNCTION = "handleAction"

/**
 * Drives a loaded bundle: evaluate it, ask which screens it has, render one,
 * forward an action to one.
 *
 * The only place that knows how a bundle is shaped. Every call names a screen,
 * which is what stops one screen's action reaching another's implementation --
 * the bundle side matches the id against exactly one screen object.
 */
internal class BundleEngine(
    private val runtime: JavaScriptRuntime,
) {

    /**
     * Loads [bundleSource] into a clean isolate and returns the screens it
     * implements.
     *
     * The restart matters: without it a replacement bundle would be evaluated on
     * top of the previous one's globals, and would inherit its remote state.
     */
    suspend fun load(bundleSource: String): List<String> {

        runtime.restart()

        runtime.evaluate(
            """
            $bundleSource
            "loaded";
            """.trimIndent()
        )

        requireExport(SCREEN_IDS_FUNCTION)
        requireExport(RENDER_FUNCTION)
        requireExport(ACTION_FUNCTION)

        return BundleUiParser.parseScreenIds(call(SCREEN_IDS_FUNCTION))
    }

    suspend fun render(screenId: String, argumentsJson: String): String =
        call(RENDER_FUNCTION, screenId, argumentsJson)

    suspend fun dispatch(screenId: String, action: String, argumentsJson: String): String =
        call(ACTION_FUNCTION, screenId, action, argumentsJson)

    /**
     * Calls an exported function with string arguments.
     *
     * Every argument is embedded as a JSON string literal rather than
     * interpolated raw. A quote or a backslash in a screen id, an action name or
     * an argument value would otherwise produce a syntax error instead of a
     * call -- and a note title is exactly the kind of value that contains one.
     */
    private suspend fun call(functionName: String, vararg arguments: String): String {

        val argumentList = arguments.joinToString(",") { JsonPrimitive(it).toString() }

        return runtime.evaluate(
            "globalThis[\"$BUNDLE_MODULE_NAME\"].$functionName($argumentList);"
        )
    }

    /**
     * Fails loudly when a bundle is missing a function Dootah is about to call,
     * so a truncated artifact, or one built for an older runtime, is reported as
     * such rather than as an opaque "undefined is not a function".
     */
    private suspend fun requireExport(functionName: String) {

        val kind = runtime.evaluate(
            "typeof (globalThis[\"$BUNDLE_MODULE_NAME\"] || {}).$functionName;"
        )

        if (kind != "function") {
            throw BundleExecutionException(
                "Bundle does not export $functionName() " +
                    "(globalThis[\"$BUNDLE_MODULE_NAME\"].$functionName is $kind). " +
                    "A bundle built for an older Dootah runtime looks like this."
            )
        }
    }
}
