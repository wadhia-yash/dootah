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
    /** Adapter id -> comma-separated parameter types ("String,Int?"); the same notation as hook inputs. */
    val signatures: Map<String, String>

    /**
     * Called only by [NativeAdapterHost] after the id and every value were checked against [signatures].
     * [content] is present exactly when the signature ends with `@content` (a container adapter).
     */
    @Composable
    fun render(id: String, arguments: List<Any?>, content: NativeContent?)

    object Empty : NativeAdapterRegistry {
        override val signatures: Map<String, String> = emptyMap()
        @Composable
        override fun render(id: String, arguments: List<Any?>, content: NativeContent?) = error("No native adapters are installed")
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
        override fun render(id: String, arguments: List<Any?>, content: NativeContent?) = owners.getValue(id).render(id, arguments, content)
    }
}

/**
 * The ordered children of a container adapter, selected by the update. A generated container calls
 * [render] inside its installed content lambda and supplies [child], which dispatches each child id to
 * the installed copy of that call, so the children keep the content lambda's receiver and composition.
 */
interface NativeContent {
    @Composable
    fun render(child: @Composable (id: String, arguments: List<Any?>) -> Unit)
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

    /** True only for an installed adapter whose arity, scalar types and content form match exactly. */
    fun accepts(registry: NativeAdapterRegistry, id: String, arguments: List<Any?>, content: Boolean = false): Boolean {
        val signature = registry.signatures[id] ?: return false
        var types = if (signature.isEmpty()) emptyList() else signature.split(',')
        if ((types.lastOrNull() == CONTENT) != content) return false
        if (content) types = types.dropLast(1)
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
    fun render(registry: NativeAdapterRegistry, id: String, arguments: List<Any?>, content: NativeContent? = null): Boolean {
        if (!accepts(registry, id, arguments, content != null)) return false
        registry.render(id, arguments, content)
        return true
    }

    const val CONTENT = "@content"
}
