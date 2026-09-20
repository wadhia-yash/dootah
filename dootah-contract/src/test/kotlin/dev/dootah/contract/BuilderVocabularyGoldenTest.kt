package dev.dootah.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds every layer of the builder vocabulary to the same list.
 *
 * The same job [LayoutVocabularyGoldenTest] does for alignment and arrangement,
 * for the entries a bundle may put in a native container's builder. The failure
 * it guards against is worse here than for a token: an entry kind one layer
 * knows and another does not is a list that draws nothing, on a screen whose
 * whole content is that list.
 *
 * There is a second thing pinned here, and it is the more important one. The
 * entire point of describing entries rather than the scope is that the scope
 * never leaves the app. So this also checks that no layer of the wire mentions
 * one, and that the renderer reaches Compose's own `item` rather than
 * reimplementing what a list does.
 */
class BuilderVocabularyGoldenTest {

    /** Written out rather than derived, so widening it has to be deliberate. */
    private val golden = listOf("item", "region")

    /** The receiver types the runtime knows how to perform entries against. */
    private val goldenScopes = listOf("androidx.compose.foundation.lazy.LazyListScope")

    /**
     * The calls that declare one describable entry.
     *
     * `item` only. The corpus's other builder calls -- `items` (35 uses),
     * `itemsIndexed` (7) -- range over the app's own domain objects, and
     * `stickyHeader` (1) is a Compose experiment; all three are the app's code
     * and travel as a region rather than as a description. Adding one here is
     * adding a way for the bundle to decide something it currently cannot, and
     * this test is where that decision is recorded.
     */
    private val goldenItemCalls = listOf("androidx.compose.foundation.lazy.LazyListScope.item")

    private val repository: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    // ---- layer 1: the contract's own declarations -------------------------

    @Test
    fun `contract declares exactly the golden vocabulary`() {
        assertEquals(golden.toSet(), BuilderEntries.ALL)
        assertEquals(goldenScopes.toSet(), BuilderScopes.ALL)
        assertEquals(goldenItemCalls.toSet(), BuilderScopes.ITEM_CALLS)
    }

    @Test
    fun `membership tests agree with the lists they guard`() {
        golden.forEach { assertTrue(it, BuilderEntries.isKnown(it)) }
        goldenScopes.forEach { assertTrue(it, BuilderScopes.isDescribable(it)) }
        goldenItemCalls.forEach { assertTrue(it, BuilderScopes.isItemCall(it)) }

        assertTrue("an unknown entry kind is accepted", !BuilderEntries.isKnown("items"))
        assertTrue(
            "an app's own scope is treated as describable",
            !BuilderScopes.isDescribable("com.example.MyScope"),
        )
    }

    // ---- layer 2: FIR lowering -------------------------------------------

    @Test
    fun `FIR lowering reads the vocabulary from the contract`() {

        val lowering = source("dootah-compiler-core/src/main/kotlin/dev/dootah/compiler/lowering/ScreenLowering.kt")
        val components = source("dootah-compiler-core/src/main/kotlin/dev/dootah/compiler/lowering/ComponentLowering.kt")

        assertTrue(
            "lowering does not ask the contract which calls declare an entry",
            "BuilderScopes.isItemCall" in lowering,
        )
        assertTrue(
            "lowering does not ask the contract which scopes are describable",
            "BuilderScopes.isDescribable" in components,
        )

        assertNoLiteralVocabulary(lowering, "FIR lowering")
        assertNoLiteralVocabulary(components, "component lowering")
    }

    /**
     * Both passes have to agree on what a builder slot is.
     *
     * They read two versions of the source and must classify the same parameter
     * the same way. A slot the app's pass thinks is a handler is an adapter that
     * hands a live Compose scope to a bundle's action.
     */
    @Test
    fun `both passes decide a builder slot from the same declarations`() {

        val ir = source("dootah-compiler-plugin/src/main/kotlin/dev/dootah/compiler/ir/ComposeTypes.kt")

        assertTrue(
            "the app's pass does not ask the contract which scopes are describable",
            "BuilderScopes.isDescribable" in ir,
        )
    }

    // ---- layer 3: the generated bundle representation ----------------------

    @Test
    fun `the generator emits every entry kind`() {

        val source = source("dootah-compiler-core/src/main/kotlin/dev/dootah/compiler/generate/BundleSourceWriter.kt")

        assertTrue("the generator never emits an item", "EntryNode.Item(" in source)
        assertTrue("the generator never emits a region", "EntryNode.Region(" in source)
        assertTrue("the generator never fills a builder slot", "entries = mapOf(" in source)
    }

    // ---- layer 4: JS serialization ---------------------------------------

    @Test
    fun `the JS node model and serializer carry every entry kind`() {

        val model = source("dootah-bundle-runtime/src/jsMain/kotlin/ui/BundleNode.kt")
        val serializer = source("dootah-bundle-runtime/src/jsMain/kotlin/ui/BundleSerializer.kt")

        assertTrue("the JS model has no entry type", "sealed interface EntryNode" in model)
        assertTrue("the JS model has no item", "class Item(" in model)
        assertTrue("the JS model has no region", "class Region(" in model)

        golden.forEach { kind ->
            assertTrue(
                "the JS serializer never writes the '$kind' kind",
                "\\\"kind\\\":\\\"$kind\\\"" in serializer,
            )
        }
    }

    // ---- layer 5: the Android parser and model ----------------------------

    @Test
    fun `the Android parser checks every entry kind against this vocabulary`() {

        val model = source("dootah-android/src/main/java/com/dootah/ui/BundleUiNode.kt")
        val parser = source("dootah-android/src/main/java/com/dootah/ui/BundleUiParser.kt")

        assertTrue("the Android model has no entry type", "sealed interface BundleUiEntry" in model)
        assertTrue("the model has no item", "class Item(" in model)
        assertTrue("the model has no region", "class Region(" in model)

        assertTrue("the parser does not read the item kind", "BuilderEntries.ITEM" in parser)
        assertTrue("the parser does not read the region kind", "BuilderEntries.REGION" in parser)

        assertNoLiteralVocabulary(parser, "the Android parser")
    }

    // ---- layer 6: the Compose renderer ------------------------------------

    /**
     * The renderer performs entries; it does not implement a list.
     *
     * This is the architectural line the whole milestone rests on, so it is
     * asserted rather than trusted: the renderer calls Compose's own `item`
     * against the scope Compose handed it, and holds no idea of its own about
     * how many entries to compose or when.
     */
    @Test
    fun `the renderer declares entries on Compose's own scope`() {

        val renderer = source("dootah-android/src/main/java/com/dootah/ui/BundleRenderer.kt")

        assertTrue(
            "the renderer does not run against a real LazyListScope",
            "private fun LazyListScope.declare(" in renderer,
        )
        assertTrue("the renderer never calls Compose's item", "is BundleUiEntry.Item -> item {" in renderer)
        assertTrue(
            "the renderer never performs the app's own region",
            "is BundleUiEntry.Region ->" in renderer,
        )
    }

    /**
     * Nothing about laying a list out is ours.
     *
     * The stop condition for this milestone, written down as a test. If any of
     * these ever appears in the renderer, Dootah has started reimplementing the
     * thing it was supposed to delegate.
     */
    @Test
    fun `no layer measures, scrolls, virtualises or recycles`() {

        val ours = listOf(
            "dootah-android/src/main/java/com/dootah/ui/BundleRenderer.kt",
            "dootah-android/src/main/java/com/dootah/ui/BundleUiParser.kt",
            "dootah-bundle-runtime/src/jsMain/kotlin/ui/BundleNode.kt",
            "dootah-bundle-runtime/src/jsMain/kotlin/ui/BundleSerializer.kt",
        )

        val forbidden = listOf(
            "visibleItem", "firstVisible", "scrollBy", "scrollTo", "animateScroll",
            "MeasureResult", "measurePolicy", "SubcomposeLayout", "recycle", "viewport",
        )

        ours.forEach { path ->
            val text = source(path)
            forbidden.forEach { word ->
                assertTrue("$path does a list's own job: it mentions $word", word !in text)
            }
        }
    }

    /**
     * The scope stays on the app's side of the wire.
     *
     * The bundle names entries. It never holds, receives or sends a scope, and
     * nothing that crosses the wire should be able to mention one.
     */
    @Test
    fun `no scope type appears in anything that crosses the wire`() {

        val onTheWire = listOf(
            "dootah-bundle-runtime/src/jsMain/kotlin/ui/BundleNode.kt",
            "dootah-bundle-runtime/src/jsMain/kotlin/ui/BundleSerializer.kt",
        )

        onTheWire.forEach { path ->
            // Comments stripped: the prose in these files explains that the
            // scope stays behind, and has to be free to say so.
            val code = codeOf(source(path))
            assertTrue(
                "$path names a scope, which must never reach the bundle",
                !code.contains("scope", ignoreCase = true),
            )
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun source(path: String): String = File(repository, path)
        .also { assertTrue("no such file: $path", it.exists()) }
        .readText()

    /** Source with its comments removed, so prose cannot satisfy or fail a check. */
    private fun codeOf(source: String): String = source.lines()
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
        }
        .joinToString("\n")

    /**
     * No layer but the renderer may spell an entry kind out.
     *
     * Comments are stripped first: this file's own prose names every kind it
     * guards, and so does the prose explaining the code being checked.
     */
    private fun assertNoLiteralVocabulary(source: String, layer: String) {

        val code = codeOf(source)

        golden.forEach { kind ->
            assertTrue(
                "$layer spells \"$kind\" out instead of reading it from the contract",
                "\"$kind\"" !in code,
            )
        }
    }
}
