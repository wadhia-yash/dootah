package com.pravah.ota

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The only manifest schema this runtime understands.
 *
 * Separate from [PRAVAH_RUNTIME_VERSION]: the schema version describes the shape
 * of the manifest document, while the runtime version describes the patch code
 * contract. A manifest can gain a new schema without patches becoming
 * incompatible, and vice versa.
 */
const val MANIFEST_SCHEMA_VERSION: Int = 1

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

private const val REQUIRED_URL_SCHEME = "https://"

/**
 * Turns manifest JSON into a validated [PatchManifest].
 *
 * Every rejection path throws [PatchManifestException] naming the offending
 * field, because the alternative -- a permissive parse with defaulted values --
 * would let a manifest that is missing its compatibility gate be treated as
 * though it had passed one.
 *
 * This is a pure function over a string. It performs no I/O and touches no
 * Android APIs, so all of its behaviour is covered by ordinary unit tests.
 */
object PatchManifestParser {

    fun parse(json: String): PatchManifest {

        val root = readRootObject(json)

        rejectLegacySchema(root)

        val schemaVersion = root.requireInt("schemaVersion")

        // Fail closed on any schema this build was not written against, including
        // a newer one: an unknown schema may move the meaning of a field we do
        // recognise, and guessing is not an option for code we are about to run.
        if (schemaVersion != MANIFEST_SCHEMA_VERSION) {
            throw PatchManifestException(
                "Unsupported manifest schemaVersion $schemaVersion, " +
                    "this runtime supports $MANIFEST_SCHEMA_VERSION"
            )
        }

        val patchVersion = root.requireInt("patchVersion")

        if (patchVersion < 1) {
            throw PatchManifestException(
                "Manifest field 'patchVersion' must be positive but was $patchVersion"
            )
        }

        return PatchManifest(
            schemaVersion = schemaVersion,
            patchVersion = patchVersion,
            runtimeVersion = root.requireString("runtimeVersion"),
            enabled = root.requireBoolean("enabled"),
            url = root.requirePatchUrl(),
            sha256 = root.requireSha256(),
            signature = root.optionalString("signature"),
        )
    }

    private fun readRootObject(json: String): JsonObject {

        val element = try {
            Json.parseToJsonElement(json)
        } catch (cause: IllegalArgumentException) {
            // SerializationException extends IllegalArgumentException.
            throw PatchManifestException("Manifest is not valid JSON", cause)
        }

        return try {
            element.jsonObject
        } catch (cause: IllegalArgumentException) {
            throw PatchManifestException("Manifest root must be a JSON object", cause)
        }
    }

    /**
     * Rejects the pre-schema manifest shape with a message that says how to fix
     * it, rather than the bare "missing field 'patchVersion'" a generic parser
     * would produce.
     */
    private fun rejectLegacySchema(root: JsonObject) {

        val looksLegacy = !root.containsKey("patchVersion") &&
            !root.containsKey("schemaVersion") &&
            root.containsKey("version")

        if (looksLegacy) {
            throw PatchManifestException(
                "Legacy manifest schema rejected. Replace 'version' with " +
                    "'patchVersion' and add 'schemaVersion': $MANIFEST_SCHEMA_VERSION, " +
                    "'runtimeVersion': \"$PRAVAH_RUNTIME_VERSION\" and 'enabled': true"
            )
        }
    }
}

private fun JsonObject.requirePrimitive(field: String): JsonPrimitive {

    val value = this[field]
        ?: throw PatchManifestException("Manifest is missing required field '$field'")

    return value as? JsonPrimitive
        ?: throw PatchManifestException("Manifest field '$field' must be a scalar value")
}

private fun JsonObject.requireInt(field: String): Int {

    val primitive = requirePrimitive(field)

    if (primitive.isString) {
        throw PatchManifestException(
            "Manifest field '$field' must be a number, not a quoted string"
        )
    }

    return primitive.content.toIntOrNull()
        ?: throw PatchManifestException(
            "Manifest field '$field' is not an integer: '${primitive.content}'"
        )
}

private fun JsonObject.requireBoolean(field: String): Boolean {

    val primitive = requirePrimitive(field)

    if (primitive.isString) {
        throw PatchManifestException(
            "Manifest field '$field' must be a bare true or false, not a quoted string"
        )
    }

    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> throw PatchManifestException(
            "Manifest field '$field' must be true or false but was '${primitive.content}'"
        )
    }
}

private fun JsonObject.requireString(field: String): String {

    val primitive = requirePrimitive(field)

    if (!primitive.isString) {
        throw PatchManifestException(
            "Manifest field '$field' must be a quoted string"
        )
    }

    if (primitive.content.isBlank()) {
        throw PatchManifestException("Manifest field '$field' must not be blank")
    }

    return primitive.content
}

private fun JsonObject.optionalString(field: String): String? {

    if (!containsKey(field)) return null

    return requireString(field)
}

/**
 * Requires HTTPS for the payload. Until publisher signatures land, TLS is the
 * only thing binding a patch to the party allowed to publish it, and the digest
 * in the manifest cannot help because an attacker who can rewrite the payload
 * over plaintext can rewrite the manifest too.
 */
private fun JsonObject.requirePatchUrl(): String {

    val url = requireString("url")

    if (!url.startsWith(REQUIRED_URL_SCHEME)) {
        throw PatchManifestException(
            "Manifest field 'url' must use HTTPS but was '$url'"
        )
    }

    return url
}

/** Normalises to lowercase so an uppercase digest does not fail a valid patch. */
private fun JsonObject.requireSha256(): String {

    val digest = requireString("sha256").lowercase()

    if (!SHA256_HEX.matches(digest)) {
        throw PatchManifestException(
            "Manifest field 'sha256' must be 64 hex characters but was '$digest'"
        )
    }

    return digest
}
