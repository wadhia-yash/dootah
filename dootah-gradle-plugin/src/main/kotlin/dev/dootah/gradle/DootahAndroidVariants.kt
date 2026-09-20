package dev.dootah.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Action
import org.gradle.api.Project

private const val ANDROID_COMPONENTS_EXTENSION = "androidComponents"

/**
 * Reports every variant of an Android module to [into], as the Android plugin creates them.
 *
 * The variant API is the only account of what this module actually builds that
 * survives flavours, build types and variant filters. Deriving the list from
 * task names instead worked exactly as long as the module had no flavours.
 */
@Suppress("UNCHECKED_CAST")
internal fun collectAndroidVariants(project: Project, into: MutableList<DootahVariant>) {

    val components = project.extensions.findByName(ANDROID_COMPONENTS_EXTENSION)
        as? AndroidComponentsExtension<*, *, Variant> ?: return

    components.onVariants(
        components.selector().all(),
        Action<Variant> { variant ->
            into += DootahVariant(
                name = variant.name,
                buildType = variant.buildType,
                resourceDirectories = runCatching { variant.sources.res?.all }.getOrNull(),
            )
        },
    )
}
