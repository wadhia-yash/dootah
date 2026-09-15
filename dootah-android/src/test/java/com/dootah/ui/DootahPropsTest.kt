package com.dootah.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a generated adapter reads out of what the bundle sent.
 *
 * Every accessor here answers for a prop that is absent or of the wrong type,
 * and none of them throws -- the two sides are two compilations of two versions
 * of a source, so a disagreement is a thing that happens. That tolerance is
 * also what makes a mistake here silent: a prop read through the wrong type
 * check is not an error, it is a component drawn without it.
 *
 * Which is what happened to every modifier a bundle ever sent.
 */
@OptIn(DootahGeneratedApi::class)
class DootahPropsTest {

    private fun props(values: Map<String, Any?>) =
        DootahProps(
            values = values,
            childContent = emptyMap(),
            entryContent = emptyMap(),
            invoker = { _, _ -> },
        )

    /**
     * `Modifier` as a value is `Modifier.Companion`, which is a narrower type
     * than the one the accessor returns. Left to inference, the check asked
     * whether the supplied modifier was the companion object -- so every size,
     * padding and background a bundle sent was dropped and the component drew
     * at its natural size, with only a log line to say so.
     */
    @Test
    fun `carries a modifier the bundle sent`() {

        val sized = Modifier.size(48.dp)

        assertSame(sized, props(mapOf("modifier" to sized)).modifier("modifier"))
    }

    @Test
    fun `falls back to no modifier when the bundle sent none`() {

        assertEquals(Modifier, props(emptyMap()).modifier("modifier"))
    }

    @Test
    fun `falls back when a prop arrives as something else`() {

        val wrong = props(mapOf("modifier" to "not a modifier"))

        assertEquals(Modifier, wrong.modifier("modifier"))
        assertEquals(Color.Unspecified, wrong.color("modifier"))
        assertEquals("", props(mapOf("text" to 7)).string("text"))
    }

    @Test
    fun `carries the scalars the bundle sent`() {

        val values = props(
            mapOf(
                "text" to "brush",
                "enabled" to true,
                "count" to 3,
                "tint" to Color.Red,
                "width" to 48.dp,
            )
        )

        assertEquals("brush", values.string("text"))
        assertEquals(true, values.boolean("enabled"))
        assertEquals(3, values.int("count"))
        assertEquals(Color.Red, values.color("tint"))
        assertEquals(48.dp, values.dp("width"))
    }
}

/**
 * What a screen's state does when its inputs change under it.
 *
 * The state is remembered across recompositions and the inputs are not, so
 * holding the ones it was built with made every re-render send the values the
 * screen had when it first appeared. A remote toolbox went on showing a tool as
 * selected after the app had switched to the eraser, because the condition
 * deciding that is evaluated remotely from a value that never changed -- and a
 * handle went on pointing at the object the caller held on first composition.
 */
@OptIn(DootahGeneratedApi::class)
class DootahScreenStateTest {

    @Test
    fun `renders with the arguments of the latest composition`() {

        val state = DootahScreenState(
            screenId = "screen",
            arguments = dootahArguments("erasing", false),
            callbacks = dootahCallbacks("", ""),
            bindings = dootahBindings(
                dootahAdapters(), dootahCapabilities(), dootahHandles(), dootahResources(),
                dootahAnchors(), dootahBuilders(),
            ),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(state.arguments.toJson().contains("false"))

        state.update(
            arguments = dootahArguments("erasing", true),
            callbacks = dootahCallbacks("", ""),
            bindings = dootahBindings(
                dootahAdapters(), dootahCapabilities(), dootahHandles(), dootahResources(),
                dootahAnchors(), dootahBuilders(),
            ),
        )

        assertTrue(
            "the screen would re-render with the value it opened with",
            state.arguments.toJson().contains("true"),
        )
    }
}
