package dev.dootah.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.dootah.portable.PortableProgram
import dev.dootah.portable.UiTree

/** The installed runtime's evaluation services: the sandbox, its observable result cache, and rejection. */
interface PortableHost {
    /** The render result for these Logic inputs, read as snapshot state; null while pending. Never blocks. */
    fun lookup(id: String, inputs: List<Any?>): UiTree.Result?
    /** Evaluates one action off the main thread; [done] runs on the main thread with the validated step values. */
    fun evaluateAction(id: String, action: Int, inputs: List<Any?>, done: (List<Any?>) -> Unit)
    /** Refuses the update; the original native body renders again. */
    fun reject(error: Exception)
}

/**
 * One hooked call site rendered by the update. State is Compose's own: `mutableStateOf` holders remembered
 * at the call site, so recomposition and pending evaluations keep it. Callback parameters are held natively
 * and invoked only by validated `call` steps; the sandbox sees scalar inputs and state values only.
 */
class PortableSession internal constructor(program: PortableProgram, private val id: String, private val host: PortableHost) {
    private val interaction = program.interaction(id)
    internal val state: List<MutableState<Any>> = interaction.initials.map { mutableStateOf(it) }
    /** The hook's latest parameter values, published after each committed composition. */
    internal var parameters: List<Any?> = emptyList()
    /** The last valid result: drawn while the next evaluation is pending, so the UI and its state persist. */
    internal var last: UiTree.Result? = null
    internal var active = true
    private val queue = ArrayDeque<Pair<Int, List<Any?>>>()
    private var running = false

    internal fun inputs(scalars: List<Any?>): List<Any?> = scalars + state.map { it.value }

    /**
     * A native callback, on the main thread. Each invocation runs its action exactly once, after every earlier
     * one has been applied, and reads the state as it is at its turn. A mistyped event refuses the update.
     */
    internal fun fire(action: Int, type: String?, value: Any?) {
        if (!active) return
        try {
            val declared = interaction.action(action).parameters
            require(type != NativeCall.UNSUPPORTED) { "Unsupported callback parameter type" }
            // An action may ignore the callback's value, but may never read a value of another type.
            val arguments = if (declared.isEmpty()) emptyList() else {
                require(declared.size == 1 && type == declared[0]) { "Event parameter type mismatch" }
                listOf(value)
            }
            queue.addLast(action to arguments)
            drain()
        } catch (e: Exception) {
            active = false
            host.reject(e)
        }
    }

    private fun drain() {
        if (running || !active) return
        val (action, arguments) = queue.removeFirstOrNull() ?: return
        running = true
        val scalars = interaction.scalars.map { parameters[it] }
        host.evaluateAction(id, action, inputs(scalars) + arguments) { values ->
            running = false
            // A call site that left composition (or a refused update) drops its outstanding events.
            if (!active) { queue.clear(); return@evaluateAction }
            apply(action, values)
            drain()
        }
    }

    private fun apply(action: Int, values: List<Any?>) {
        var next = 0
        for (step in interaction.action(action).steps) {
            if (step.set) {
                state[step.target].value = requireNotNull(values[next++])
            } else {
                val arguments = values.subList(next, next + step.values)
                next += step.values
                invoke(parameters[step.target], arguments)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun invoke(callback: Any?, arguments: List<Any?>) {
        // Arity and argument types were validated against the installed callback type before rendering.
        when (arguments.size) {
            0 -> (callback as Function0<Any?>).invoke()
            1 -> (callback as Function1<Any?, Any?>).invoke(arguments[0])
            else -> error("Callback arity")
        }
    }

    companion object {
        /** True when [value] is an installed callback of exactly the declared Kotlin function type's arity. */
        internal fun callback(value: Any?, type: String): Boolean = when (PortableProgram.callbackArguments(type).size) {
            0 -> value is Function0<*>
            1 -> value is Function1<*, *>
            else -> false
        }
    }
}

/**
 * Renders [id]'s update at the hooked call site, or returns null (the native body renders) until a first
 * result exists. [types] and [values] are the hook's installed parameter tokens and values.
 */
@Composable
fun portableOverride(program: PortableProgram, id: String, types: List<String>, values: List<Any?>,
                     host: PortableHost): Pair<UiTree.Result, PortableSession>? {
    val session = remember(program, id) { PortableSession(program, id, host) }
    DisposableEffect(session) { onDispose { session.active = false } }
    if (!session.active) return null
    val interaction = program.interaction(id)
    try {
        require(program.parameters(id) == types && values.size == types.size) { "Installed input ABI mismatch" }
        interaction.callbacks.forEach { require(PortableSession.callback(values[it], types[it])) { "Installed callback type" } }
    } catch (e: Exception) {
        session.active = false
        host.reject(e)
        return null
    }
    SideEffect { session.parameters = values }
    val found = host.lookup(id, session.inputs(interaction.scalars.map { values[it] }))
    if (found != null) session.last = found
    return (found ?: session.last)?.let { it to session }
}

/** Every active native call names an installed adapter with matching values, content slots and callbacks. */
fun nativeCallsAccepted(result: UiTree.Result, registry: NativeAdapterRegistry): Boolean {
    fun accepted(node: UiTree.Node): Boolean = when (node.kind) {
        "native" -> NativeAdapterHost.accepts(registry, node.adapter, adapterArguments(node, result.slots), node.contents.size, node.events.size) &&
            node.contents.all { slot -> slot.all(::accepted) }
        "if" -> (if (result.slots[node.slot] as Boolean) node.children else node.otherwise).all(::accepted)
        else -> node.children.all(::accepted)
    }
    return result.root == null || accepted(result.root)
}

/** Renders one validated native node; a refused adapter composes nothing. */
@Composable
fun renderNative(node: UiTree.Node, slots: List<Any>, session: PortableSession?, registry: NativeAdapterRegistry = InstalledAdapters.registry) {
    NativeAdapterHost.render(registry, node.adapter, TreeCall(node, slots, session, registry), node.contents.size, node.events.size)
}

private class TreeCall(private val node: UiTree.Node, private val slots: List<Any>, private val session: PortableSession?,
                       private val registry: NativeAdapterRegistry) : NativeCall() {
    override val arguments: List<Any?> = adapterArguments(node, slots)

    @Composable
    override fun content(slot: Int, child: @Composable (id: String, call: NativeCall) -> Unit) =
        emitChildren(node.contents[slot], slots, HashMap(), session, registry, child)

    override fun fire(index: Int, type: String?, value: Any?) {
        val action = node.events.getOrNull(index) ?: return
        session?.fire(action, type, value)
    }
}

/** A slot's validated children in update order; each child keeps a stable key across reordering. */
@Composable
private fun emitChildren(nodes: List<UiTree.Node>, slots: List<Any>, seen: HashMap<String, Int>, session: PortableSession?,
                         registry: NativeAdapterRegistry, child: @Composable (id: String, call: NativeCall) -> Unit) {
    nodes.forEach { n ->
        if (n.kind == "if") emitChildren(if (slots[n.slot] as Boolean) n.children else n.otherwise, slots, seen, session, registry, child)
        else {
            val occurrence = seen.merge(n.adapter, 1, Int::plus)!!
            key(n.adapter, occurrence) {
                val call = TreeCall(n, slots, session, registry)
                if (NativeAdapterHost.accepts(registry, n.adapter, call.arguments, n.contents.size, n.events.size)) child(n.adapter, call)
            }
        }
    }
}

internal fun adapterArguments(node: UiTree.Node, slots: List<Any>): List<Any?> =
    node.arguments.map { index -> slots[index].takeUnless { it == org.json.JSONObject.NULL } }
