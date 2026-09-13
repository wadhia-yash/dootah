package com.dootah.ui

import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val callbacks = dootahCallbacks("onSave", { saved = true })

        assertTrue(callbacks.invoke("onSave"))
        assertTrue(saved)

        // A bundle naming something the screen does not declare reaches nothing.
        assertFalse(callbacks.invoke("onDelete"))
    }

    @Test
    fun `a modifier stays native rather than being serialised`() {

        val arguments = dootahModifiedArguments(Modifier, "price", 999)

        assertEquals("""{"price":999}""", arguments.toJson())
    }
}
