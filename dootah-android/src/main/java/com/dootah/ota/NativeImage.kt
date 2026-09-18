package com.dootah.ota

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** PNG and JPEG only; decoded by Android, rendered by Compose's BitmapPainter. */
internal fun decodeNativeImage(bytes: ByteArray): Bitmap {
    val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
        byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    )
    if (png) validatePng(bytes)
    val jpeg = bytes.size >= 4 && bytes[0] == 255.toByte() && bytes[1] == 216.toByte() &&
        bytes[bytes.size - 2] == 255.toByte() && bytes.last() == 217.toByte()
    require(png || jpeg) { "Only PNG and JPEG images are supported" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
        bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "Invalid image dimensions" }
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Image decode failed" }
}

/** Android's decoder can accept a truncated PNG. Require all chunks and CRCs first. */
private fun validatePng(bytes: ByteArray) {
    val buffer = java.nio.ByteBuffer.wrap(bytes)
    var offset = 8
    var ended = false
    while (offset + 12 <= bytes.size) {
        val length = buffer.getInt(offset)
        require(length >= 0 && length.toLong() + offset + 12 <= bytes.size) { "Truncated PNG" }
        val crc = java.util.zip.CRC32().apply { update(bytes, offset + 4, length + 4) }
        require(crc.value.toInt() == buffer.getInt(offset + 8 + length)) { "Corrupt PNG chunk" }
        val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
        offset += length + 12
        if (type == "IEND") { require(length == 0); ended = true; break }
    }
    require(ended && offset == bytes.size) { "Incomplete PNG" }
}
