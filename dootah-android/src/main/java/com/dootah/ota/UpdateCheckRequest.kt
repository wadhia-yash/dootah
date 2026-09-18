package com.dootah.ota

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Explicit native configuration only; channel is control-plane routing, not a JS capability. */
internal class UpdateCheckRequest(
    val appId: String,
    val runtimeVersion: String,
    val appVersion: Long,
    val channel: String,
    private val installationId: () -> String,
) {
    init {
        require(channel in setOf("development", "staging", "production")) { "Unknown update channel" }
        require(appId.matches(Regex("[A-Za-z0-9_.-]{1,200}")))
        require(runtimeVersion.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && appVersion > 0)
    }
    // Separate update ordering, retained targets, recovery, quarantine and pause across configurations.
    val cacheScope: String = sha256Hex("$appId\n$runtimeVersion\n$appVersion\n$channel".toByteArray())

    fun url(endpoint: String): String {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && uri.host != null && uri.rawQuery == null && uri.fragment == null && uri.userInfo == null)
        val id = installationId()
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val fields = linkedMapOf("appId" to appId, "runtimeVersion" to runtimeVersion,
            "appVersion" to appVersion.toString(), "channel" to channel, "installationId" to id)
        return endpoint + "?" + fields.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    fun manifest(response: String): String {
        try {
            val root = Json.parseToJsonElement(response) as? JsonObject ?: error("Expected object")
            val returnedChannel = root["channel"] as? JsonPrimitive ?: error("Missing channel")
            require(returnedChannel.isString && returnedChannel.content == channel) { "Wrong response channel" }
            return (root["manifest"] as? JsonObject ?: error("Missing manifest")).toString()
        } catch (e: Exception) { throw BundleManifestException("Invalid update-check response", e) }
    }
}

/** Stored under Context.noBackupFilesDir. A broken identity fails closed instead of re-rolling. */
internal object InstallationIdentity {
    @Synchronized fun readOrCreate(directory: File): String {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create installation storage" }
        return RandomAccessFile(File(directory, "installation.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val file = File(directory, "installation-id")
                if (file.exists()) {
                    require(file.length() == 36L) { "Invalid persisted installation ID" }
                    file.readText().also { value -> require(UUID.fromString(value).toString() == value) }
                } else {
                    val id = UUID.randomUUID().toString()
                    val pending = File(directory, "installation-id.tmp")
                    FileOutputStream(pending).use { out -> out.write(id.toByteArray()); out.fd.sync() }
                    check(pending.renameTo(file)) { "Cannot persist installation ID" }
                    id
                }
            }
        }
    }
}
