package com.pravah.runtime

import com.pravah.ota.PatchExecutionException
import kotlinx.serialization.json.JsonPrimitive

/**
 * The UMD global the patch bundle exports.
 *
 * Derived from the :patch-bundle Gradle project name by the Kotlin/JS webpack
 * build, so renaming that project is a breaking change to the patch protocol.
 */
private const val PATCH_MODULE_NAME = "patch-bundle"

private const val RENDER_FUNCTION = "renderScreen"
private const val ACTION_FUNCTION = "handleAction"

/**
 * Drives a loaded patch: evaluate the bundle, ask it to render, forward actions.
 *
 * This is the only place that knows how a patch bundle is shaped. Neither the
 * OTA layer nor the integrating app needs to know that patches are JavaScript
 * modules exporting particular function names.
 */
internal class PatchEngine(
    private val runtime: JavaScriptRuntime,
) {

    /**
     * Loads [patchSource] into a clean isolate and returns its first UI tree as
     * JSON.
     *
     * The restart matters: without it a replacement patch would be evaluated on
     * top of the previous one's globals.
     */
    suspend fun load(patchSource: String): String {

        runtime.restart()

        runtime.evaluate(
            """
            $patchSource
            "loaded";
            """.trimIndent()
        )

        requireExport(RENDER_FUNCTION)
        requireExport(ACTION_FUNCTION)

        return render()
    }

    suspend fun render(): String =
        runtime.evaluate("globalThis[\"$PATCH_MODULE_NAME\"].$RENDER_FUNCTION();")

    /**
     * Forwards a UI action to the patch and returns the resulting UI tree.
     *
     * The action is embedded as a JSON string literal rather than interpolated
     * raw. A quote or backslash in an action name would otherwise produce a
     * syntax error instead of a call.
     */
    suspend fun dispatch(action: String): String {

        val actionLiteral = JsonPrimitive(action).toString()

        return runtime.evaluate(
            "globalThis[\"$PATCH_MODULE_NAME\"].$ACTION_FUNCTION($actionLiteral);"
        )
    }

    /**
     * Fails loudly when a bundle is missing a function Pravah is about to call,
     * so a truncated or wrong artifact is reported as such rather than as an
     * opaque "undefined is not a function".
     */
    private suspend fun requireExport(functionName: String) {

        val kind = runtime.evaluate(
            "typeof (globalThis[\"$PATCH_MODULE_NAME\"] || {}).$functionName;"
        )

        if (kind != "function") {
            throw PatchExecutionException(
                "Patch bundle does not export $functionName() " +
                    "(globalThis[\"$PATCH_MODULE_NAME\"].$functionName is $kind)"
            )
        }
    }
}
