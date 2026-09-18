package dev.dootah.gradle.tasks

import dev.dootah.gradle.internal.PublicationClient
import dev.dootah.contract.ContractJson
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File

@UntrackedTask(because = "Publication is an external authenticated side effect; retries must contact the server")
abstract class DootahPublishTask : DefaultTask() {
    @get:InputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Input abstract val server: Property<String>
    @get:Input abstract val channel: Property<String>
    @get:Input abstract val rollout: Property<Int>
    @get:Input @get:Optional abstract val minAppVersion: Property<Long>
    @get:Input @get:Optional abstract val maxAppVersion: Property<Long>
    @TaskAction fun publish() {
        val result = PublicationClient(server.get(), System.getenv("DOOTAH_PUBLISH_TOKEN") ?: "")
            .publish(outputDirectory.get().asFile, channel.get(), rollout.get(), minAppVersion.orNull, maxAppVersion.orNull)
        logger.lifecycle("Dootah publication ${result["status"]}: ${result["identity"]}")
    }
}

@UntrackedTask(because = "Checks external secrets without recording them in task inputs or cache")
abstract class DootahPublishPreflight : DefaultTask() {
    @get:InputFile abstract val contractFile: RegularFileProperty
    @get:Input abstract val server: Property<String>
    @get:Input abstract val channel: Property<String>
    @get:Input abstract val rollout: Property<Int>
    @TaskAction fun check() {
        PublicationClient.validateServer(server.get())
        PublicationClient.validateControls(channel.get(), rollout.get())
        PublicationClient(server.get(), System.getenv("DOOTAH_PUBLISH_TOKEN") ?: "")
        val key = System.getenv("DOOTAH_SIGNING_KEY_FILE")?.let { File(it).canonicalFile }
        require(key != null && key.isFile && !key.toPath().startsWith(project.rootDir.canonicalFile.toPath())) {
            "Set DOOTAH_SIGNING_KEY_FILE to an existing external PKCS#8 PEM key"
        }
        require(ContractJson.readContract(contractFile.get().asFile.readText()).screens.isNotEmpty()) { "A recorded installed-app contract is required for publication" }
    }
}
