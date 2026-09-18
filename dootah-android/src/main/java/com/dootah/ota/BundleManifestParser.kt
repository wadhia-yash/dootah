package com.dootah.ota

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The only manifest schema this runtime understands.
 *
 * Separate from [DOOTAH_RUNTIME_VERSION]: the schema version describes the shape
 * of the manifest document, while the runtime version describes the bundle code
 * contract. A manifest can gain a new schema without bundles becoming
 * incompatible, and vice versa.
 */
const val MANIFEST_SCHEMA_VERSION: Int = 1

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

private const val REQUIRED_URL_SCHEME = "https://"

/**
 * Turns manifest JSON into a validated [BundleManifest].
 *
 * Every rejection path throws [BundleManifestException] naming the offending
 * field, because the alternative -- a permissive parse with defaulted values --
 * would let a manifest that is missing its compatibility gate be treated as
 * though it had passed one.
 *
 * This is a pure function over a string. It performs no I/O and touches no
 * Android APIs, so all of its behaviour is covered by ordinary unit tests.
 */
object BundleManifestParser {

    fun parse(json: String): BundleManifest {

        val root = readRootObject(json)

        rejectLegacySchema(root)

        val schemaVersion = root.requireInt("schemaVersion")

        // Fail closed on any schema this build was not written against, including
        // a newer one: an unknown schema may move the meaning of a field we do
        // recognise, and guessing is not an option for code we are about to run.
        if (schemaVersion != MANIFEST_SCHEMA_VERSION) {
            throw BundleManifestException(
                "Unsupported manifest schemaVersion $schemaVersion, " +
                    "this runtime supports $MANIFEST_SCHEMA_VERSION"
            )
        }

        val bundleVersion = root.requireInt("bundleVersion")

        if (bundleVersion < 1) {
            throw BundleManifestException(
                "Manifest field 'bundleVersion' must be positive but was $bundleVersion"
            )
        }

        return BundleManifest(
            schemaVersion = schemaVersion,
            bundleVersion = bundleVersion,
            runtimeVersion = root.requireString("runtimeVersion"),
            enabled = root.requireBoolean("enabled"),
            url = root.requireBundleUrl(),
            sha256 = root.requireSha256(),
            signature = root.optionalString("signature"),
            appId = root.optionalString("appId"),
            images = root.readImages(),
        )
    }

    private fun readRootObject(json: String): JsonObject {

        val element = try {
            Json.parseToJsonElement(json)
        } catch (cause: IllegalArgumentException) {
            // SerializationException extends IllegalArgumentException.
            throw BundleManifestException("Manifest is not valid JSON", cause)
        }

        return try {
            element.jsonObject
        } catch (cause: IllegalArgumentException) {
            throw BundleManifestException("Manifest root must be a JSON object", cause)
        }
    }

    /**
     * Rejects the pre-schema manifest shape with a message that says how to fix
     * it, rather than the bare "missing field 'bundleVersion'" a generic parser
     * would produce.
     */
    private fun rejectLegacySchema(root: JsonObject) {

        val looksLegacy = !root.containsKey("bundleVersion") &&
            !root.containsKey("schemaVersion") &&
            root.containsKey("version")

        if (looksLegacy) {
            throw BundleManifestException(
                "Legacy manifest schema rejected. Replace 'version' with " +
                    "'bundleVersion' and add 'schemaVersion': $MANIFEST_SCHEMA_VERSION, " +
                    "'runtimeVersion': \"$DOOTAH_RUNTIME_VERSION\" and 'enabled': true"
            )
        }
    }
}

private fun JsonObject.requirePrimitive(field: String): JsonPrimitive {

    val value = this[field]
        ?: throw BundleManifestException("Manifest is missing required field '$field'")

    return value as? JsonPrimitive
        ?: throw BundleManifestException("Manifest field '$field' must be a scalar value")
}

private fun JsonObject.requireInt(field: String): Int {

    val primitive = requirePrimitive(field)

    if (primitive.isString) {
        throw BundleManifestException(
            "Manifest field '$field' must be a number, not a quoted string"
        )
    }

    return primitive.content.toIntOrNull()
        ?: throw BundleManifestException(
            "Manifest field '$field' is not an integer: '${primitive.content}'"
        )
}

private fun JsonObject.requireBoolean(field: String): Boolean {

    val primitive = requirePrimitive(field)

    if (primitive.isString) {
        throw BundleManifestException(
            "Manifest field '$field' must be a bare true or false, not a quoted string"
        )
    }

    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> throw BundleManifestException(
            "Manifest field '$field' must be true or false but was '${primitive.content}'"
        )
    }
}

private fun JsonObject.requireString(field: String): String {

    val primitive = requirePrimitive(field)

    if (!primitive.isString) {
        throw BundleManifestException(
            "Manifest field '$field' must be a quoted string"
        )
    }

    if (primitive.content.isBlank()) {
        throw BundleManifestException("Manifest field '$field' must not be blank")
    }

    return primitive.content
}

private fun JsonObject.optionalString(field: String): String? {

    if (!containsKey(field)) return null

    return requireString(field)
}

/** HTTPS remains required in addition to publisher authentication and payload hashes. */
private fun JsonObject.requireBundleUrl(): String {

    val url = requireString("url")

    if (!url.startsWith(REQUIRED_URL_SCHEME)) {
        throw BundleManifestException(
            "Manifest field 'url' must use HTTPS but was '$url'"
        )
    }

    return url
}

/** Normalises to lowercase so an uppercase digest does not fail a valid bundle. */
private fun JsonObject.requireSha256(): String {

    val digest = requireString("sha256").lowercase()

    if (!SHA256_HEX.matches(digest)) {
        throw BundleManifestException(
            "Manifest field 'sha256' must be 64 hex characters but was '$digest'"
        )
    }

    return digest
}

private fun JsonObject.readImages(): List<BundleImage> {
    val value = this["images"] ?: return emptyList()
    val entries = value as? kotlinx.serialization.json.JsonArray
        ?: throw BundleManifestException("Manifest images must be an array")
    if (entries.size > 128) throw BundleManifestException("Too many images (maximum 128)")
    val images = entries.map { element ->
        val obj = element as? JsonObject
            ?: throw BundleManifestException("Each image must be an object")
        val hash = obj.requireSha256()
        val id = obj.requireString("id")
        if (id != hash) throw BundleManifestException("Image id must equal its SHA-256")
        BundleImage(id, obj.requireBundleUrl(), hash)
    }
    if (images.map { it.id }.distinct().size != images.size) {
        throw BundleManifestException("Duplicate image id")
    }
    return images
}
