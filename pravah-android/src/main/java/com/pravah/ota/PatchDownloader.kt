package com.pravah.ota

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val DOWNLOAD_CHUNK_BYTES = 16 * 1024

private const val REQUIRED_URL_SCHEME = "https://"

/**
 * Fetches bytes over HTTPS under a strict time and size budget.
 *
 * Split out from the update logic so the network policy -- HTTPS only, bounded
 * time, bounded size -- is stated in exactly one place.
 */
internal class PatchDownloader(
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
) {

    fun download(url: String, maxBytes: Int): ByteArray {

        requireHttps(url)

        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
            }
        } catch (cause: IOException) {
            throw PatchDownloadException("Could not open connection to $url", cause)
        }

        try {
            val status = connection.responseCode

            if (status != HttpURLConnection.HTTP_OK) {
                throw PatchDownloadException("Request for $url returned HTTP $status")
            }

            // The declared length is only used to reject early. The read below is
            // capped regardless, because this header is attacker-controlled.
            val declaredLength = connection.contentLength

            if (declaredLength > maxBytes) {
                throw PatchDownloadException(
                    "$url declares $declaredLength bytes, over the $maxBytes byte limit"
                )
            }

            return connection.inputStream.use { stream ->
                stream.readAtMost(maxBytes, url)
            }
        } catch (cause: IOException) {
            throw PatchDownloadException("Could not read $url", cause)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Until publisher signatures land, TLS is the only thing binding a patch to the
 * party allowed to publish it. The digest cannot help: an attacker who can
 * rewrite the payload over plaintext can rewrite the manifest that describes it.
 */
private fun requireHttps(url: String) {

    if (!url.startsWith(REQUIRED_URL_SCHEME)) {
        throw PatchDownloadException("Pravah requires HTTPS but was given '$url'")
    }
}

/**
 * Reads the stream, refusing to buffer more than [maxBytes]. A response with no
 * Content-Length, or a dishonest one, would otherwise be read until the process
 * died.
 */
private fun InputStream.readAtMost(maxBytes: Int, url: String): ByteArray {

    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(DOWNLOAD_CHUNK_BYTES)

    while (true) {
        val bytesRead = read(chunk)

        if (bytesRead == -1) break

        if (buffer.size() + bytesRead > maxBytes) {
            throw PatchDownloadException(
                "Payload from $url exceeds the $maxBytes byte limit"
            )
        }

        buffer.write(chunk, 0, bytesRead)
    }

    return buffer.toByteArray()
}
