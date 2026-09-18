package dev.dootah.gradle

import org.gradle.api.GradleException

/**
 * The single Kotlin compiler version Compiler Milestone 1 supports.
 *
 * A Kotlin compiler plugin is compiled against a specific compiler ABI, so this
 * is a hard requirement rather than a recommendation. Claiming a wider range
 * would turn an ABI mismatch into an obscure crash inside the compiler.
 */
const val SUPPORTED_KOTLIN_VERSION: String = "2.3.20"

/**
 * Rejects an unsupported host compiler with a message that names the versions
 * involved.
 */
internal fun verifyKotlinVersion(hostKotlinVersion: String) {

    if (hostKotlinVersion == SUPPORTED_KOTLIN_VERSION) return

    throw GradleException(
        "Dootah supports Kotlin $SUPPORTED_KOTLIN_VERSION only, but this project " +
            "compiles with Kotlin $hostKotlinVersion.\n" +
            "Dootah's compiler plugin is built against a single Kotlin compiler ABI. " +
            "Align the project's Kotlin version, or use a Dootah release built for " +
            "Kotlin $hostKotlinVersion."
    )
}

/** The failure text used when Dootah is declared after the Compose plugin. */
internal fun composeDeclaredFirstMessage(): String =
    "Dootah must be declared before the Compose compiler plugin, because " +
        "Dootah's transform runs in plugin declaration order and Compose must " +
        "lower Dootah's generated composable calls afterwards.\n" +
        "Reorder the app's plugins block:\n" +
        "    plugins {\n" +
        "        id(\"dev.dootah\")\n" +
        "        id(\"org.jetbrains.kotlin.plugin.compose\")\n" +
        "    }"

/**
 * The Dootah release these plugins belong to.
 *
 * Read from the jar so the compiler plugin, the annotation and the bundle
 * runtime cannot drift apart from the Gradle plugin that wires them.
 */
internal val DOOTAH_VERSION: String
    get() = DootahProjectPlugin::class.java.`package`?.implementationVersion
        ?: "0.1.0-alpha.1"
