package dev.dootah.compiler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The interception must do two things that pull in opposite directions: route a
 * screen to Dootah when a remote implementation exists, and leave the original
 * body in the binary untouched so it can still run. These tests pin both.
 */
class InterceptionTransformerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `inserts the Dootah runtime calls into a bundlable function`() {

        val result = compile(offerScreen())

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        val compiled = result.compiledClassText("com/example/OfferScreenKt.class")

        assertTrue(
            "the remembered screen lookup was not inserted",
            compiled.contains("rememberDootahScreen"),
        )
        assertTrue(
            "the availability check was not inserted",
            compiled.contains("hasRemoteImplementation"),
        )
        assertTrue(
            "the remote render call was not inserted",
            compiled.contains("DootahRemoteContent"),
        )
    }

    /**
     * The native implementation is the fallback, so it has to survive the
     * rewrite. A transform that routed the screen to Dootah but dropped the
     * original body would leave the app with nothing to fall back to.
     */
    @Test
    fun `keeps the original body in the compiled output`() {

        val result = compile(offerScreen())

        val compiled = result.compiledClassText("com/example/OfferScreenKt.class")

        assertTrue(
            "the original body's content is missing from the compiled class",
            compiled.contains(NATIVE_BODY_MARKER),
        )
    }

    @Test
    fun `derives the screen id from the fully qualified function name`() {

        val result = compile(offerScreen())

        assertTrue(
            result.orderingReport()!!,
            result.orderingReport()!!.contains("intercepted=com.example.OfferScreen"),
        )
    }

    @Test
    fun `an explicit id overrides the derived one`() {

        val result = compile(
            SourceFile(
                name = "Renamed.kt",
                contents = """
                    package com.example

                    import androidx.compose.runtime.Composable
                    import dev.dootah.Bundlable

                    @Bundlable("checkout-screen")
                    @Composable
                    fun SomeLaterName() {}
                """.trimIndent(),
            )
        )

        val report = result.orderingReport()!!

        assertTrue(report, report.contains("intercepted=checkout-screen"))
        assertFalse(report, report.contains("intercepted=com.example.SomeLaterName"))
    }

    /**
     * Discovery is automatic, so the question is no longer "was it annotated"
     * but "is it a shape Dootah can take over". A wrapper's content is supplied
     * by its caller, and a screen Dootah rendered remotely would have nothing to
     * put there -- so the wrapper is left exactly as written.
     */
    @Test
    fun `leaves a composable it cannot take over alone`() {

        val result = compile(
            SourceFile(
                name = "Plain.kt",
                contents = """
                    package com.example

                    import androidx.compose.runtime.Composable

                    @Composable
                    fun PlainWrapper(content: @Composable () -> Unit) {
                        content()
                    }
                """.trimIndent(),
            )
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        val compiled = result.compiledClassText("com/example/PlainKt.class")

        assertFalse(
            "a composable taking content must not be intercepted",
            compiled.contains("rememberDootahScreen"),
        )
    }

    /** The opt-out an app reaches for when a screen must stay native. */
    @Test
    fun `leaves a composable marked DootahNative alone`() {

        val result = compile(
            SourceFile(
                name = "Audited.kt",
                contents = """
                    package com.example

                    import androidx.compose.runtime.Composable
                    import dev.dootah.DootahNative

                    @DootahNative
                    @Composable
                    fun AuditedScreen() {}
                """.trimIndent(),
            )
        )

        assertTrue("compilation failed: ${result.messages}", result.succeeded)

        assertFalse(
            "a function marked @DootahNative must not be intercepted",
            result.compiledClassText("com/example/AuditedKt.class")
                .contains("rememberDootahScreen"),
        )
    }

    /**
     * Without the runtime there is nothing to intercept into, and generating the
     * calls anyway would fail later with an unresolved reference that names none
     * of the cause.
     */
    @Test
    fun `fails with an actionable message when the Dootah runtime is absent`() {

        val result = compileWithDootah(
            workingDirectory = temporaryFolder.root,
            sources = listOf(offerScreen()),
            withDootahRuntime = false,
        )

        assertFalse("compilation should have failed", result.succeeded)

        val errors = result.messagesContaining("Dootah's runtime is not on this module's compile classpath")

        assertTrue("expected the missing-runtime error, got: ${result.messages}", errors.isNotEmpty())
        assertTrue(errors.single(), errors.single().contains("dootah-android"))
    }

    private fun compile(source: SourceFile): CompilationResult =
        compileWithDootah(
            workingDirectory = temporaryFolder.root,
            sources = listOf(source),
        )

    private fun offerScreen() = SourceFile(
        name = "OfferScreen.kt",
        contents = """
            package com.example

            import androidx.compose.runtime.Composable

            @Composable
            fun OfferScreen() {
                val price = 999
                Native.show("$NATIVE_BODY_MARKER" + price)
            }

            object Native {
                fun show(text: String) {}
            }
        """.trimIndent(),
    )

    private companion object {
        /** A literal from the native body, used to prove it survived. */
        const val NATIVE_BODY_MARKER = "native-offer-body"
    }
}
