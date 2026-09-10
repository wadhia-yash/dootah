package dev.dootah.gradle

import org.gradle.api.provider.Property

/** The `dootah { }` block in a host app's build script. */
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
}
