package com.dootah.ui

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
                BundleUiModifier.Padding(start = 1f, top = 2f, end = 3f, bottom = 4f),
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
}
