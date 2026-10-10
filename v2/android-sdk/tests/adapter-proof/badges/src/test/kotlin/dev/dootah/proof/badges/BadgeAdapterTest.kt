package dev.dootah.proof.badges

import androidx.compose.runtime.*
import dev.dootah.runtime.InstalledAdapters
import dev.dootah.runtime.NativeAdapterHost
import dev.dootah.runtime.NativeAdapterRegistry
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

/** A node-less applier: the composition runs for real, nothing is drawn. */
private class NoNodes : AbstractApplier<Unit>(Unit) {
    var nodes = 0
    override fun insertTopDown(index: Int, instance: Unit) { nodes++ }
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

private fun compose(content: @Composable () -> Unit): NoNodes {
    val applier = NoNodes()
    val composition = Composition(applier, Recomposer(EmptyCoroutineContext))
    composition.setContent(content)
    composition.dispose()
    return applier
}

class BadgeAdapterTest {
    // Reached through the generated index the SDK links against; no application registration code.
    private val registry: NativeAdapterRegistry = InstalledAdapters.registry
    private val downloads = registry.signatures.keys.single { id ->
        id.startsWith("dev.dootah.proof.badges.Badge#") && compose { Rendered.clear(); NativeAdapterHost.render(registry, id, listOf("1")) }
            .let { Rendered == listOf("Badge(1,secondary,400,pill)") }
    }

    @BeforeTest fun clear() { Rendered.clear() }

    @Test fun indexLinksEveryCallShapeWithTypedSignatures() {
        assertNotSame(NativeAdapterRegistry.Empty, registry)
        assertEquals(4, registry.signatures.size)
        assertTrue(registry.signatures.values.all { it == "String" })
    }

    @Test fun generatedAdapterKeepsDefaultsAndReadsTheCallersComposition() {
        compose { CompositionLocalProvider(LocalPalette provides "dark") { NativeAdapterHost.render(registry, downloads, listOf("3")) } }
        assertEquals(listOf("Badge(3,dark,400,pill)"), Rendered)
    }

    @Test fun twoInputValuesRenderTwoResults() {
        compose { NativeAdapterHost.render(registry, downloads, listOf("1")); NativeAdapterHost.render(registry, downloads, listOf("42")) }
        assertEquals(listOf("Badge(1,secondary,400,pill)", "Badge(42,secondary,400,pill)"), Rendered)
    }

    @Test fun refusesUnknownIdArityTypeAndNullComposingNothing() {
        compose {
            assertFalse(NativeAdapterHost.render(registry, "dev.dootah.proof.badges.Badge#0000000000000000", listOf("1")))
            assertFalse(NativeAdapterHost.render(registry, downloads, emptyList()))
            assertFalse(NativeAdapterHost.render(registry, downloads, listOf(1)))
            assertFalse(NativeAdapterHost.render(registry, downloads, listOf<Any?>(null)))
            assertFalse(NativeAdapterHost.render(NativeAdapterRegistry.Empty, downloads, listOf("1")))
        }
        assertTrue(Rendered.isEmpty())
    }

    @Test fun nativeSourceStillRunsUnchanged() {
        compose { DownloadsBadge(0); DownloadsBadge(5) }
        assertEquals(listOf("Badge(5,secondary,400,pill)"), Rendered)
    }

    /** Empty root branch: nothing is emitted, yet the composition commits once and an effect observes it. */
    @Test fun emptyRootBranchStillCommits() {
        for (visible in listOf(true, false)) {
            Rendered.clear()
            val acks = mutableListOf<Boolean>()
            val applier = compose {
                if (visible) NativeAdapterHost.render(registry, downloads, listOf("1"))
                SideEffect { acks += visible }
            }
            assertEquals(listOf(visible), acks)
            assertEquals(if (visible) 1 else 0, Rendered.size)
            assertEquals(0, applier.nodes)
        }
    }
}
