package dev.dootah.compiler

/**
 * The Kotlin compiler versions this backend jar was built against.
 *
 * A backend jar is compiled against one compiler ABI and is meaningless on
 * another, so it carries the list of versions the build matrix verified for it
 * and refuses to register itself anywhere else. The Gradle plugin picks the
 * backend from the same matrix; this check is what makes a hand-written
 * `kotlinCompilerPluginClasspath` fail loudly instead of crashing somewhere
 * inside the compiler.
 *
 * The lookup is anchored on a class of this jar's *own*, which is the whole
 * point of the object. `registerExtensions` is an extension function on the
 * compiler's `ExtensionStorage`, so an unqualified `javaClass` there is the
 * compiler's class, not the plugin's -- and the compiler's loader never holds
 * the plugin jar. Under a test harness where both sit on one class loader that
 * reads the same, which is why it can only be caught by a real build.
 */
internal object BackendAbi {

    /** Written into the jar by the backend's `generateBackendMetadata` task. */
    private const val RESOURCE = "/dev/dootah/backend-versions.txt"

    val supportedKotlinVersions: List<String> by lazy {
        requireNotNull(BackendAbi::class.java.getResourceAsStream(RESOURCE)) {
            "Dootah compiler backend metadata ($RESOURCE) is missing from ${origin()}; " +
                "reinstall this release."
        }.bufferedReader()
            .use { it.readText() }
            .split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    /** Fails closed: an unverified compiler ABI is refused, never guessed at. */
    fun verify(runningKotlinVersion: String) {
        require(runningKotlinVersion in supportedKotlinVersions) {
            "Dootah compiler backend supports Kotlin ${supportedKotlinVersions.joinToString()}, " +
                "but the running compiler is $runningKotlinVersion. " +
                "Apply dev.dootah to select the correct backend automatically."
        }
    }

    /** Where this jar was loaded from, so a packaging failure names the file. */
    private fun origin(): String =
        BackendAbi::class.java.protectionDomain?.codeSource?.location?.toString()
            ?: "the Dootah compiler plugin jar"
}
