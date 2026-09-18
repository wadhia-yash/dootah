package dev.dootah.gradle.internal

import dev.dootah.contract.SignedUpdate
import dev.dootah.contract.SignedImage
import dev.dootah.contract.BundleImages
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.core.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Only signed metadata and named payloads cross the network. No key parameter exists here. */
internal class PublicationClient(
    server: String,
    private val token: String,
    private val transport: (String, String, ByteArray, String) -> ByteArray = ::request,
) {
    private val server = server.trimEnd('/')
    init { validateServer(this.server); require(token.length >= 32 && token.none { it.isISOControl() }) { "Set DOOTAH_PUBLISH_TOKEN (at least 32 characters)" } }

    fun publish(directory: File, channel: String, rollout: Int, min: Long? = null, max: Long? = null): Map<*, *> {
        validateControls(channel, rollout)
        require((min == null || min >= 1) && (max == null || max >= 1) && (min == null || max == null || min <= max))
        val manifest = json(directory.resolve("manifest.json").readBytes())
        require(manifest["signature"] is String) { "Signed manifest required" }
        val images = manifest["images"] as? List<*> ?: error("Missing images")
        val signed = SignedUpdate((manifest["schemaVersion"] as Number).toInt(), manifest["appId"] as String,
            manifest["runtimeVersion"] as String, (manifest["bundleVersion"] as Number).toInt(), manifest["enabled"] as Boolean,
            manifest["url"] as String, manifest["sha256"] as String, images.map {
                val image = it as Map<*, *>
                SignedImage(image["id"] as String, image["url"] as String, image["sha256"] as String)
            })
        val identity = BundleImagePackaging.sha256(signed.signingBytes())
        val artifacts = listOf(manifest) + images.map { it as Map<*, *> }
        val bytes = artifacts.mapIndexed { index, entry ->
            val hash = entry["sha256"] as String
            require(hash.matches(Regex("[0-9a-f]{64}")) && entry["url"] == "$server/artifacts/$hash") { "Unexpected artifact URL/hash" }
            val file = directory.resolve(if (index == 0) "bundle.js" else "images/$hash")
            require(file.length() in 1..8 * 1024 * 1024) { "Missing or oversized artifact" }
            file.readBytes().also { require(BundleImagePackaging.sha256(it) == hash) { "Artifact hash mismatch" } }
        }
        require(BundleImages.required(bytes.first().toString(Charsets.UTF_8)) == images.map { (it as Map<*, *>)["id"] }.toSet())
        artifacts.zip(bytes).forEach { (entry, payload) ->
            val hash = entry["sha256"] as String
            val receipt = json(transport("PUT", "$server/publish/artifacts/$hash", payload, token))
            require(receipt["sha256"] == hash && receipt["status"] == "stored") { "Invalid upload receipt" }
        }
        val metadata = linkedMapOf<String, Any>("channel" to channel, "rolloutPercent" to rollout, "paused" to false, "manifest" to manifest)
        min?.let { metadata["minAppVersion"] = it }; max?.let { metadata["maxAppVersion"] = it }
        val result = json(transport("POST", "$server/publish/releases", mapper.writeValueAsBytes(metadata), token))
        // An ambiguous response is an error, never a success. Retrying the same command resolves it.
        require(result["status"] in listOf("registered", "existing") && result["identity"] == identity) { "Invalid registration receipt; retry the same publication" }
        return result
    }
    companion object {
        private val mapper = ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        fun validateServer(server: String) {
            val uri = URI(server)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in listOf("", "/")) { "dootahServer must be an HTTPS origin" }
        }
        fun validateControls(channel: String, rollout: Int) {
            require(channel in setOf("development", "staging", "production") && rollout in 0..100) { "Explicit valid channel and rollout required" }
        }
        private fun json(bytes: ByteArray): Map<*, *> {
            require(bytes.size <= 65536) { "Oversized metadata" }
            return mapper.readValue(bytes, Map::class.java) ?: error("Invalid server metadata")
        }
        private fun request(method: String, url: String, bytes: ByteArray, token: String): ByteArray {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method; connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", if (method == "POST") "application/json" else "application/octet-stream")
                connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                check(connection.responseCode == 200) { "Publication failed: HTTP ${connection.responseCode}; retry the same command" }
                return connection.inputStream.use { it.readNBytes(65537) }.also { require(it.size <= 65536) { "Oversized server response" } }
            } finally { connection.disconnect() }
        }
    }
}
