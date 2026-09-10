package dev.dootah.compiler

import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.name.FqName

/** The plugin id shared by the Gradle plugin and this compiler plugin. */
const val DOOTAH_PLUGIN_ID: String = "dev.dootah"

val BUNDLABLE_ANNOTATION: FqName = FqName("dev.dootah.Bundlable")

val COMPOSABLE_ANNOTATION: FqName = FqName("androidx.compose.runtime.Composable")

/**
 * The parameter type the Compose compiler threads through every lowered
 * composable. Its presence is how Dootah detects that Compose already ran.
 */
val COMPOSER_CLASS: FqName = FqName("androidx.compose.runtime.Composer")

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

object DootahConfigurationKeys {

    val MODE: CompilerConfigurationKey<DootahMode> =
        CompilerConfigurationKey.create("dootah mode")

    /** Where diagnostics and per-screen metadata are written. */
    val REPORT_DIR: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("dootah report directory")

    /** Where generated bundle Kotlin is written. Extraction only. */
    val GENERATED_DIR: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("dootah generated source directory")
}
