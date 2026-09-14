package com.dootah.ui

import dev.dootah.contract.Dimension
import dev.dootah.contract.LayoutArrangement
import dev.dootah.contract.PropValue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the boundary between remote text and a real user's screen.
 *
 * Everything it accepts gets drawn, so these pin both halves of the contract:
 * what a well-formed bundle produces, and that anything it does not recognise
 * fails the whole response instead of being drawn in part.
 */
class BundleUiParserTest {

    @Test
    fun `reads a nested tree with modifiers in order`() {

        val response = BundleUiParser.parse(
            """
            {"ui":{"type":"column",
              "modifiers":[
                {"type":"inherited"},
                {"type":"padding","start":1.0,"top":2.0,"end":3.0,"bottom":4.0}
              ],
              "children":[
                {"type":"row","children":[
                  {"type":"text","text":"hello","modifiers":[{"type":"weight","value":1.0}]}
                ]},
                {"type":"button","text":"Add","action":"add"}
              ]},
             "commands":[]}
            """.trimIndent()
        )

        val column = response.ui as BundleUiNode.Column

        assertEquals(
            listOf(
                BundleUiModifier.Inherited,
                BundleUiModifier.Padding(
                    start = Dimension.of(1.0), top = Dimension.of(2.0),
                    end = Dimension.of(3.0), bottom = Dimension.of(4.0),
                ),
            ),
            column.modifiers,
        )

        val row = column.children[0] as BundleUiNode.Row
        val text = row.children.single() as BundleUiNode.Text

        assertEquals("hello", text.text)
        assertEquals(listOf(BundleUiModifier.Weight(1f)), text.modifiers)

        assertEquals(
            BundleUiNode.Button("Add", "add", emptyList()),
            column.children[1],
        )
    }

    /**
     * A fragment holds children like a layout but draws none of its own, and the
     * check for components this build does not have has to see through it.
     */
    @Test
    fun `reads a fragment and finds the components inside it`() {

        val response = BundleUiParser.parse(
            """
            {"ui":{"type":"fragment","children":[
               {"type":"box","children":[{"type":"component","adapter":"Icon(name)"}]},
               {"type":"component","adapter":"Icon(name)"},
               {"type":"text","text":"tail"}
             ]},
             "commands":[]}
            """.trimIndent()
        )

        val fragment = response.ui as BundleUiNode.Fragment

        assertEquals(3, fragment.children.size)
        assertEquals(
            listOf("Icon(name)", "Icon(name)"),
            response.ui.requirements().adapters,
        )
    }

    @Test
    fun `reads a component, its arguments and its content`() {

        val response = BundleUiParser.parse(
            """{"ui":{"type":"component","adapter":"m3.IconButton(content|onClick)",
                 "props":{"onClick":{"k":"callback","id":"menu.value=true","arity":0}},
                 "slots":{"content":[{"type":"component","adapter":"m3.Icon(painter)",
                   "props":{"painter":{"k":"painterRes","key":"drawable:brush"}}}]}},
               "commands":[]}"""
        )

        assertEquals(
            BundleUiNode.Component(
                adapterId = "m3.IconButton(content|onClick)",
                props = mapOf(
                    "onClick" to PropValue.CallbackValue("menu.value=true", arity = 0),
                ),
                children = mapOf(
                    "content" to listOf(
                        BundleUiNode.Component(
                            adapterId = "m3.Icon(painter)",
                            props = mapOf(
                                "painter" to PropValue.PainterResourceValue("drawable:brush"),
                            ),
                        )
                    )
                ),
            ),
            response.ui,
        )
    }

    /**
     * A list, as the entries the app is asked to declare.
     *
     * The scope is conspicuously absent, and has to be: what arrives is which
     * entries there are, and the app makes the scope and performs them.
     */
    @Test
    fun `reads a list's entries and what the app must build for them`() {

        val response = BundleUiParser.parse(
            """{"ui":{"type":"component","adapter":"lazy.LazyColumn(content|modifier)",
                 "builders":{"content":[
                   {"kind":"item","children":[{"type":"text","text":"Latest"}]},
                   {"kind":"region","adapter":"!com.example.articleItems@abc"}]}},
               "commands":[]}"""
        )

        assertEquals(
            BundleUiNode.Component(
                adapterId = "lazy.LazyColumn(content|modifier)",
                entries = mapOf(
                    "content" to listOf(
                        BundleUiEntry.Item(
                            children = listOf(BundleUiNode.Text(text = "Latest", modifiers = emptyList())),
                        ),
                        BundleUiEntry.Region(adapterId = "!com.example.articleItems@abc"),
                    )
                ),
            ),
            response.ui,
        )

        // The walk that decides whether this build can draw the screen has to
        // reach inside a builder slot. While an entry held only a name this was
        // invisible; a region the app has not got is a list with a hole in it.
        assertEquals(
            listOf("!com.example.articleItems@abc"),
            response.ui.requirements().builders,
        )
    }

    @Test
    fun `refuses an entry kind it cannot build`() {

        val failure = assertThrows(BundleProtocolException::class.java) {
            BundleUiParser.parse(
                """{"ui":{"type":"component","adapter":"lazy.LazyColumn(content)",
                     "builders":{"content":[{"kind":"itemsIndexed","children":[]}]}},
                   "commands":[]}"""
            )
        }

        assertTrue(failure.message.orEmpty(), "itemsIndexed" in failure.message.orEmpty())
    }

    /**
     * A Long is carried as text.
     *
     * A bundle's numbers are JavaScript numbers, which are doubles: past 2^53 a
     * Long silently loses its low digits, and an identifier that arrives almost
     * right is worse than one that does not arrive.
     */
    @Test
    fun `reads a long without losing precision`() {

        val response = BundleUiParser.parse(
            """{"ui":{"type":"component","adapter":"a.B(id)",
                 "props":{"id":{"k":"long","v":"9007199254740993"}}},"commands":[]}"""
        )

        assertEquals(
            PropValue.LongValue(9007199254740993L),
            (response.ui as BundleUiNode.Component).props["id"],
        )
    }

    @Test
    fun `refuses an argument kind it does not know`() {

        val failure = runCatching {
            BundleUiParser.parse(
                """{"ui":{"type":"component","adapter":"a.B(x)",
                     "props":{"x":{"k":"jvmObject","v":"..."}}},"commands":[]}"""
            )
        }.exceptionOrNull()

        assertEquals(
            "Unknown prop kind 'jvmObject'",
            (failure as BundleProtocolException).message,
        )
    }

    @Test
    fun `reads the allowlisted commands`() {

        val response = BundleUiParser.parse(
            """
            {"ui":{"type":"text","text":"x"},
             "commands":[
               {"type":"invokeCallback","name":"onSave"},
               {"type":"log","message":"saved"},
               {"type":"toast","message":"done"}
             ]}
            """.trimIndent()
        )

        assertEquals(
            listOf(
                BundleCommand.InvokeCallback("onSave"),
                BundleCommand.Log("saved"),
                BundleCommand.Toast("done"),
            ),
            response.commands,
        )
    }

    @Test
    fun `refuses a command it does not implement`() {

        // Rejecting the whole response is the point. Executing the commands it
        // understood and dropping the rest would run half of what a bundle
        // asked for, which is worse than falling back to native.
        val failure = assertThrows(BundleProtocolException::class.java) {
            BundleUiParser.parse(
                """{"ui":{"type":"text","text":"x"},
                    "commands":[{"type":"startActivity","intent":"..."}]}"""
            )
        }

        assertTrue(failure.message!!.contains("startActivity"))
    }

    @Test
    fun `refuses a node type it cannot draw`() {

        assertThrows(BundleProtocolException::class.java) {
            BundleUiParser.parse("""{"ui":{"type":"webview","url":"..."},"commands":[]}""")
        }
    }

    @Test
    fun `refuses a modifier it cannot apply`() {

        assertThrows(BundleProtocolException::class.java) {
            BundleUiParser.parse(
                """{"ui":{"type":"text","text":"x",
                    "modifiers":[{"type":"clickable"}]},"commands":[]}"""
            )
        }
    }

    @Test
    fun `reports a screen the bundle does not implement`() {

        val failure = assertThrows(BundleProtocolException::class.java) {
            BundleUiParser.parse(
                """{"error":"unknownScreen","screenId":"com.example.Missing"}"""
            )
        }

        assertTrue(failure.message!!.contains("com.example.Missing"))
    }

    @Test
    fun `reads the screen list`() {

        assertEquals(
            listOf("com.example.A", "com.example.B"),
            BundleUiParser.parseScreenIds("""["com.example.A","com.example.B"]"""),
        )
    }
    // ---- alignment and arrangement ---------------------------------------

    @Test
    fun `reads alignment and arrangement on each layout`() {

        val response = BundleUiParser.parse(
            """
            {"ui":{"type":"column",
              "horizontalAlignment":"End",
              "verticalArrangement":{"token":"SpaceBetween"},
              "children":[
                {"type":"row",
                 "verticalAlignment":"CenterVertically",
                 "horizontalArrangement":{"token":"spacedBy","space":8.0},
                 "children":[]},
                {"type":"box","contentAlignment":"Center","children":[]}
              ]},
             "commands":[]}
            """.trimIndent()
        )

        val column = response.ui as BundleUiNode.Column

        assertEquals("End", column.horizontalAlignment)
        assertEquals(LayoutArrangement("SpaceBetween"), column.verticalArrangement)

        val row = column.children[0] as BundleUiNode.Row

        assertEquals("CenterVertically", row.verticalAlignment)
        assertEquals(LayoutArrangement("spacedBy", Dimension.of(8.0)), row.horizontalArrangement)

        assertEquals("Center", (column.children[1] as BundleUiNode.Box).contentAlignment)
    }

    /**
     * Saying nothing is not the same as saying the default.
     *
     * The renderer leaves Compose's own default in place for a null, so a bundle
     * that never mentioned alignment must arrive with nulls rather than with
     * whatever the compiling machine's Compose considered default.
     */
    @Test
    fun `leaves alignment unset when the bundle did not send it`() {

        val response = BundleUiParser.parse(
            """{"ui":{"type":"row","children":[]},"commands":[]}"""
        )

        val row = response.ui as BundleUiNode.Row

        assertEquals(null, row.verticalAlignment)
        assertEquals(null, row.horizontalArrangement)
    }

    @Test
    fun `refuses an alignment outside the vocabulary`() {

        val failure = assertThrows(BundleProtocolException::class.java) {
            BundleUiParser.parse(
                """{"ui":{"type":"row","verticalAlignment":"Sideways","children":[]},"commands":[]}"""
            )
        }

        assertTrue(failure.message, failure.message!!.contains("Sideways"))
    }

    /**
     * A real `Alignment`, on the wrong axis, is still refused.
     *
     * `CenterHorizontally` exists and is meaningless as a `Row`'s vertical
     * alignment. Checking the name against the set for its own axis is what
     * stops the renderer being handed a token it has no value for.
     */
    @Test
    fun `refuses an alignment from the wrong axis`() {

        val failure = assertThrows(BundleProtocolException::class.java) {
            BundleUiParser.parse(
                """
                {"ui":{"type":"row","verticalAlignment":"CenterHorizontally","children":[]},
                 "commands":[]}
                """.trimIndent()
            )
        }

        assertTrue(failure.message, failure.message!!.contains("CenterHorizontally"))
    }

    @Test
    fun `refuses an arrangement from the wrong axis`() {

        val failure = assertThrows(BundleProtocolException::class.java) {
            BundleUiParser.parse(
                """
                {"ui":{"type":"column","verticalArrangement":{"token":"Start"},"children":[]},
                 "commands":[]}
                """.trimIndent()
            )
        }

        assertTrue(failure.message, failure.message!!.contains("Start"))
    }

    /**
     * A `spacedBy` with no gap is malformed, and refusing is the safe answer.
     *
     * Defaulting it to zero would draw a layout nobody described, which is the
     * one outcome worse than falling back to the native implementation.
     */
    @Test
    fun `refuses a spacedBy that arrives without its gap`() {

        assertThrows(BundleProtocolException::class.java) {
            BundleUiParser.parse(
                """
                {"ui":{"type":"row","horizontalArrangement":{"token":"spacedBy"},"children":[]},
                 "commands":[]}
                """.trimIndent()
            )
        }
    }

    // ---- lengths the app owns --------------------------------------------

    @Test
    fun `reads a length the bundle named rather than carried`() {

        val response = BundleUiParser.parse(
            """
            {"ui":{"type":"row",
              "horizontalArrangement":{"token":"spacedBy",
                "space":{"anchor":"androidx.compose.material3.MaterialTheme.padding.small"}},
              "modifiers":[{"type":"padding","start":1.0,"top":2.0,
                "end":{"anchor":"com.example.Spacing.gutter"},"bottom":4.0}],
              "children":[]},
             "commands":[]}
            """.trimIndent()
        )

        val row = response.ui as BundleUiNode.Row

        assertEquals(
            LayoutArrangement(
                "spacedBy",
                Dimension.anchored("androidx.compose.material3.MaterialTheme.padding.small"),
            ),
            row.horizontalArrangement,
        )

        assertEquals(
            BundleUiModifier.Padding(
                start = Dimension.of(1.0),
                top = Dimension.of(2.0),
                end = Dimension.anchored("com.example.Spacing.gutter"),
                bottom = Dimension.of(4.0),
            ),
            row.modifiers.single(),
        )
    }

    /**
     * A named length is something the app has to have, so it is collected.
     *
     * Before a length could be named there was nothing in a layout's own
     * modifiers to require, and the walk skipped them. A name that went
     * uncollected here would reach a device without ever being checked.
     */
    @Test
    fun `a named length is reported as something the app must supply`() {

        val response = BundleUiParser.parse(
            """
            {"ui":{"type":"column",
              "verticalArrangement":{"token":"spacedBy",
                "space":{"anchor":"com.example.Spacing.small"}},
              "children":[
                {"type":"text","text":"x",
                 "modifiers":[{"type":"width","value":{"anchor":"com.example.Spacing.wide"}}]}
              ]},
             "commands":[]}
            """.trimIndent()
        )

        assertEquals(
            listOf("com.example.Spacing.small", "com.example.Spacing.wide"),
            response.ui.requirements().anchors.sorted(),
        )
    }

    @Test
    fun `refuses a malformed name for a length`() {

        val failure = assertThrows(BundleProtocolException::class.java) {
            BundleUiParser.parse(
                """
                {"ui":{"type":"text","text":"x",
                  "modifiers":[{"type":"width","value":{"anchor":"nodots"}}]},
                 "commands":[]}
                """.trimIndent()
            )
        }

        assertTrue(failure.message, failure.message!!.contains("nodots"))
    }

    @Test
    fun `refuses a length that is neither a number nor a name`() {

        assertThrows(BundleProtocolException::class.java) {
            BundleUiParser.parse(
                """
                {"ui":{"type":"text","text":"x",
                  "modifiers":[{"type":"width","value":{"nonsense":1}}]},
                 "commands":[]}
                """.trimIndent()
            )
        }
    }

}
