package dev.dootah.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name two compiler passes have to arrive at independently.
 *
 * One reads the source the APK was built from and the other reads the source a
 * bundle is published from, and neither can see what the other computed. A
 * disagreement here has no symptom until a device draws nothing where a region
 * should be, so the rules are pinned rather than assumed.
 */
class FrozenRegionIdTest {

    @Test
    fun `a qualified call and a bare one name the same region`() {

        val qualified = """
            com.moriafly.salt.ui.TextButton(
                onClick = { save() },
                text = "Confirm",
            )
        """.trimIndent()

        val bare = """
            TextButton(
                onClick = { save() },
                text = "Confirm",
            )
        """.trimIndent()

        val name = "com.moriafly.salt.ui.TextButton"

        // The frontend's span covers the qualifier and the backend's does not.
        // Both are the same call, and the qualified name is already carried
        // beside the digest, so the two have to agree.
        assertEquals(FrozenRegionId.of(name, qualified), FrozenRegionId.of(name, bare))
    }

    /**
     * Indentation is not part of what a region does.
     *
     * The two passes read the same file, so this is not about them disagreeing.
     * It is about the same region nested one level deeper after an edit
     * elsewhere in the screen -- which happens constantly, and which must not
     * silently turn every region below it into one the app has not got.
     */
    @Test
    fun `indenting a region does not rename it`() {

        val name = "androidx.compose.material3.Icon"

        val region = """
            Icon(
                imageVector = Menu,
                contentDescription = "Menu"
            )
        """.trimIndent()

        val indented = region.lines().joinToString("\n") { line -> "        $line" }

        assertEquals(FrozenRegionId.of(name, region), FrozenRegionId.of(name, indented))
    }

    @Test
    fun `editing what a region does renames it`() {

        val name = "androidx.compose.material3.Icon"

        assertNotEquals(
            FrozenRegionId.of(name, """Icon(imageVector = Menu, contentDescription = "Menu")"""),
            FrozenRegionId.of(name, """Icon(imageVector = Back, contentDescription = "Menu")"""),
        )
    }

    @Test
    fun `a trailing lambda with no argument list is still named by what it holds`() {

        val name = "androidx.compose.foundation.layout.Column"

        assertEquals(
            FrozenRegionId.of(name, """Column { Text("a") }"""),
            FrozenRegionId.of(name, """androidx.compose.foundation.layout.Column { Text("a") }"""),
        )

        assertNotEquals(
            FrozenRegionId.of(name, """Column { Text("a") }"""),
            FrozenRegionId.of(name, """Column { Text("b") }"""),
        )
    }

    @Test
    fun `a conditional is named under its own reserved name`() {

        val id = FrozenRegionId.of(
            FrozenRegionId.CONDITIONAL,
            "if (showDialog) { Warning() }",
        )

        assertTrue(id, FrozenRegionId.isFrozen(id))

        // Unspellable as a Kotlin name, so it can never be mistaken for a
        // region named after a real function.
        assertTrue(id, id.contains("<conditional>"))

        assertNotEquals(
            id,
            FrozenRegionId.of(FrozenRegionId.CONDITIONAL, "if (showDialog) { Other() }"),
        )
    }

    @Test
    fun `a region id is distinguishable from a reusable one`() {

        val frozen = FrozenRegionId.of("com.example.Card", "Card { }")

        assertTrue(FrozenRegionId.isFrozen(frozen))
        assertTrue(!FrozenRegionId.isFrozen("com.example.Card(content|modifier)"))
    }
}
