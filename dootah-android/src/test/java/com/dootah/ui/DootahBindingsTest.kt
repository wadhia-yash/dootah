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

        val slots = dootahSlots("$name,Icon(name)#0", "", {}, {})

        assertEquals(emptyList<String>(), slots.missingFrom(listOf(name, "Icon(name)#0")))
    }

    @Test
    fun `a slot the app does not have is reported`() {

        val slots = dootahSlots("Icon(name)#0", "", {})

        assertEquals(listOf("Icon(tint)#0"), slots.missingFrom(listOf("Icon(tint)#0")))
    }

    @Test
    fun `a screen with no slots has none`() {

        assertTrue(dootahSlots("", "").ids().isEmpty())
    }

    /**
     * The failure this check exists for.
     *
     * Three identical icon buttons are numbered #0, #1, #2. Delete the middle
     * one and publish: the bundle's #1 is what used to be #2, but the installed
     * app still has all three and hands back its own #1. Nothing is missing, so
     * nothing is reported, and the screen draws the wrong icon.
     */
    @Test
    fun `a component the source renumbered is reported`() {

        val slots = dootahSlots(
            "IconButton(content|onClick)#0,IconButton(content|onClick)#1," +
                "IconButton(content|onClick)#2",
            "IconButton(content|onClick)=3",
            {}, {}, {},
        )

        val drawn = listOf("IconButton(content|onClick)#1")

        assertEquals(
            listOf("IconButton(content|onClick)"),
            slots.disagreeingShapes(drawn, mapOf("IconButton(content|onClick)" to 2)),
        )

        assertEquals(
            emptyList<String>(),
            slots.disagreeingShapes(drawn, mapOf("IconButton(content|onClick)" to 3)),
        )
    }

    /**
     * Adding a Text is the commonest edit there is, and it must not be mistaken
     * for a renumbering.
     *
     * The bundle's table counts every component in its source, including the
     * ones it draws itself rather than asking the app for. Only the shapes it
     * actually asks the app for are numbered against this build.
     */
    @Test
    fun `an edit to a component the bundle draws itself is not a renumbering`() {

        val slots = dootahSlots(
            "Icon(name)#0",
            "Icon(name)=1,Text(text)=1",
            {},
        )

        assertEquals(
            emptyList<String>(),
            slots.disagreeingShapes(
                listOf("Icon(name)#0"),
                mapOf("Icon(name)" to 1, "Text(text)" to 2),
            ),
        )
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
