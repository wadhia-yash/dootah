package dev.dootah.runtime

import android.content.Context
import expo.modules.updates.EnabledUpdatesController
import expo.modules.updates.logging.UpdatesLogReader
import expo.modules.updatesinterface.UpdatesStateChangeListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Trusted native telemetry. Never reachable by a portable program. */
internal class CloudTelemetry(private val context: Context, private val controller: EnabledUpdatesController) {
    private val preferences = context.getSharedPreferences("dev.dootah.telemetry", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val activation = UUID.randomUUID().toString()
    // Constructed before the updater starts, so every rejection of this process is after it.
    private val started = System.currentTimeMillis()
    private val installation = expo.modules.easclient.EASClientID(context).uuid.toString()
    private var downloaded: String? = null
    private val runtime = controller.runtimeVersion ?: ""
    private val origin = controller.updateUrl?.let { URI(it.toString()).let { u ->
        if (u.scheme == "https" || (u.scheme == "http" && u.host == "127.0.0.1")) "${u.scheme}://${u.rawAuthority}" else null
    } }
    private val queue = ArrayDeque<JSONObject>()

    init {
        executor.execute {
            try {
                val saved = JSONArray(preferences.getString("queue", "[]"))
                for (i in 0 until minOf(saved.length(), 64)) queue.add(saved.getJSONObject(i))
            } catch (_: Exception) { queue.clear() }
        }
        controller.subscribeToUpdatesStateChanges(object : UpdatesStateChangeListener {
            override fun updatesStateDidChange(event: Map<String, Any>) {
                val update = (event["manifest"] as? Map<*, *>)?.get("id") as? String
                when (event["type"]) {
                    "check" -> emit("update_checked", null)
                    "checkCompleteWithUpdate" -> { downloaded = update; emit("update_available", update) }
                    "download" -> if (downloaded != null) emit("download_started", downloaded)
                    "downloadCompleteWithUpdate" -> emit("download_completed", update)
                    "checkCompleteWithRollback" -> executor.execute { preferences.edit().putBoolean("rollbackPending", true).apply() }
                    // Generic network errors are not signature-verification failures; only the
                    // updater's structured code-signing log entry classifies one. It is appended
                    // asynchronously before the error propagates, so read it shortly afterwards.
                    "checkError", "downloadError" -> executor.schedule(Runnable { reportVerificationFailure() }, 1, TimeUnit.SECONDS)
                }
            }
        })
        executor.scheduleWithFixedDelay({ flush() }, 2, 10, TimeUnit.SECONDS)
    }

    fun enroll(ticket: String, result: (Boolean) -> Unit) {
        if (!ticket.matches(Regex("[A-Za-z0-9_-]{43}"))) { result(false); return }
        executor.execute {
            val ok = try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                val response = send("/v1/enroll", ticket, JSONObject()
                    .put("installationId", installation).put("runtime", runtime)
                    .put("nativeVersion", info.versionName ?: "unknown").put("sdkVersion", BuildConfig.DOOTAH_SDK_VERSION)
                    .put("embeddedId", controller.embeddedUpdateId.toString()))
                val credential = response.getString("credential")
                require(credential.matches(Regex("[A-Za-z0-9_-]{43}")))
                preferences.edit().putString("credential", credential).commit()
                flush(); true
            } catch (_: Exception) { false }
            android.os.Handler(context.mainLooper).post { result(ok) }
        }
    }

    fun selected(id: UUID, embedded: Boolean) {
        emit("update_activated", id.toString())
        executor.execute {
            if (embedded && preferences.getBoolean("rollbackPending", false)) {
                val previous = preferences.getString("lastRemote", null)
                emit("rollback_applied", previous)
                preferences.edit().remove("rollbackPending").remove("lastRemote").apply()
            }
            if (!embedded) preferences.edit().putString("lastRemote", id.toString()).apply()
        }
    }
    fun healthy(id: UUID) = emit("update_healthy", id.toString())
    fun rejected(id: UUID?) { emit("update_rejected", id?.toString()); emit("native_fallback", id?.toString()) }

    private fun reportVerificationFailure() {
        try {
            val seen = maxOf(started, preferences.getLong("verificationSeen", 0))
            val entries = UpdatesLogReader(context.filesDir).getLogEntries(java.util.Date(seen))
            val newest = VerificationSignal.newestRejection(entries, seen) ?: return
            preferences.edit().putLong("verificationSeen", newest).apply()
            emit("verification_failed", null)
        } catch (_: Exception) { /* Reporting cannot change native recovery. */ }
    }

    private fun emit(type: String, id: String?) {
        executor.execute {
            try {
                if (queue.size >= 64) queue.removeFirst()
                queue.add(JSONObject().put("id", UUID.randomUUID().toString()).put("installationId", installation)
                    .put("updateId", id ?: JSONObject.NULL).put("type", type).put("activationId", activation)
                    .put("timestamp", java.time.Instant.now().toString()).put("runtimeAbi", 2).put("logicAbi", 1)
                    .put("metadata", JSONObject()))
                persist()
            } catch (_: Exception) { /* Reporting cannot change native recovery. */ }
        }
    }
    private fun persist() { preferences.edit().putString("queue", JSONArray(queue.toList()).toString()).apply() }
    private fun flush() {
        try {
            val credential = preferences.getString("credential", null) ?: return
            if (queue.isEmpty()) return
            val batch = queue.take(32)
            send("/v1/events", credential, JSONObject().put("events", JSONArray(batch)))
            repeat(batch.size) { queue.removeFirst() }; persist()
        } catch (_: Exception) { /* Bounded persistent queue; retry later, no UI or launch dependency. */ }
    }
    private fun send(path: String, credential: String, body: JSONObject): JSONObject {
        val base = origin ?: error("No secure telemetry origin")
        val connection = java.net.URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000; connection.readTimeout = 3000
            connection.setRequestProperty("Authorization", "Bearer $credential")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 32768)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            require(connection.responseCode in 200..299)
            val bytesIn = connection.inputStream.use { it.readBytesBounded(8192) }
            return JSONObject(String(bytesIn, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= max); output.write(buffer, 0, n) }
        return output.toByteArray()
    }
}
