package dev.dootah.runtime

import androidx.compose.runtime.Composable

/**
 * The only way SDK code reaches a composable the application owns.
 *
 * The Dootah Gradle plugin generates the implementations and one index class in the application
 * module (see AdapterGen); the SDK links against that index, so nothing is looked up by name at run
 * time and no application code registers anything. An adapter exists only for a call the installed
 * source already makes; it accepts scalar values only and runs inside the caller's composition.
 */
interface NativeAdapterRegistry {
    /**
     * Adapter id -> signature: comma-separated data types ("String,Int?", the notation of hook inputs), then
     * one marker per content slot ("@content" for the trailing lambda, "@slot:name") and per callback ("@event").
     */
    val signatures: Map<String, String>

    /** Called only by [NativeAdapterHost] after the id and [call]'s values were checked against [signatures]. */
    @Composable
    fun render(id: String, call: NativeCall)

    object Empty : NativeAdapterRegistry {
        override val signatures: Map<String, String> = emptyMap()
        @Composable
        override fun render(id: String, call: NativeCall) = error("No native adapters are installed")
    }

    companion object {
        /** Combines per-file registries; an identity registered twice is a generator fault and refuses all. */
        @JvmStatic
        fun of(vararg parts: NativeAdapterRegistry): NativeAdapterRegistry = Composite(parts.toList())
    }

    private class Composite(parts: List<NativeAdapterRegistry>) : NativeAdapterRegistry {
        private val owners = HashMap<String, NativeAdapterRegistry>()
        override val signatures: Map<String, String>

        init {
            val all = LinkedHashMap<String, String>()
            parts.forEach { part ->
                part.signatures.forEach { (id, signature) ->
                    require(owners.put(id, part) == null) { "Duplicate native adapter $id" }
                    all[id] = signature
                }
            }
            signatures = all
        }

        @Composable
        override fun render(id: String, call: NativeCall) = owners.getValue(id).render(id, call)
    }
}

/**
 * One installed native call as the update renders it. A generated adapter reads its data values from
 * [arguments], renders each installed content lambda's children through [content], and forwards each
 * installed callback argument to [event]. The Kotlin compiler selects the `event` overload from the
 * callback's own parameter type, so the value arrives tagged with that type; any other type is refused.
 */
abstract class NativeCall {
    abstract val arguments: List<Any?>

    /** Renders the children the update selected for content slot [slot] (signature order), in update order. */
    @Composable
    abstract fun content(slot: Int, child: @Composable (id: String, call: NativeCall) -> Unit)

    fun event(index: Int) = fire(index, null, null)
    @JvmName("eventString") fun event(index: Int, value: String) = fire(index, "String", value)
    @JvmName("eventInt") fun event(index: Int, value: Int) = fire(index, "Int", value)
    @JvmName("eventBoolean") fun event(index: Int, value: Boolean) = fire(index, "Boolean", value)
    /** Any other callback parameter type: the event is refused. */
    @JvmName("eventUnsupported") fun event(index: Int, @Suppress("UNUSED_PARAMETER") value: Any?) = fire(index, UNSUPPORTED, null)

    /** [type] is null for a callback without parameters. Called on the main thread by the native component. */
    protected abstract fun fire(index: Int, type: String?, value: Any?)

    companion object { const val UNSUPPORTED = "?" }
}

/** The application's generated registry, or none. Never throws. */
object InstalledAdapters {
    val registry: NativeAdapterRegistry by lazy {
        try {
            dev.dootah.generated.DootahAdapterIndex.registry() as? NativeAdapterRegistry ?: NativeAdapterRegistry.Empty
        } catch (e: LinkageError) {
            NativeAdapterRegistry.Empty // Built without the Dootah Gradle plugin.
        } catch (e: RuntimeException) {
            NativeAdapterRegistry.Empty // A malformed generated index fails closed to native.
        }
    }
}

object NativeAdapterHost {
    private val SCALARS = mapOf<String, (Any) -> Boolean>(
        "String" to { it is String }, "Int" to { it is Int }, "Boolean" to { it is Boolean })

    /** True only for an installed adapter whose declared signature matches exactly. */
    fun installed(registry: NativeAdapterRegistry, id: String, signature: String): Boolean =
        registry.signatures[id] == signature

    /** True only for an installed adapter whose scalar values, content slot count and callback count match exactly. */
    fun accepts(registry: NativeAdapterRegistry, id: String, arguments: List<Any?>, slots: Int = 0, events: Int = 0): Boolean {
        val signature = registry.signatures[id] ?: return false
        val tokens = if (signature.isEmpty()) emptyList() else signature.split(',')
        val types = tokens.filter { !it.startsWith("@") }
        // Markers follow the data types: content slots, then callbacks.
        if (tokens.take(types.size) != types) return false
        if (tokens.count { it == CONTENT || it.startsWith(SLOT) } != slots || tokens.count { it == EVENT } != events) return false
        if (types.size != arguments.size) return false
        return types.indices.all { i ->
            val type = types[i]
            val value = arguments[i]
            if (type.endsWith("?")) value == null || SCALARS[type.dropLast(1)]?.invoke(value) == true
            else value != null && SCALARS[type]?.invoke(value) == true
        }
    }

    /** Renders the adapter in the current composition. Returns false, composing nothing, when refused. */
    @Composable
    fun render(registry: NativeAdapterRegistry, id: String, call: NativeCall, slots: Int = 0, events: Int = 0): Boolean {
        if (!accepts(registry, id, call.arguments, slots, events)) return false
        registry.render(id, call)
        return true
    }

    const val CONTENT = "@content"
    const val SLOT = "@slot:"
    const val EVENT = "@event"
}
