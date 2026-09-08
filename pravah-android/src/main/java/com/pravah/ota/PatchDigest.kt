package com.pravah.ota

import java.security.MessageDigest

/**
 * Lowercase hex SHA-256 of [payload].
 *
 * Always taken over the bytes exactly as received. Hashing a decoded and
 * re-encoded string would make any BOM or non-UTF-8 byte produce a digest that
 * disagrees with the publisher's for reasons no log could explain.
 */
internal fun sha256Hex(payload: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { byte -> "%02x".format(byte) }
