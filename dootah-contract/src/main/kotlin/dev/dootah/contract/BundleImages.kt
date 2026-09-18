package dev.dootah.contract

/** Content-addressed raster painters. No path or URL is accepted in a UI prop. */
public object BundleImages {
    public const val PREFIX: String = "image:"
    private const val HEADER = "// dootah-images:"
    private val digest = Regex("[0-9a-f]{64}")

    public fun isImage(key: String): Boolean = key.startsWith(PREFIX)
    public fun hash(key: String): String = key.removePrefix(PREFIX).also {
        require(isImage(key) && digest.matches(it)) { "Invalid image reference: $key" }
    }
    public fun header(hashes: Collection<String>): String {
        hashes.forEach { require(digest.matches(it)) }
        return HEADER + hashes.distinct().sorted().joinToString(",") + "\n"
    }
    /** The bundle digest covers this dependency list as well as the JavaScript. */
    public fun required(source: String): Set<String> {
        val line = source.lineSequence().first()
        require(line.startsWith(HEADER)) { "Bundle is missing its image dependency header" }
        val entries = line.removePrefix(HEADER).takeIf { it.isNotEmpty() }
            ?.split(",").orEmpty()
        entries.forEach { require(digest.matches(it)) { "Invalid image dependency" } }
        return entries.toSet()
    }
}
