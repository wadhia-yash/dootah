package dev.dootah.runtime

/**
 * Expo's state-change events carry only a generic loader message ("Failed to download remote
 * update"), so code-signing rejections cannot be classified from them. The updater's persisted
 * log records the structured UpdateCodeSigningError code, written only by its manifest and
 * directive signature checks.
 */
internal object VerificationSignal {
    // Entries are UpdatesLogEntry JSON objects; quotes inside values are escaped, so a message
    // cannot forge an unescaped field. Non-JSON cause/stack lines never start with '{'.
    private val code = Regex("\"code\":\"UpdateCodeSigningError\"")
    private val timestamp = Regex("\"timestamp\":(\\d+)")

    /** Timestamp of the newest code-signing rejection logged strictly after [after], or null. */
    fun newestRejection(entries: List<String>, after: Long): Long? = entries
        .filter { it.startsWith("{") && code.containsMatchIn(it) }
        .mapNotNull { timestamp.find(it)?.groupValues?.get(1)?.toLongOrNull() }
        .filter { it > after }
        .maxOrNull()
}
