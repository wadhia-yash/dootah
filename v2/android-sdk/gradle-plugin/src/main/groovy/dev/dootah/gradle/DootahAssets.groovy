package dev.dootah.gradle

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*

abstract class DootahAssets extends DefaultTask {
    @Input abstract Property<String> getIdentity()
    @Input abstract Property<Boolean> getLocalHttp()
    @Input abstract Property<Boolean> getDebuggable()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    DootahAssets() {
        localHttp.convention(false)
        debuggable.convention(false)
    }

    static void validateTransport(boolean http, boolean debug) {
        if (http && !debug) throw new org.gradle.api.GradleException(
            'Dootah local HTTP is debug-only. Use HTTPS for non-debuggable variants; put loopback Network Security Config in src/debug only.')
    }

    @TaskAction void generate() {
        validateTransport(localHttp.get(), debuggable.get())
        def directory = outputDirectory.get().asFile
        directory.mkdirs()
        // Persist per build input set, like Expo's embedded manifest generation.
        new File(directory, 'app.manifest').text = JsonOutput.toJson([
            id: UUID.randomUUID().toString(),
            commitTime: System.currentTimeMillis(), assets: []
        ])
    }
}
