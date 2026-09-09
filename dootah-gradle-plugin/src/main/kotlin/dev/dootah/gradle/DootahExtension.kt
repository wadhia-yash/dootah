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
     * Directory for Dootah compiler reports.
     *
     * Present so the Phase 0 spikes and their tests can assert on what the
     * compiler observed. Not part of the eventual product surface.
     */
    abstract val reportDirectory: Property<String>
}
