package dev.dootah.runtime

/** Phase 4's bounded constant-text contract; deliberately not a Portable IR. */
internal object DispatchPayload {
    private val identity = Regex("dth1:[0-9a-f]{64}")
    fun parse(message: Map<*, *>): Map<String, String>? {
        if (message.keys != setOf("type", "abi", "overrides") ||
            message["type"] != "dootah.dispatch.v1" || (message["abi"] as? Number)?.toDouble() != 1.0) return null
        val entries = message["overrides"] as? List<*> ?: return null
        if (entries.size !in 1..32) return null
        val result = linkedMapOf<String, String>()
        for (item in entries) {
            val entry = item as? Map<*, *> ?: return null
            if (entry.keys != setOf("functionId", "title")) return null
            val id = entry["functionId"] as? String ?: return null
            val title = entry["title"] as? String ?: return null
            if (!identity.matches(id) || title.isBlank() || title.length > 256 || result.put(id, title) != null) return null
        }
        return result.toMap()
    }
}
