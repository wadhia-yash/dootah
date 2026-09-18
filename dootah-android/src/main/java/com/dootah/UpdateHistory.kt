package com.dootah

/** Local lifecycle evidence; timestamps are device wall-clock milliseconds. */
data class UpdateHistoryEvent(
    val timestampMillis: Long,
    val kind: UpdateEventKind,
    val bundleVersion: Int?,
    val identity: String?,
    val contentHash: String?,
    val reason: String = "",
    val previousVersion: Int? = null,
    val previousIdentity: String? = null,
)

enum class UpdateEventKind {
    CANDIDATE, ACTIVE, HEALTHY, FAILED_QUARANTINED,
    AUTOMATIC_ROLLBACK, MANUAL_ROLLBACK, UPDATES_RESUMED,
}

data class RetainedHealthyUpdate(val bundleVersion: Int, val identity: String, val contentHash: String)

data class DootahUpdateHistory(
    val events: List<UpdateHistoryEvent>,
    val retainedHealthyUpdates: List<RetainedHealthyUpdate>,
    val updatesPaused: Boolean,
)

sealed interface ManualRollbackResult {
    data class Applied(val bundleVersion: Int) : ManualRollbackResult
    data class Rejected(val reason: String) : ManualRollbackResult
}
