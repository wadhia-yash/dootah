package dev.dootah.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.dootah.fixture.BadgeRow
import dev.dootah.fixture.DownloadsBadge
import dev.dootah.portable.PortableProgram
import dev.dootah.portable.UiTree
import dev.dootah.runtime.sandbox.RestrictedSandbox
import java.util.Collections
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Device evidence for build-generated native adapters: the app-owned component, its generated
 * adapter and the index are compiled into this APK from ordinary source; the artifact is the
 * analyzer output of the edited rule (`count > 0` -> `count > 1`) and runs in the real sandbox.
 */
class NativeAdapterDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val raw = instrumentation.context.assets.open("adapters.json").bufferedReader().use { it.readText() }
    private val program = PortableProgram(raw, "adapter-fixture")
    private val registry = InstalledAdapters.registry

    private fun evaluate(count: Int): UiTree.Result {
        val id = program.functionIds().single()
        val sandbox = RestrictedSandbox(instrumentation.targetContext)
        val output = try { sandbox.evaluate(program.source(id, listOf("Int"), listOf(count))) } finally { sandbox.close() }
        return program.renderResult(id, output).also { assertTrue(nativeCallsAccepted(it, registry)) }
    }

    @Test fun generatedIndexIsLinkedIntoTheApkAndMatchesTheArtifact() {
        assertNotSame(NativeAdapterRegistry.Empty, registry)
        val nestedRaw = instrumentation.context.assets.open("adapters-nested.json").bufferedReader().use { it.readText() }
        val nested = PortableProgram(nestedRaw, "adapter-fixture")
        // The fixture installs exactly the plain badge and the BadgeRow container with its two children.
        assertEquals(program.nativeAdapters() + nested.nativeAdapters(), registry.signatures)
        requireInstalledAdapters(program, registry)
    }

    @Test fun ruleChangesPerValueWhileTheNativeComponentRendersIdentically() {
        val results = mapOf(1 to evaluate(1), 3 to evaluate(3))
        assertEquals(listOf(false, JSONObject.NULL), results.getValue(1).slots)
        assertEquals(listOf(true, "3"), results.getValue(3).slots)
        val count = mutableIntStateOf(3)
        val remote = mutableStateOf(false)
        val dark = mutableStateOf(false)
        rule.setContent {
            val colors = if (dark.value) darkColorScheme(tertiary = Color(0xffcac1ea)) else lightColorScheme(tertiary = Color(0xff605a7c))
            MaterialTheme(colorScheme = colors) {
                Box(Modifier.size(120.dp, 48.dp).background(MaterialTheme.colorScheme.surface).testTag("capture")) {
                    if (remote.value) { val r = results.getValue(count.intValue); renderTree(r.root, r.slots) }
                    else DownloadsBadge(count.intValue)
                }
            }
        }
        for (night in listOf(false, true)) {
            // count = 3: both rules show the badge; the override draws it through the generated adapter.
            rule.runOnIdle { dark.value = night; count.intValue = 3; remote.value = false }
            rule.onNodeWithText("3").assertExists()
            val native = rule.onNodeWithTag("capture").captureToImage().asAndroidBitmap()
            rule.runOnIdle { remote.value = true }
            rule.onNodeWithText("3").assertExists()
            val adapted = rule.onNodeWithTag("capture").captureToImage().asAndroidBitmap()
            assertTrue("Native component pixels differ: dark=$night", native.sameAs(adapted))
            // count = 1: the installed rule shows "1"; the updated rule renders an empty root branch.
            rule.runOnIdle { count.intValue = 1; remote.value = false }
            rule.onNodeWithText("1").assertExists()
            rule.runOnIdle { remote.value = true }
            rule.onNodeWithText("1").assertDoesNotExist()
            rule.onNodeWithTag("capture").assertExists()
        }
    }

    /** Each composition of an empty or native root is followed by its own committed-frame callback. */
    @Test fun emptyAndNativeRootsAcknowledgeEveryCommittedFrame() {
        val results = listOf(evaluate(1), evaluate(3))
        val generation = mutableIntStateOf(0)
        val acknowledged = Collections.synchronizedSet(HashSet<Int>())
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(120.dp, 48.dp)) {
                    val current = generation.intValue
                    key(current) {
                        val r = results[current % 2]
                        renderTree(r.root, r.slots)
                        FrameCommitted(true) { acknowledged.add(current) }
                    }
                }
            }
        }
        repeat(20) { i ->
            rule.runOnIdle { generation.intValue = i }
            rule.waitUntil(2_000) { i in acknowledged }
            if (i % 2 == 0) rule.onNodeWithText("3").assertDoesNotExist() else rule.onNodeWithText("3").assertExists()
        }
        assertEquals((0 until 20).toSet(), acknowledged.toSet())
    }

    @Test fun uninstalledAdapterIsRefusedAndNativeRemains() {
        val adapter = program.nativeAdapters().keys.single()
        val unknown = adapter.substringBefore('#') + "#" + "0".repeat(16)
        val forged = PortableProgram(raw.replace(adapter, unknown), "adapter-fixture")
        try { requireInstalledAdapters(forged, registry); fail("Uninstalled adapter accepted") }
        catch (expected: IllegalArgumentException) { assertEquals("Native adapter not installed", expected.message) }
        // At composition time a refused call composes nothing and the caller keeps its native body.
        rule.setContent {
            MaterialTheme {
                if (!NativeAdapterHost.render(registry, unknown, Values(listOf("3")))) DownloadsBadge(5)
                if (!NativeAdapterHost.render(registry, adapter, Values(listOf(3)))) DownloadsBadge(6)
            }
        }
        rule.onNodeWithText("5").assertExists()
        rule.onNodeWithText("6").assertExists()
        rule.onNodeWithText("3").assertDoesNotExist()
    }

    /**
     * Nested content: the update reorders the installed Row's existing children and hides one by data.
     * Children render inside the installed Row content lambda (RowScope.weight) as native components.
     */
    @Test fun nestedChildrenAreReorderedAndHiddenInsideTheInstalledRow() {
        val nestedRaw = instrumentation.context.assets.open("adapters-nested.json").bufferedReader().use { it.readText() }
        val nested = PortableProgram(nestedRaw, "adapter-fixture")
        requireInstalledAdapters(nested, registry)
        fun evaluateNested(count: Int): UiTree.Result {
            val id = nested.functionIds().single()
            val sandbox = RestrictedSandbox(instrumentation.targetContext)
            val output = try { sandbox.evaluate(nested.source(id, listOf("Int"), listOf(count))) } finally { sandbox.close() }
            return nested.renderResult(id, output).also { assertTrue(nativeCallsAccepted(it, registry)) }
        }
        val results = mapOf(1 to evaluateNested(1), 3 to evaluateNested(3))
        val count = mutableIntStateOf(3)
        val remote = mutableStateOf(false)
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme(tertiary = Color(0xff605a7c))) {
                Box(Modifier.width(240.dp).testTag("capture")) {
                    if (remote.value) { val r = results.getValue(count.intValue); renderTree(r.root, r.slots) }
                    else BadgeRow(count.intValue)
                }
            }
        }
        fun left(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left
        // Installed order: badge, then the weighted label.
        rule.runOnIdle { count.intValue = 3; remote.value = false }
        val nativeBadge = rule.onNodeWithText("3").captureToImage().asAndroidBitmap()
        assertTrue(left("3") < left("items"))
        // Updated order: label first (still weighted by RowScope), the same native badge after it.
        rule.runOnIdle { remote.value = true }
        assertTrue(left("3") > left("items"))
        assertTrue("Nested native badge pixels differ", nativeBadge.sameAs(rule.onNodeWithText("3").captureToImage().asAndroidBitmap()))
        // count = 1: the conditional child is hidden; the other child remains.
        rule.runOnIdle { count.intValue = 1 }
        rule.onNodeWithText("1").assertDoesNotExist()
        rule.onNodeWithText("items").assertExists()
        rule.runOnIdle { remote.value = false }
        rule.onNodeWithText("1").assertExists()
    }
}

/** A call carrying data values only; refused before it could render content or fire callbacks. */
private class Values(override val arguments: List<Any?>) : NativeCall() {
    @androidx.compose.runtime.Composable
    override fun content(slot: Int, child: @androidx.compose.runtime.Composable (id: String, call: NativeCall) -> Unit) = error("No content")
    override fun fire(index: Int, type: String?, value: Any?) = error("No callbacks")
}
