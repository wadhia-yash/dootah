package com.dootah.ui

import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The values and callbacks a screen carries to its remote implementation. */
@OptIn(DootahGeneratedApi::class)
class DootahBindingsTest {

    @Test
    fun `arguments are written as the JSON a bundle reads`() {

        val arguments = dootahArguments("name,price,premium", "Desk Lamp", 2499, true)

        val json = arguments.toJson()

        assertTrue(json, json.contains("\"name\":\"Desk Lamp\""))
        assertTrue(json, json.contains("\"price\":2499"))
        assertTrue(json, json.contains("\"premium\":true"))
    }

    @Test
    fun `a screen with no arguments sends nothing`() {

        assertEquals("", dootahArguments("").toJson())
    }

    @Test
    fun `only the callbacks a screen declares can be invoked`() {

        var saved = false
        val callbacks = dootahCallbacks("onSave", "", { saved = true })

        assertTrue(callbacks.invoke("onSave", emptyList()))
        assertTrue(saved)

        // A bundle naming something the screen does not declare reaches nothing.
        assertFalse(callbacks.invoke("onDelete", emptyList()))
    }

    /**
     * A callback taking a value, which is what milestone 1 needed.
     *
     * The value arrives as a JSON scalar and becomes the type the screen's own
     * signature declared, so the app's `(String) -> Unit` is called with a
     * `String` and nothing had to be guessed from the value itself.
     */
    @Test
    fun `a callback is called with the values the bundle sent`() {

        var opened: String? = null
        val callbacks = dootahCallbacks(
            "navigateToPost",
            "String",
            { id: String -> opened = id },
        )

        assertTrue(
            callbacks.invoke("navigateToPost", listOf(CallbackArgument.Text("2")))
        )
        assertEquals("2", opened)
    }

    /** Every carried type, read back as the parameter declares it. */
    @Test
    fun `values become the types the screen declared`() {

        val seen = mutableListOf<Any?>()
        val callbacks = dootahCallbacks(
            "onInt,onLong,onFloat,onDouble,onBool",
            "Int,Long,Float,Double,Boolean",
            { value: Int -> seen += value },
            { value: Long -> seen += value },
            { value: Float -> seen += value },
            { value: Double -> seen += value },
            { value: Boolean -> seen += value },
        )

        assertTrue(callbacks.invoke("onInt", listOf(CallbackArgument.Number(7.0))))
        // A Long travels as text, because a bundle's numbers are doubles and an
        // id past 2^53 sent as one arrives with its low digits gone.
        assertTrue(
            callbacks.invoke("onLong", listOf(CallbackArgument.Text("9007199254740993")))
        )
        assertTrue(callbacks.invoke("onFloat", listOf(CallbackArgument.Number(1.5))))
        assertTrue(callbacks.invoke("onDouble", listOf(CallbackArgument.Number(2.5))))
        assertTrue(callbacks.invoke("onBool", listOf(CallbackArgument.Bool(true))))

        assertEquals(listOf<Any?>(7, 9007199254740993L, 1.5f, 2.5, true), seen)
    }

    /**
     * A value that does not fit drops the call rather than coercing it.
     *
     * Calling anyway with a zero or an empty string would act on a number the
     * developer never wrote, and casting a `Double` into a `(String) -> Unit`
     * would be a `ClassCastException` inside someone's `onClick` -- the crash
     * this boundary exists to prevent.
     */
    @Test
    fun `a value of the wrong shape is refused rather than coerced`() {

        var opened: String? = null
        val callbacks = dootahCallbacks(
            "navigateToPost",
            "String",
            { id: String -> opened = id },
        )

        assertFalse(
            callbacks.invoke("navigateToPost", listOf(CallbackArgument.Number(2.0)))
        )
        assertFalse(callbacks.invoke("navigateToPost", emptyList()))
        assertNull(opened)
    }

    @Test
    fun `a modifier stays native rather than being serialised`() {

        val arguments = dootahModifiedArguments(Modifier, "price", 999)

        assertEquals("""{"price":999}""", arguments.toJson())
    }
}
