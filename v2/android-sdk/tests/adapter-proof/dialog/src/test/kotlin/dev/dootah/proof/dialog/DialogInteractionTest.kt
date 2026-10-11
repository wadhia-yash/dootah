package dev.dootah.proof.dialog

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import dev.dootah.portable.PortableProgram
import dev.dootah.portable.UiTree
import dev.dootah.runtime.InstalledAdapters
import dev.dootah.runtime.PortableHost
import dev.dootah.runtime.portableOverride
import dev.dootah.runtime.renderNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.test.*

private class NoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

/** The sandbox stand-in: real generated JavaScript run by node; work completes only when the test says so. */
private class NodeHost(private val program: PortableProgram) : PortableHost {
    val results = mutableStateMapOf<List<Any?>, UiTree.Result>()
    private val work = ArrayDeque<() -> Unit>()
    var rejected: Exception? = null
    var evaluations = 0

    override fun lookup(id: String, inputs: List<Any?>): UiTree.Result? {
        results[inputs]?.let { return it }
        work.addLast { results[inputs] = program.renderResult(id, node(program.source(id, inputs))).also { evaluations++ } }
        return null
    }
    override fun evaluateAction(id: String, action: Int, inputs: List<Any?>, done: (List<Any?>) -> Unit) {
        work.addLast { done(program.actionResult(id, action, node(program.actionSource(id, action, inputs)))) }
    }
    override fun reject(error: Exception) { rejected = error }
    fun complete() { while (work.isNotEmpty()) work.removeFirst()() }
    val pending get() = work.size

    private fun node(source: String): String {
        val process = ProcessBuilder("node", "-e", "process.stdout.write(eval(process.argv[1]))", source).start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), err)
        return out
    }
}

/**
 * Technical checkpoint on real Kotlin/Compose compiler output and the real Compose runtime: the generated
 * multi-slot adapter, Compose-owned state, installed callbacks, recomposition and pending evaluations.
 */
class DialogInteractionTest {
    private val raw = javaClass.getResource("/interaction.json")!!.readText()
    private val registry = InstalledAdapters.registry
    private val events = mutableListOf<String>()
    private val dismiss: () -> Unit = { events += "dismiss" }
    private val delete: (Boolean) -> Unit = { events += "delete($it)" }

    private val clock = BroadcastFrameClock()
    private val job = Job()
    private val recomposer = Recomposer(clock + job + Dispatchers.Unconfined)
    private lateinit var composition: Composition
    private val tick = mutableIntStateOf(0)

    @BeforeTest fun start() {
        Drawn.clear()
        CoroutineScope(clock + job + Dispatchers.Unconfined).launch { recomposer.runRecomposeAndApplyChanges() }
    }
    @AfterTest fun stop() { if (::composition.isInitialized) composition.dispose(); recomposer.cancel(); job.cancel() }

    private fun frame() { Snapshot.sendApplyNotifications(); clock.sendFrame(System.nanoTime()) }

    /** The bytecode hook's behaviour: the update's override at the call site, or the original native body. */
    private fun show(program: PortableProgram, host: PortableHost, values: List<Any?> = listOf(dismiss, delete)) {
        val id = program.functionIds().single()
        composition = Composition(NoNodes(), recomposer)
        composition.setContent {
            tick.intValue
            val rendered = portableOverride(program, id, program.parameters(id), values, host)
            if (rendered == null) DeleteDialog(dismiss, delete)
            else renderNative(rendered.first.root, rendered.first.slots, rendered.second, registry)
        }
        frame()
    }
    private fun drawn(): List<String> = Drawn.calls.toList().also { Drawn.calls.clear() }

    @Test fun multiSlotStateAndCallbacksRunThroughTheInstalledComponents() {
        // 1. The generated adapter for the multi-slot dialog compiled, with nested slots and callbacks.
        val dialog = registry.signatures.entries.single { it.key.startsWith("dev.dootah.proof.dialog.Dialog#") && !it.key.contains('/') }
        assertEquals("@slot:title,@slot:text,@slot:confirmButton,@slot:dismissButton,@event", dialog.value)
        assertEquals(setOf("@content,@event", "Boolean,@event", "@content", ""), registry.signatures.values.toSet() - dialog.value)

        val program = PortableProgram(raw, "dialog-fixture")
        val host = NodeHost(program)
        show(program, host)
        // No first result yet: the original native body renders, with its installed initial state.
        assertTrue("CheckRow(Reset,false)" in drawn())
        host.complete(); frame()
        // 2. The update's state initializes (mutableStateOf(true)) and every slot renders installed components.
        assertEquals(listOf("Dialog(3)", "Label(Remove)", "Stack(4)", "Label(Also reset progress?)", "CheckRow(Reset,true)",
            "Action", "Label(Remove)", "Action", "Label(Cancel)"), drawn())

        // The native checkbox reports a change: the action sets Compose state and the dialog recomposes.
        Drawn.toggle!!(false)
        host.complete(); frame()
        // 5. While the new state's evaluation is pending, the last valid UI stays: the override dialog is neither
        // disposed nor replaced by the native body (whose checkbox would read its own, separate state).
        val pendingFrame = drawn()
        assertEquals(1, host.pending)
        assertEquals(listOf("+", "-", "+"), Drawn.lifecycle, "native first frame, then the override, then nothing")
        assertTrue(pendingFrame.none { it.startsWith("CheckRow(Reset,false)") }, pendingFrame.toString())
        host.complete(); frame()
        assertTrue("CheckRow(Reset,false)" in drawn())
        assertEquals(listOf("+", "-", "+"), Drawn.lifecycle)

        // 4. Recomposition keeps the state and never replays an event.
        repeat(3) { tick.intValue++; frame() }
        // Compose may skip unchanged components; whatever it redraws shows the kept state (Remove below reads it).
        val rows = drawn().filter { it.startsWith("CheckRow") }
        assertTrue(rows.all { it == "CheckRow(Reset,false)" }, rows.toString())
        assertTrue(events.isEmpty())
        assertNull(host.rejected)

        // 3. Remove: the installed callbacks receive the current state, in order, exactly once.
        Drawn.clicks.getValue("Label(Remove)")()
        host.complete(); frame()
        assertEquals(listOf("delete(false)", "dismiss"), events)
        events.clear()
        Drawn.clicks.getValue("Label(Cancel)")()
        Drawn.dismiss!!()
        host.complete(); frame()
        assertEquals(listOf("dismiss", "dismiss"), events)
        assertNull(host.rejected)
    }

    /** Events queue in order; each reads the state as left by the earlier ones; a disposed call site drops the rest. */
    @Test fun eventsAreOrderedAppliedOnceAndDroppedAfterDisposal() {
        val program = PortableProgram(raw, "dialog-fixture")
        val host = NodeHost(program)
        show(program, host)
        host.complete(); frame()
        Drawn.toggle!!(false)
        Drawn.clicks.getValue("Label(Remove)")()
        host.complete(); frame()
        assertEquals(listOf("delete(false)", "dismiss"), events)
        events.clear()
        Drawn.clicks.getValue("Label(Remove)")()
        composition.dispose()
        host.complete()
        assertTrue(events.isEmpty(), "an event outlived its call site")
    }

    /** 6. A mistyped event program, or a callback of another shape, refuses the update; nothing is invoked. */
    @Test fun invalidEventsAndCallbacksFailSafely() {
        val forged = JSONObject(raw)
        val actions = forged.getJSONArray("overrides").getJSONObject(0).getJSONArray("actions")
        for (i in 0 until actions.length()) {
            val action = actions.getJSONObject(i)
            if (action.getJSONArray("parameters").length() == 1) {
                // Declares a String event while the installed checkbox reports a Boolean.
                action.put("parameters", org.json.JSONArray(listOf("String")))
                action.getJSONArray("steps").getJSONObject(0).put("value", JSONObject("""{"op":"literal","type":"Boolean","value":true}"""))
            }
        }
        val mistyped = PortableProgram(forged.toString(), "dialog-fixture")
        val host = NodeHost(mistyped)
        show(mistyped, host)
        host.complete(); frame()
        Drawn.toggle!!(false)
        host.complete(); frame()
        assertEquals("Event parameter type mismatch", host.rejected?.message)
        assertTrue(events.isEmpty())
        composition.dispose()

        // The hook passed callbacks of the wrong shape (swapped): refused before anything renders or runs.
        Drawn.clear()
        val program = PortableProgram(raw, "dialog-fixture")
        val other = NodeHost(program)
        show(program, other, listOf(delete, dismiss))
        assertEquals("Installed callback type", other.rejected?.message)
        assertEquals(0, other.evaluations)
        assertTrue("CheckRow(Reset,false)" in drawn(), "the original native body remains")
    }
}
