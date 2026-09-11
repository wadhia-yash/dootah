package com.dootah.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.util.Log
import com.dootah.DOOTAH_LOG_TAG
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The screen values Dootah carries to a remote implementation.
 *
 * Only the types Dootah can serialise appear here; anything else is refused when
 * the screen is lowered, so this never has to guess. The `Modifier` a caller
 * passed is held separately rather than serialised, because a Compose modifier
 * is a native object with no wire form -- the bundle refers to it by position
 * and the instance stays on this side.
 */
class DootahArguments internal constructor(
    private val names: List<String>,
    private val values: List<Any?>,
    internal val modifier: Modifier,
) {

    /**
     * The arguments as the JSON a bundle reads.
     *
     * Encoded by the same library that parses what comes back, so escaping is
     * not this class's problem: a note title with a quote in it is exactly the
     * input a hand-rolled encoder gets wrong.
     *
     * A value of a type Dootah cannot carry is sent as null rather than
     * stringified. Lowering already refuses those, so reaching this branch means
     * the app and the compiler disagree -- and the bundle, which requires every
     * declared argument, reports it and falls back instead of rendering a screen
     * built from a coerced value.
     */
    fun toJson(): String {

        if (names.isEmpty()) return ""

        return buildJsonObject {
            names.forEachIndexed { index, name ->
                put(name, encode(name, values.getOrNull(index)))
            }
        }.toString()
    }

    private fun encode(name: String, value: Any?): JsonElement = when (value) {

        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)

        else -> {
            Log.e(
                DOOTAH_LOG_TAG,
                "screen argument '$name' is a ${value::class.java.simpleName}, " +
                    "which Dootah cannot carry",
            )
            JsonNull
        }
    }

    companion object {
        internal val EMPTY = DootahArguments(emptyList(), emptyList(), Modifier)
    }
}

/**
 * The screen's callback parameters, by name.
 *
 * A fixed map built at the call site from the composable's own parameters, so
 * the set of reachable callbacks is decided by the APK. A command naming
 * something else finds nothing; there is no lookup by class or method name.
 */
class DootahCallbacks internal constructor(
    private val byName: Map<String, () -> Unit>,
) {

    internal fun names(): Set<String> = byName.keys

    /** False when the screen declares no such callback. */
    internal fun invoke(name: String): Boolean {
        val callback = byName[name] ?: return false
        callback()
        return true
    }

    companion object {
        internal val EMPTY = DootahCallbacks(emptyMap())
    }
}

/**
 * The native composables a remote implementation may place, by slot.
 *
 * Each slot is a composable the compiler lifted out of the screen's own body, so
 * what it draws is code that shipped in the APK. A bundle chooses where a slot
 * goes and nothing else: it cannot pass arguments to one, name one that does not
 * exist, or reach anything a slot closes over.
 */
class DootahSlots internal constructor(
    private val byId: Map<String, @Composable () -> Unit>,
) {

    internal fun ids(): Set<String> = byId.keys

    internal fun missingFrom(requested: List<String>): List<String> =
        requested.filterNot { slot -> slot in byId }

    /**
     * Draws a slot, or nothing when the bundle names one this build has not got.
     *
     * Silence rather than a crash: a bundle built against a newer version of the
     * screen may refer to a slot this APK never had, and that must degrade to a
     * missing component rather than taking the app down.
     */
    @Composable
    internal fun Render(slot: String) {
        byId[slot]?.invoke()
    }

    companion object {
        internal val EMPTY = DootahSlots(emptyMap())
    }
}

/**
 * Builds the arguments for a screen with no `Modifier` parameter.
 *
 * [names] is a comma-separated list, and [values] are in the same order. One
 * string plus one vararg rather than alternating pairs, because the names are a
 * compile-time constant and pairing them positionally cannot go half wrong.
 */
@DootahGeneratedApi
fun dootahArguments(names: String, vararg values: Any?): DootahArguments =
    DootahArguments(splitNames(names), values.toList(), Modifier)

/** As [dootahArguments], for a screen that also takes a `Modifier`. */
@DootahGeneratedApi
fun dootahModifiedArguments(
    modifier: Modifier,
    names: String,
    vararg values: Any?,
): DootahArguments = DootahArguments(splitNames(names), values.toList(), modifier)

@DootahGeneratedApi
fun dootahCallbacks(names: String, vararg callbacks: () -> Unit): DootahCallbacks =
    DootahCallbacks(splitNames(names).zip(callbacks.toList()).toMap())

@DootahGeneratedApi
fun dootahSlots(ids: String, vararg slots: @Composable () -> Unit): DootahSlots =
    DootahSlots(splitNames(ids).zip(slots.toList()).toMap())

private fun splitNames(names: String): List<String> =
    if (names.isEmpty()) emptyList() else names.split(",")
