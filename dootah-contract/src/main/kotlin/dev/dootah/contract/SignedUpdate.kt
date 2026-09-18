package dev.dootah.contract

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** The publisher and device encode parsed values, never raw JSON, for Ed25519. */
data class SignedUpdate(
    val schemaVersion: Int,
    val appId: String,
    val runtimeVersion: String,
    val bundleVersion: Int,
    val enabled: Boolean,
    val url: String,
    val sha256: String,
    val images: List<SignedImage>,
) {
    /**
     * Domain/version, then fields in declaration order. Integers are signed 32-bit
     * big endian; strings are UTF-8 prefixed by byte length; Boolean is one byte.
     * Images are sorted by ID and prefixed by count. No locale or JSON dependence.
     * Unknown future behavior requires a new encoding version, not unsigned fields.
     */
    fun signingBytes(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            fun string(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            string("Dootah.Ed25519.Manifest.v1")
            out.writeInt(schemaVersion)
            string(appId)
            string(runtimeVersion)
            out.writeInt(bundleVersion)
            out.writeBoolean(enabled)
            string(url)
            string(sha256.lowercase())
            out.writeInt(images.size)
            images.sortedBy { it.id }.forEach {
                string(it.id)
                string(it.url)
                string(it.sha256.lowercase())
            }
        }
    }.toByteArray()

    /** Identical wire writer for tooling and persistence. Signature is not signed. */
    fun manifestJson(signature: String?): String = buildString {
        append("{\"schemaVersion\":$schemaVersion,\"appId\":${quote(appId)},")
        append("\"runtimeVersion\":${quote(runtimeVersion)},\"bundleVersion\":$bundleVersion,")
        append("\"enabled\":$enabled,\"url\":${quote(url)},\"sha256\":${quote(sha256)},")
        append("\"images\":[")
        append(images.sortedBy { it.id }.joinToString(",") {
            "{\"id\":${quote(it.id)},\"url\":${quote(it.url)},\"sha256\":${quote(it.sha256)}}"
        })
        append(']')
        if (signature != null) append(",\"signature\":${quote(signature)}")
        append("}\n")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch.code < 32 -> append("\\u" + ch.code.toString(16).padStart(4, '0'))
                else -> append(ch)
            }
        }
        append('"')
    }
}

data class SignedImage(val id: String, val url: String, val sha256: String)
