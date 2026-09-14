package dev.dootah.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one place that holds every layer of the layout vocabulary to the same
 * list.
 *
 * A token is only closed if it is closed everywhere. It has to be read by FIR
 * lowering, written into the generated bundle source, serialised by the JS
 * runtime, parsed by the app and resolved by the renderer -- six implementations
 * of one vocabulary, in three languages, across a network and a version
 * boundary. Five of them agreeing is not a vocabulary; it is a bug that draws
 * the wrong layout on a device.
 *
 * The declarations in this module are the golden copy. Where a layer can be
 * linked against, it is checked directly; where it cannot -- the JS runtime is
 * compiled for another target, the renderer needs Compose and a device -- its
 * source is read and each token is required to appear in the `when` that
 * resolves it. Reading source is blunt, and it is still the check that would
 * have caught every drift this codebase has actually had: a token added on one
 * side and forgotten on the other.
 *
 * This is also the file to change deliberately. Adding a token here fails until
 * all six layers carry it, which is the intended cost of widening what a bundle
 * may say.
 */
class LayoutVocabularyGoldenTest {

    /**
     * The vocabulary, written out rather than derived.
     *
     * Deriving it from [Alignments] would make this test agree with itself: the
     * point is that someone editing the vocabulary has to edit this too, and see
     * every layer that must follow.
     */
    private val golden = mapOf(
        "Alignments.HORIZONTAL" to listOf("Start", "CenterHorizontally", "End"),
        "Alignments.VERTICAL" to listOf("Top", "CenterVertically", "Bottom"),
        "Alignments.BOX" to listOf(
            "TopStart", "TopCenter", "TopEnd",
            "CenterStart", "Center", "CenterEnd",
            "BottomStart", "BottomCenter", "BottomEnd",
        ),
        "Arrangements.VERTICAL" to listOf(
            "Top", "Bottom", "Center", "SpaceBetween", "SpaceAround", "SpaceEvenly", "spacedBy",
        ),
        "Arrangements.HORIZONTAL" to listOf(
            "Start", "End", "Center", "SpaceBetween", "SpaceAround", "SpaceEvenly", "spacedBy",
        ),
    )

    private val repository: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    // ---- layer 1: the contract's own declarations -------------------------

    @Test
    fun `contract declares exactly the golden vocabulary`() {
        assertEquals(golden["Alignments.HORIZONTAL"], Alignments.HORIZONTAL)
        assertEquals(golden["Alignments.VERTICAL"], Alignments.VERTICAL)
        assertEquals(golden["Alignments.BOX"], Alignments.BOX)
        assertEquals(golden["Arrangements.VERTICAL"], Arrangements.VERTICAL)
        assertEquals(golden["Arrangements.HORIZONTAL"], Arrangements.HORIZONTAL)
    }

    @Test
    fun `membership tests agree with the lists they guard`() {
        golden.getValue("Alignments.HORIZONTAL").forEach {
            assertTrue(it, Alignments.isKnownHorizontal(it))
        }
        golden.getValue("Alignments.VERTICAL").forEach {
            assertTrue(it, Alignments.isKnownVertical(it))
        }
        golden.getValue("Alignments.BOX").forEach { assertTrue(it, Alignments.isKnownBox(it)) }
        golden.getValue("Arrangements.VERTICAL").forEach {
            assertTrue(it, Arrangements.isKnownVertical(it))
        }
        golden.getValue("Arrangements.HORIZONTAL").forEach {
            assertTrue(it, Arrangements.isKnownHorizontal(it))
        }
    }

    /**
     * The axes overlap, and the overlap is the part worth pinning.
     *
     * `Center` and the three `Space*` arrangements are one Compose object
     * implementing both interfaces, so they belong to both sets. `Top` and
     * `Start` are not: a set that quietly gained the wrong one would let a
     * bundle name an arrangement the app cannot resolve for that axis.
     */
    @Test
    fun `axes share only what Compose declares on both`() {
        assertEquals(
            listOf("Center", "SpaceBetween", "SpaceAround", "SpaceEvenly", "spacedBy"),
            Arrangements.VERTICAL.filter { it in Arrangements.HORIZONTAL },
        )
        assertEquals(
            emptyList<String>(),
            Alignments.HORIZONTAL.filter { it in Alignments.VERTICAL },
        )
    }

    // ---- layer 2: FIR lowering -------------------------------------------

    /**
     * Lowering must read the vocabulary rather than restate it.
     *
     * A second list inside the compiler is the drift this whole file exists to
     * prevent, so what is checked is that it refers to these declarations and
     * spells no token of its own.
     */
    @Test
    fun `FIR lowering reads the vocabulary from the contract`() {

        val source = source("dootah-compiler-plugin/src/main/kotlin/dev/dootah/compiler/fir/LayoutLowering.kt")

        listOf(
            "Alignments.HORIZONTAL", "Alignments.VERTICAL", "Alignments.BOX",
            "Arrangements.VERTICAL", "Arrangements.HORIZONTAL", "Arrangements.SPACED_BY",
        ).forEach { reference ->
            assertTrue("lowering does not use $reference", reference in source)
        }

        assertNoLiteralTokens(source, "FIR lowering")
    }

    // ---- layer 3: the generated bundle representation ----------------------

    @Test
    fun `the generator emits every layout argument`() {

        val source = source("dootah-compiler-plugin/src/main/kotlin/dev/dootah/compiler/generate/BundleSourceWriter.kt")

        PARAMETERS.forEach { parameter ->
            assertTrue("the generator never emits $parameter", parameter in source)
        }

        assertTrue("the generator does not emit an arrangement", "ArrangementNode(" in source)
        assertNoLiteralTokens(source, "the generator")
    }

    // ---- layer 4: JS serialization ---------------------------------------

    @Test
    fun `the JS node model and serializer carry every layout argument`() {

        val model = source("dootah-bundle-runtime/src/jsMain/kotlin/ui/BundleNode.kt")
        val serializer = source("dootah-bundle-runtime/src/jsMain/kotlin/ui/BundleSerializer.kt")

        PARAMETERS.forEach { parameter ->
            assertTrue("the JS node model has no $parameter", parameter in model)
            assertTrue("the JS serializer never writes $parameter", parameter in serializer)
        }

        assertTrue("the JS model has no arrangement", "class ArrangementNode" in model)
        assertTrue("the JS serializer omits the spacedBy gap", "\\\"space\\\"" in serializer)
        assertNoLiteralTokens(model, "the JS node model")
        assertNoLiteralTokens(serializer, "the JS serializer")
    }

    // ---- layer 5: the Android parser and model ----------------------------

    @Test
    fun `the Android parser checks every name against this vocabulary`() {

        val model = source("dootah-android/src/main/java/com/dootah/ui/BundleUiNode.kt")
        val parser = source("dootah-android/src/main/java/com/dootah/ui/BundleUiParser.kt")

        PARAMETERS.forEach { parameter ->
            assertTrue("the Android model has no $parameter", parameter in model)
            assertTrue("the parser never reads $parameter", parameter in parser)
        }

        listOf(
            "Alignments::isKnownHorizontal",
            "Alignments::isKnownVertical",
            "Alignments::isKnownBox",
            "Arrangements::isKnownVertical",
            "Arrangements::isKnownHorizontal",
        ).forEach { check ->
            assertTrue("the parser does not apply $check", check in parser)
        }

        assertNoLiteralTokens(parser, "the Android parser")
    }

    // ---- layer 6: the Compose renderer ------------------------------------

    /**
     * The renderer is the one layer that must spell the tokens out.
     *
     * Every other layer passes a name through; this one turns a name into a
     * Compose object, and there is no way to do that without naming both sides.
     * So here the check is the opposite of the others: every token in the golden
     * vocabulary must appear in the `when` for its axis, and the axes must not
     * be confused with one another.
     */
    @Test
    fun `the renderer resolves every token, on the right axis`() {

        val source = source("dootah-android/src/main/java/com/dootah/ui/BundleRenderer.kt")

        val resolvers = mapOf(
            "Alignments.HORIZONTAL" to Pair(
                block(source, "private fun horizontalAlignment(") to "Alignment",
                "horizontalAlignment",
            ),
            "Alignments.VERTICAL" to Pair(
                block(source, "private fun verticalAlignment(") to "Alignment",
                "verticalAlignment",
            ),
            "Alignments.BOX" to Pair(
                block(source, "private fun boxAlignment(") to "Alignment",
                "boxAlignment",
            ),
            "Arrangements.VERTICAL" to Pair(
                block(source, "private fun verticalArrangement(") to "Arrangement",
                "verticalArrangement",
            ),
            "Arrangements.HORIZONTAL" to Pair(
                block(source, "private fun horizontalArrangement(") to "Arrangement",
                "horizontalArrangement",
            ),
        )

        resolvers.forEach { (name, spec) ->
            val (blockAndOwner, resolver) = spec
            val (body, owner) = blockAndOwner

            golden.getValue(name).forEach { token ->

                // `spacedBy` is a call, not a member, and is named through the
                // contract's constant rather than spelled.
                val expected =
                    if (token == Arrangements.SPACED_BY) "$owner.spacedBy(" else "$owner.$token"

                assertTrue(
                    "$resolver does not resolve '$token' to $expected",
                    expected in body,
                )
            }
        }

        // The axes must stay apart: a `Row`'s vertical alignment resolving to
        // `Alignment.Start` would compile if the types ever widened, and would
        // be wrong.
        val vertical = block(source, "private fun verticalAlignment(")
        assertTrue(
            "vertical alignment resolves a horizontal value",
            Alignments.HORIZONTAL.none { "Alignment.$it" in vertical },
        )
    }

    // ---- helpers ----------------------------------------------------------

    /** No layer but the renderer may spell a token; the rest pass names through. */
    private fun assertNoLiteralTokens(source: String, layer: String) {

        val spelled = (golden.values.flatten().toSet() - Arrangements.SPACED_BY)
            .filter { token -> "\"$token\"" in source }

        assertTrue(
            "$layer spells the token(s) $spelled instead of reading the vocabulary",
            spelled.isEmpty(),
        )
    }

    /** One function body, from its signature to the blank line after it ends. */
    private fun block(source: String, signature: String): String {

        val start = source.indexOf(signature)
        assertTrue("no `$signature` in the renderer", start >= 0)

        val end = source.indexOf("\n}\n", start).takeIf { it > 0 }
            ?: source.indexOf("\n    }\n", start)

        return source.substring(start, if (end > 0) end else source.length)
    }

    private fun source(path: String): String {
        val file = File(repository, path)
        assertTrue("missing $path", file.exists())
        return file.readText()
    }

    private companion object {

        /** The layout parameters this milestone added, as every layer names them. */
        val PARAMETERS = listOf(
            "horizontalAlignment", "verticalAlignment", "contentAlignment",
            "verticalArrangement", "horizontalArrangement",
        )
    }
}
