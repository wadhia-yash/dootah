package com.dootah.runtime

import com.dootah.ota.BundleExecutionException
import kotlinx.serialization.json.JsonPrimitive

/**
 * The UMD global the Dootah bundle exports.
 *
 * Derived from the :dootah-bundle Gradle project name by the Kotlin/JS webpack
 * build, so renaming that project is a breaking change to the bundle protocol.
 */
private const val BUNDLE_MODULE_NAME = "dootah-bundle"

private const val RENDER_FUNCTION = "renderScreen"
private const val ACTION_FUNCTION = "handleAction"

/**
 * Drives a loaded bundle: evaluate it, ask it to render, forward actions.
 *
 * This is the only place that knows how a bundle is shaped. Neither the OTA
 * layer nor the integrating app needs to know that bundles are JavaScript
 * modules exporting particular function names.
 */
internal class BundleEngine(
    private val runtime: JavaScriptRuntime,
) {

    /**
     * Loads [bundleSource] into a clean isolate and returns its first UI tree as
     * JSON.
     *
     * The restart matters: without it a replacement bundle would be evaluated on
     * top of the previous one's globals.
     */
    suspend fun load(bundleSource: String): String {

        runtime.restart()

        runtime.evaluate(
            """
            $bundleSource
            "loaded";
            """.trimIndent()
        )

        requireExport(RENDER_FUNCTION)
        requireExport(ACTION_FUNCTION)

        return render()
    }

    suspend fun render(): String =
        runtime.evaluate("globalThis[\"$BUNDLE_MODULE_NAME\"].$RENDER_FUNCTION();")

    /**
     * Forwards a UI action to the bundle and returns the resulting UI tree.
     *
     * The action is embedded as a JSON string literal rather than interpolated
     * raw. A quote or backslash in an action name would otherwise produce a
     * syntax error instead of a call.
     */
    suspend fun dispatch(action: String): String {

        val actionLiteral = JsonPrimitive(action).toString()

        return runtime.evaluate(
            "globalThis[\"$BUNDLE_MODULE_NAME\"].$ACTION_FUNCTION($actionLiteral);"
        )
    }

    /**
     * Fails loudly when a bundle is missing a function Dootah is about to call,
     * so a truncated or wrong artifact is reported as such rather than as an
     * opaque "undefined is not a function".
     */
    private suspend fun requireExport(functionName: String) {

        val kind = runtime.evaluate(
            "typeof (globalThis[\"$BUNDLE_MODULE_NAME\"] || {}).$functionName;"
        )

        if (kind != "function") {
            throw BundleExecutionException(
                "Bundle does not export $functionName() " +
                    "(globalThis[\"$BUNDLE_MODULE_NAME\"].$functionName is $kind)"
            )
        }
    }
}
