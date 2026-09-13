package dev.dootah.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * The `dootah { }` block in a host app's build script.
 *
 * The intended shape of a host app's entire Dootah setup: apply the plugin,
 * name where updates are published, and keep writing ordinary Compose. Nothing
 * here is per screen, and nothing here needs to be repeated as the app grows.
 */
abstract class DootahExtension {

    /**
     * The Dootah runtime contract the published bundle targets.
     *
     * Must match the runtime version compiled into the installed app, which is
     * what stops a bundle running against a runtime that cannot honour it.
     */
    abstract val runtimeVersion: Property<String>

    /**
     * The version of the bundle being built.
     *
     * The installed app only accepts a bundle strictly newer than the one it
     * has, so this has to be raised for each publish. It is explicit rather than
     * derived: a version that moved on its own could roll a device backwards or
     * silently skip a release.
     */
    abstract val bundleVersion: Property<Int>

    /**
     * The HTTPS URL the published `bundle.js` will be served from.
     *
     * Written into the generated manifest, which is what the installed app
     * follows to download it.
     */
    abstract val bundleUrl: Property<String>

    /**
     * Directory for Dootah compiler reports.
     *
     * Present so builds and tests can inspect what the compiler observed.
     */
    abstract val reportDirectory: Property<String>

    /**
     * How Dootah decides which Compose functions it may take over.
     *
     * `"auto"`, the default, is the point of the plugin: configure the project
     * once and go on writing ordinary Compose. `"annotated"` restricts Dootah to
     * functions marked `@Bundlable`, which is useful while migrating and when
     * asking what changes as discovery is switched on.
     */
    abstract val discovery: Property<String>

    /**
     * Fully qualified names Dootah may consider, as patterns.
     *
     * Empty means every Compose function in the module, which is what a project
     * gets for applying the plugin and configuring nothing. Set this to narrow
     * Dootah to part of an app.
     */
    abstract val includes: ListProperty<String>

    /**
     * Fully qualified names Dootah must leave alone, as patterns.
     *
     * Always wins over [includes]: an instruction to leave something native
     * should never have to out-argue a wildcard. `@DootahNative` does the same
     * job in the source, for cases where the reason belongs next to the code.
     */
    abstract val excludes: ListProperty<String>

    /**
     * Whether a screen Dootah could not describe fails the build.
     *
     * Off by default, and that default is a consequence of automatic discovery
     * rather than a relaxation of standards. Dootah now looks at every Compose
     * function in the app; most of any real app is beyond what it can describe
     * today, and a build that failed over each one would be unusable. A screen
     * it cannot describe is absent from the bundle, and the app renders the
     * native body it always had.
     *
     * A function marked `@Bundlable` is held to the stricter standard whatever
     * this is set to: that annotation is a developer asking for a specific
     * screen by name, and silence would be the wrong answer.
     */
    abstract val failOnUnsupportedScreen: Property<Boolean>

    /** Adds patterns to [includes]. */
    fun include(vararg patterns: String) {
        includes.addAll(patterns.toList())
    }

    /** Adds patterns to [excludes]. */
    fun exclude(vararg patterns: String) {
        excludes.addAll(patterns.toList())
    }
}
