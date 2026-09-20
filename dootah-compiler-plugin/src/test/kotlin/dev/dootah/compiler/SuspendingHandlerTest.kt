package dev.dootah.compiler

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A screen that starts coroutines, which most real screens do.
 *
 * `LaunchedEffect { ... }` and `scope.launch { ... }` are suspend lambdas
 * declared inside the body Dootah rewrites. The JVM backend rewrites every
 * suspend function into a continuation-passing view afterwards, and it asserts
 * that the calls it is retargeting still point at declarations it has seen. A
 * transform that moves or copies a suspend lambda without keeping that identity
 * intact fails inside `AddContinuationLowering`, with nothing in the message
 * naming Dootah.
 */
class SuspendingHandlerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a screen that launches coroutines still compiles`() {
        val result = compileWithDootah(
            workingDirectory = temporaryFolder.newFolder(),
            sources = listOf(SourceFile("Entry.kt", """
                package com.example

                import androidx.compose.runtime.*
                import androidx.compose.material3.Text

                class Scope {
                    // A receiver on the suspend lambda, as the real coroutine
                    // builders declare it.
                    fun launch(block: suspend Scope.() -> Unit) {}
                }

                class DrawerState {
                    suspend fun close() {}
                }

                @Composable
                fun rememberScope(): Scope = remember { Scope() }

                @Composable
                fun Effect(key: Any?, block: suspend Scope.() -> Unit) {}

                // A handler parameter that is itself suspend, beside a slot.
                // This is the shape a navigation drawer has, and the one the
                // JVM backend's continuation rewrite is strictest about.
                @Composable
                fun Drawer(
                    onDismissRequest: suspend () -> Unit,
                    content: @Composable () -> Unit,
                ) {}

                @Composable
                fun Entry(route: String) {
                    var open by remember { mutableStateOf(false) }
                    val scope = rememberScope()
                    val drawerState = remember { DrawerState() }
                    Effect(route) { open = true }
                    Text("beside the effect")
                    Drawer(onDismissRequest = { drawerState.close() }) {
                        Text("inside the drawer")
                    }
                    Button(onClick = { scope.launch { open = !open } })
                }

                @Composable
                fun Button(onClick: () -> Unit) {}
            """.trimIndent())),
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)
        assertTrue(result.interceptedScreens().contains("com.example.Entry"))
    }
}
