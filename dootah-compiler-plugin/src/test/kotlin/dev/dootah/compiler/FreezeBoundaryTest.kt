package dev.dootah.compiler

import dev.dootah.contract.FrozenRegionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Where a failure stops.
 *
 * Dootah does not need to understand everything in a screen, and it never
 * will -- the point of the whole design is that what it does not understand
 * keeps running as the native code it already is. What matters is how far the
 * not-understanding spreads. A screen is worth publishing when an update could
 * change what someone sees, so a rule that gave up the screen for one
 * unsupported corner gave up the update as well, and on a real application
 * every screen has a corner.
 *
 * These pin the property rather than the vocabulary. None of them names a
 * Compose API Dootah knows: the components are the fixture's own, invented for
 * the test, and nothing in the compiler has heard of them. What is asserted is
 * only ever *where the boundary landed*, which is the thing that has to be true
 * of an application Dootah has never seen.
 */
class FreezeBoundaryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * The shared preamble: a value Dootah cannot carry and a component it has
     * never heard of that takes one.
     *
     * `Gauge(reading = sensor)` is unsupported for the most ordinary reason
     * there is -- it is handed a domain object. The app has the component and
     * the object; a bundle can have neither.
     */
    private val preamble = """
        package com.example

        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.Row
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.getValue
        import androidx.compose.runtime.mutableStateOf
        import androidx.compose.runtime.remember
        import androidx.compose.runtime.setValue

        class Sensor

        @Composable
        fun Gauge(reading: Sensor) {}

        @Composable
        fun Chip(onClick: () -> Unit) {}

        @Composable
        fun Stepper(onStep: () -> Unit) {}

        @Composable
        fun Picker(onConfirm: (Long, Long) -> Unit) {}
    """.trimIndent()

    @Test
    fun `a supported sibling before an unsupported region stays remote`() {

        val generated = lower(
            """
            @Composable
            fun Screen() {
                val sensor = Sensor()
                Column {
                    Text("before")
                    Gauge(reading = sensor)
                }
            }
            """
        )

        assertTrue(generated, generated.contains("""TextNode("before")"""))
        assertEquals(generated, 1, generated.frozenRegions())
    }

    @Test
    fun `a supported sibling after an unsupported region stays remote`() {

        val generated = lower(
            """
            @Composable
            fun Screen() {
                val sensor = Sensor()
                Column {
                    Gauge(reading = sensor)
                    Text("after")
                }
            }
            """
        )

        assertTrue(generated, generated.contains("""TextNode("after")"""))
        assertEquals(generated, 1, generated.frozenRegions())
    }

    /**
     * The boundary is the statement, not the layout holding it.
     *
     * The `Row` around the unsupported call is still described, so an update
     * can reorder what is in it, drop the text beside it, or move the whole row
     * -- none of which it could do if the failure had been allowed to travel
     * one level out.
     */
    @Test
    fun `an unsupported call inside a layout keeps the layout remote`() {

        val generated = lower(
            """
            @Composable
            fun Screen() {
                val sensor = Sensor()
                Column {
                    Text("top")
                    Row {
                        Text("inner")
                        Gauge(reading = sensor)
                    }
                }
            }
            """
        )

        assertTrue(generated, generated.contains("RowNode("))
        assertTrue(generated, generated.contains("""TextNode("top")"""))
        assertTrue(generated, generated.contains("""TextNode("inner")"""))
        assertEquals(generated, 1, generated.frozenRegions())
    }

    /**
     * A component the app already has, driven by the bundle.
     *
     * Nothing in Dootah knows what a `Chip` is. It is placed anyway, and the
     * handler it is given is one of the screen's own parameters, lifted into
     * the APK and named by what it does. This is the case the product is for:
     * the capability is already installed, so a source change that uses it
     * ships over the air.
     */
    @Test
    fun `a component the compiler has never heard of is placed with its handler`() {

        val generated = lower(
            """
            @Composable
            fun Screen(onPick: () -> Unit) {
                Column {
                    Text("pick one")
                    Chip(onClick = onPick)
                }
            }
            """
        )

        assertTrue(generated, generated.contains("com.example.Chip(onClick)"))
        assertTrue(generated, generated.contains("CallbackProp("))
        assertEquals(generated, 0, generated.frozenRegions())
    }

    /**
     * A value two sides both want is given to the app, and takes its readers
     * with it.
     *
     * The stepper's handler writes `count`, and the handler cannot be
     * described, so it stays native and writes the app's own `count`. A bundle
     * holding a second `count` would be holding one nothing updates -- so it
     * holds none, and the text that reads it goes native too. That is a larger
     * boundary than the offending statement, and it is the right one: it is
     * exactly the data dependency and no more.
     *
     * The `Column` is still the bundle's, which is the part that makes this a
     * degradation rather than a refusal.
     */
    @Test
    fun `a value a native handler writes is left to the app, along with its readers`() {

        val generated = lower(
            """
            @Composable
            fun Screen() {
                var count by remember { mutableStateOf(0) }
                Column {
                    Text("Count: " + count)
                    Stepper(onStep = { count = count + 1 })
                }
            }
            """
        )

        assertTrue(generated, generated.contains("ColumnNode("))
        assertFalse(generated, generated.contains("state.init"))
        assertFalse(generated, generated.contains("\"Count: \""))
        assertEquals(generated, 2, generated.frozenRegions())
    }

    /**
     * The converse, and the one that stops the rule above from being a licence
     * to give up.
     *
     * Nothing native reads `greeting`, so the unsupported gauge beside it takes
     * nothing with it: the bundle keeps the state, keeps the text that shows
     * it, and can change either over the air.
     */
    @Test
    fun `an unrelated unsupported region does not take the bundle's state with it`() {

        val generated = lower(
            """
            @Composable
            fun Screen() {
                var greeting by remember { mutableStateOf("hi") }
                val sensor = Sensor()
                Column {
                    Text(greeting)
                    Gauge(reading = sensor)
                }
            }
            """
        )

        assertTrue(generated, generated.contains("""state.initString("greeting", "hi")"""))
        assertTrue(generated, generated.contains("""TextNode(state.string("greeting"))"""))
        assertEquals(generated, 1, generated.frozenRegions())
    }

    /**
     * An `if` whose condition the app owns is a region like any other.
     *
     * `showDialog` comes from a `remember` Dootah cannot read the initial value
     * of, so the condition is the app's. Before, a conditional had no last rung
     * on the ladder -- it could not be described, could not be placed, and
     * could not be kept -- so the failure travelled to the root and the screen
     * went native whole. Now it is kept, and the text beside it is not.
     */
    @Test
    fun `a conditional the app owns is kept, and its siblings stay remote`() {

        val generated = lower(
            """
            @Composable
            fun Screen() {
                val sensor = Sensor()
                val showGauge = remember { Sensor() != null }
                Column {
                    Text("always")
                    if (showGauge) {
                        Gauge(reading = sensor)
                    }
                }
            }
            """
        )

        assertTrue(generated, generated.contains("""TextNode("always")"""))
        assertTrue(generated, generated.contains(FrozenRegionId.CONDITIONAL))
        assertEquals(generated, 1, generated.frozenRegions())
    }

    /**
     * Two unsupported regions do not add up to a refusal.
     *
     * The count matters as much as the placement: a rule that degraded the
     * first failure and refused the second would still lose every real screen,
     * because real screens have several.
     */
    @Test
    fun `several unsupported regions each stop at themselves`() {

        val generated = lower(
            """
            @Composable
            fun Screen() {
                val sensor = Sensor()
                Column {
                    Gauge(reading = sensor)
                    Text("between")
                    Row {
                        Gauge(reading = sensor)
                        Text("inner")
                    }
                }
            }
            """
        )

        assertTrue(generated, generated.contains("""TextNode("between")"""))
        assertTrue(generated, generated.contains("""TextNode("inner")"""))
        assertTrue(generated, generated.contains("RowNode("))

        // One region, not two: both calls are the same code and are named by
        // what they say, so the app registers one adapter and the bundle
        // places it twice.
        assertEquals(generated, 2, generated.frozenPlacements())
    }

    // ---- helpers --------------------------------------------------------

    private fun lower(screen: String): String {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            mode = "extract",
            sources = listOf(SourceFile("Screen.kt", preamble + "\n\n" + screen.trimIndent())),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        return requireNotNull(result.generatedSources().entries.firstOrNull {
            it.key.startsWith("DootahScreen_")
        }) {
            "the screen was refused rather than degraded:\n${result.rejectionReport()}"
        }.value
    }

    /** How many distinct regions the bundle asks the app to draw as written. */
    private fun String.frozenRegions(): Int =
        REGION.findAll(this).map { it.value }.toSet().size

    /** How many places the bundle draws one, which may be more. */
    private fun String.frozenPlacements(): Int = REGION.findAll(this).count()

    private companion object {
        val REGION = Regex("\"" + Regex.escape(FrozenRegionId.PREFIX) + "[^\"]+\"")
    }
}
