package com.dootah.operator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.dootah.DOOTAH_LOG_TAG
import com.dootah.Dootah
import com.dootah.ManualRollbackResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional adb operator surface. Register ONLY in the host's debug manifest,
 * exported with android:permission="android.permission.DUMP" (shell/system).
 * Not registered by the library; never reachable through remote JavaScript.
 */
class DootahOperatorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                when (intent.getStringExtra("command")) {
                    "rollback" -> when (val result = Dootah.rollbackTo(intent.getIntExtra("version", -1),
                        intent.getStringExtra("reason") ?: "operator_request")) {
                        is ManualRollbackResult.Applied -> Log.i(DOOTAH_LOG_TAG, "OPERATOR rollback applied: ${result.bundleVersion}")
                        is ManualRollbackResult.Rejected -> Log.w(DOOTAH_LOG_TAG, "OPERATOR rollback rejected: ${result.reason}")
                    }
                    "resume" -> Log.i(DOOTAH_LOG_TAG, "OPERATOR resume: ${Dootah.resumeUpdates()}")
                    "history" -> {
                        val history = Dootah.updateHistory()
                        Log.i(DOOTAH_LOG_TAG, "OPERATOR history paused=${history.updatesPaused}; retained=" +
                            JSONArray(history.retainedHealthyUpdates.map { JSONObject().put("version", it.bundleVersion)
                                .put("identity", it.identity).put("hash", it.contentHash) }))
                        // One bounded line per event avoids logcat's per-line truncation.
                        history.events.forEach { event ->
                            Log.i(DOOTAH_LOG_TAG, "OPERATOR event " + JSONObject()
                                .put("time", event.timestampMillis).put("kind", event.kind.name)
                                .put("version", event.bundleVersion).put("identity", event.identity)
                                .put("hash", event.contentHash).put("reason", event.reason)
                                .put("fromVersion", event.previousVersion).put("fromIdentity", event.previousIdentity))
                        }
                    }
                    else -> Log.w(DOOTAH_LOG_TAG, "OPERATOR expected rollback, resume or history")
                }
            } catch (e: Exception) {
                Log.w(DOOTAH_LOG_TAG, "OPERATOR request failed", e)
            } finally { pending.finish() }
        }
    }
}
