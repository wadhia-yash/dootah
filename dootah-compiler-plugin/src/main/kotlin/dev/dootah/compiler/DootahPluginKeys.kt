package dev.dootah.compiler

import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.name.FqName

/** The plugin id shared by the Gradle plugin and this compiler plugin. */
const val DOOTAH_PLUGIN_ID: String = "dev.dootah"

val BUNDLABLE_ANNOTATION: FqName = FqName("dev.dootah.Bundlable")

/** Opts a function, class or file out of Dootah entirely. */
val DOOTAH_NATIVE_ANNOTATION: FqName = FqName("dev.dootah.DootahNative")

/**
 * Tooling previews, matched by simple name.
 *
 * There is more than one `Preview` -- Android's, Desktop's, and whatever a
 * multiplatform target adds next -- and they are all equally not shipped UI.
 * Matching the name rather than a list of packages means a new one is handled
 * the day it appears instead of the day someone notices.
 */
const val PREVIEW_ANNOTATION_NAME: String = "Preview"

val COMPOSABLE_ANNOTATION: FqName = FqName("androidx.compose.runtime.Composable")

/**
 * The parameter type the Compose compiler threads through every lowered
 * composable. Its presence is how Dootah detects that Compose already ran.
 */
val COMPOSER_CLASS: FqName = FqName("androidx.compose.runtime.Composer")

/**
 * The layouts whose children Dootah describes remotely.
 *
 * Declared here rather than beside the rest of the supported catalogue because
 * both passes need it and they must agree: the extraction pass never turns one
 * of these into a native slot, and the app's build must not register one as a
 * slot either. A layout kept native would swallow the children that are meant to
 * be updatable.
 */
val LAYOUT_COMPOSABLES: Set<FqName> = setOf(
    FqName("androidx.compose.foundation.layout.Column"),
    FqName("androidx.compose.foundation.layout.Row"),
    FqName("androidx.compose.foundation.layout.Box"),
)

/**
 * What this compiler plugin invocation is for.
 *
 * The same jar runs in two places with two jobs, and they must not be confused:
 * [INTERCEPT] runs inside the host app's real compilation, [EXTRACT] runs in
 * Dootah's own analysis-only pass.
 */
enum class DootahMode {
    INTERCEPT,
    EXTRACT;

    companion object {
        fun fromCliValue(value: String): DootahMode? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * How Dootah decides which Compose functions it may take over.
 *
 * [AUTO] is the product: a developer configures the project once and keeps
 * writing ordinary Compose. [ANNOTATED] is the older model, kept because it is
 * the only way to ask "what changes when discovery is switched on", and because
 * an app part-way through migrating needs to be able to stand still.
 */
enum class DootahDiscovery {
    AUTO,
    ANNOTATED;

    companion object {
        fun fromCliValue(value: String): DootahDiscovery? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

object DootahConfigurationKeys {

    val MODE: CompilerConfigurationKey<DootahMode> =
        CompilerConfigurationKey.create("dootah mode")

    /** Where diagnostics and per-screen metadata are written. */
    val REPORT_DIR: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("dootah report directory")

    /** Where generated bundle Kotlin is written. Extraction only. */
    val GENERATED_DIR: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("dootah generated source directory")

    /** Whether functions are discovered automatically or by annotation. */
    val DISCOVERY: CompilerConfigurationKey<DootahDiscovery> =
        CompilerConfigurationKey.create("dootah discovery")

    /**
     * The encoded include/exclude patterns.
     *
     * Passed as one opaque string because both compiler invocations have to
     * parse it with the same code for the APK and the bundle to agree on which
     * screens exist at all.
     */
    val FILTER: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("dootah screen filter")
}
