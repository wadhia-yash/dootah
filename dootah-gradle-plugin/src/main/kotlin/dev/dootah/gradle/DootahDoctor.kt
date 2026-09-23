package dev.dootah.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.util.zip.ZipFile

/** Diagnostic only: does not compile, record a contract, install or publish. */
abstract class DootahDoctorTask : DefaultTask() {
    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val kotlinVersion: Property<String>
    @get:Input abstract val kgpVersion: Property<String>
    @get:Input abstract val agpVersion: Property<String>
    @get:Input abstract val composeDetected: Property<Boolean>
    @get:Input abstract val family: Property<String>
    @get:Input abstract val artifact: Property<String>
    @get:Input abstract val runtimeVersion: Property<String>
    @get:Input abstract val appId: Property<String>
    @get:Internal abstract val contract: RegularFileProperty
    @get:Classpath abstract val backendFiles: ConfigurableFileCollection

    @TaskAction fun diagnose() {
        val version = kotlinVersion.get()
        val metadata = backendFiles.files.singleOrNull()?.let { jar ->
            ZipFile(jar).use { zip ->
                zip.getEntry("dev/dootah/backend-versions.txt")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().use { it.readText().trim() }
                }
            }
        }
        val verified = version in metadata.orEmpty().split(',').map(String::trim)
        val contractFile = contract.get().asFile
        val contractMatches = contractFile.isFile && runCatching {
            val recorded = dev.dootah.contract.ContractJson.readContract(contractFile.readText())
            recorded.runtimeVersion == runtimeVersion.get() && recorded.screens.isNotEmpty()
        }.getOrDefault(false)
        val ready = verified && composeDetected.get() && variantName.get() != "unresolved" && contractMatches &&
            appId.get().isNotBlank() && runtimeVersion.get() == dev.dootah.contract.RuntimeVersion.CURRENT
        logger.lifecycle("""
            Dootah Doctor
            Variant: ${variantName.get()}
            App ID: ${appId.get().ifBlank { "not configured" }}
            Kotlin compiler: $version
            KGP: ${kgpVersion.get()}
            AGP: ${agpVersion.get()}
            Compose compiler: ${if (composeDetected.get()) "detected" else "not detected"}
            Compiler backend: ${family.get()}
            Backend artifact: ${artifact.get()}
            Backend ABI metadata: ${metadata ?: "unavailable"}
            ABI compatibility: ${if (verified) "VERIFIED" else "UNSUPPORTED"}
            Contract: ${if (contractFile.isFile) "present" else "missing"} (${contractFile.path})
            runtimeVersion: ${runtimeVersion.get()}
            OTA readiness: ${if (ready) "READY" else "NOT READY"}
        """.trimIndent())
        if (family.get() == "none") logger.lifecycle(unsupportedCompilerMessage(version))
        if (!contractFile.isFile) logger.lifecycle("Record the installed baseline with dootahRecordContract before publishing.")
        if (contractFile.isFile && !contractMatches) logger.lifecycle("The baseline contract is invalid or targets a different runtimeVersion.")
        logger.lifecycle("Readiness is a local build check; device delivery and activation are not verified by Doctor.")
    }
}

internal fun registerDootahDoctor(project: Project, variants: List<DootahVariant>, extension: DootahExtension) {
    val version = hostKotlinVersion(project) ?: "unknown"
    val backend = resolveCompilerBackend(version)
    val selected = runCatching { selectDootahVariant(variants, requestedVariant(project, extension)) }.getOrNull()
    val agp = project.plugins.findPlugin("com.android.application") ?: project.plugins.findPlugin("com.android.library")
    val agpVersion = agp?.let {
        runCatching { it.javaClass.classLoader.loadClass("com.android.Version").getField("ANDROID_GRADLE_PLUGIN_VERSION").get(null).toString() }.getOrNull()
    } ?: "not detected"
    val artifact = backend?.let { "dev.dootah:${it.artifactId}:$DOOTAH_VERSION" }
    val files = artifact?.let {
        project.configurations.maybeCreate("dootahDoctorBackend").apply {
            isCanBeConsumed = false
            isTransitive = false
            project.dependencies.add(name, it)
        }
    }
    project.tasks.register("dootahDoctor", DootahDoctorTask::class.java) {
        it.group = "dootah"
        it.description = "Reports compiler compatibility and local OTA readiness without building the app"
        it.variantName.set(selected?.name ?: "unresolved")
        it.kotlinVersion.set(version)
        it.kgpVersion.set(hostKgpVersion(project) ?: "unknown")
        it.agpVersion.set(agpVersion)
        it.composeDetected.set(project.plugins.hasPlugin("org.jetbrains.kotlin.plugin.compose"))
        it.family.set(backend?.family ?: "none")
        it.artifact.set(artifact ?: "none")
        it.runtimeVersion.set(extension.runtimeVersion)
        it.appId.set(extension.appId.orElse(""))
        it.contract.set(project.layout.projectDirectory.file("dootah/contract.json"))
        if (files != null) it.backendFiles.from(files)
    }
}
