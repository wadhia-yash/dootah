package dev.dootah.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The include/exclude rules, which reach two separate compiler invocations as
 * one string. Both parse it with this class, so what matters is that it means
 * the same thing after a round trip.
 */
class ScreenFilterTest {

    @Test
    fun `configuring nothing means every composable in the module`() {
        assertTrue(ScreenFilter.EVERYTHING.accepts("com.example.Toolbox"))
    }

    /**
     * Writing a package name and having it match nothing would be a trap, so a
     * pattern with no wildcard covers the package and everything under it.
     */
    @Test
    fun `a bare package name covers what is under it`() {

        val filter = ScreenFilter.of(include = emptyList(), exclude = listOf("com.example.debug"))

        assertFalse(filter.accepts("com.example.debug.Panel"))
        assertFalse(filter.accepts("com.example.debug.inner.Panel"))
        assertTrue(filter.accepts("com.example.Toolbox"))

        // Not a prefix match on the text: a different package that happens to
        // start with the same letters is a different package.
        assertTrue(filter.accepts("com.example.debugging.Panel"))
    }

    @Test
    fun `one star stops at a package boundary and two cross it`() {

        assertTrue(ScreenFilter.matches("com.example.*", "com.example.Toolbox"))
        assertFalse(ScreenFilter.matches("com.example.*", "com.example.ui.Toolbox"))
        assertTrue(ScreenFilter.matches("com.example.**", "com.example.ui.Toolbox"))
    }

    /**
     * An instruction to leave something native should never have to out-argue a
     * wildcard someone wrote first.
     */
    @Test
    fun `an exclusion wins over an inclusion`() {

        val filter = ScreenFilter.of(
            include = listOf("com.example.**"),
            exclude = listOf("com.example.payments.**"),
        )

        assertTrue(filter.accepts("com.example.Toolbox"))
        assertFalse(filter.accepts("com.example.payments.Sheet"))
        assertFalse(filter.accepts("com.other.Toolbox"))
    }

    /**
     * The app's build and the extraction pass are given the same encoded string,
     * and a screen the two disagree about is a screen that silently never
     * updates.
     */
    @Test
    fun `it means the same thing after a round trip`() {

        val original = ScreenFilter.of(
            include = listOf("com.example.**", "com.other.ui.*"),
            exclude = listOf("com.example.payments.**"),
        )

        val restored = ScreenFilter.parse(original.encode())

        listOf(
            "com.example.Toolbox",
            "com.example.payments.Sheet",
            "com.other.ui.Bar",
            "com.other.ui.deep.Bar",
            "com.third.Thing",
        ).forEach { name ->
            assertEquals(name, original.accepts(name), restored.accepts(name))
        }
    }
}
