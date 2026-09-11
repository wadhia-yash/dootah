package com.dootah.ui

import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compiler hands this surface its names as delimited strings, so the names
 * and the delimiter have to agree.
 *
 * They did not. Slot names carried a comma, the list of them was
 * comma-separated, and the app built its slot table out of fragments: every
 * lookup missed and every native component vanished from the screen. Nothing
 * upstream noticed, because the compiler had emitted exactly the right name.
 */
@OptIn(DootahGeneratedApi::class)
class DootahBindingsTest {

    @Test
    fun `a slot name survives the trip from the compiler`() {

        val name = "ToolboxHistoryControlsContent(canRedo|canUndo|onClear)#0"

        val slots = dootahSlots("$name,Icon(name)#0", {}, {})

        assertEquals(emptyList<String>(), slots.missingFrom(listOf(name, "Icon(name)#0")))
    }

    @Test
    fun `a slot the app does not have is reported`() {

        val slots = dootahSlots("Icon(name)#0", {})

        assertEquals(listOf("Icon(tint)#0"), slots.missingFrom(listOf("Icon(tint)#0")))
    }

    @Test
    fun `a screen with no slots has none`() {

        assertTrue(dootahSlots("").ids().isEmpty())
    }

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
