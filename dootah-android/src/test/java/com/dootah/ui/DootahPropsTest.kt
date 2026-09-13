package com.dootah.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
class DootahPropsTest {

    private fun props(values: Map<String, Any?>) =
        DootahProps(values = values, childContent = emptyMap(), invoker = { _, _ -> })

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
