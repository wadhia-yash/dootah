package dev.dootah.gradle.internal

import dev.dootah.contract.BundleImages
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.imageio.ImageIO

internal data class PackagedImage(val resource: String, val bytes: ByteArray, val hash: String)

/** Only nodpi, single-file PNG/JPEG drawables in this module.
 * Qualified resources and XML retain Android's resource selection and stay in the APK.
 * Operates on compiler-generated painter tokens, never on application Kotlin source.
 */
internal object BundleImagePackaging {
    private val painter = Regex("""PainterResourceProp\("(drawable:[a-zA-Z0-9_]+)"\)""")

    fun collect(sources: List<File>, resources: Collection<File>): List<PackagedImage> {
        val keys = sources.flatMap { painter.findAll(it.readText()).map { it.groupValues[1] }.toList() }.toSet()
        return keys.sorted().mapNotNull { key ->
            val candidates = resources.filter {
                it.parentFile.name.startsWith("drawable") && it.name.substringBefore('.') == key.substringAfter(':')
            }
            val file = candidates.singleOrNull() ?: return@mapNotNull null
            if (file.parentFile.name != "drawable-nodpi" ||
                file.extension.lowercase() !in setOf("png", "jpg", "jpeg")) return@mapNotNull null
            val bytes = file.readBytes()
            require(bytes.size <= 8 * 1024 * 1024) { "Image too large: $file" }
            val image = requireNotNull(ImageIO.read(bytes.inputStream())) { "Corrupt image: $file" }
            require(image.width.toLong() * image.height <= 16_000_000) { "Image dimensions too large: $file" }
            PackagedImage(key, bytes, sha256(bytes))
        }
    }

    fun rewrite(source: String, images: List<PackagedImage>): String {
        val hashes = images.associate { it.resource to it.hash }
        return painter.replace(source) { match ->
            hashes[match.groupValues[1]]?.let { "PainterResourceProp(\"${BundleImages.PREFIX}$it\")" }
                ?: match.value
        }
    }

    fun imageUrl(bundleUrl: String, hash: String): String {
        val uri = URI(bundleUrl)
        require(uri.scheme == "https" && uri.host != null) { "bundleUrl must use HTTPS" }
        return uri.resolve("images/$hash").toASCIIString()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
