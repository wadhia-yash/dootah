package dev.dootah.gradle

import dev.dootah.contract.RuntimeVersion
import dev.dootah.contract.ScreenFilter
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

private const val COMPOSE_PLUGIN_ID = "org.jetbrains.kotlin.plugin.compose"

private const val DOOTAH_GROUP = "dev.dootah"
private const val ANNOTATIONS_ARTIFACT = "dootah-annotations"
private const val RUNTIME_ARTIFACT = "dootah-android"

/**
 * The `dev.dootah` plugin applied to a host app.
 *
 * Responsible for build experience only -- attaching the compiler plugin, wiring
 * the annotation dependency, gating the compiler version. It holds no knowledge
 * of Kotlin or Compose semantics; that all lives in the compiler plugin.
 */
class DootahProjectPlugin : KotlinCompilerPluginSupportPlugin {

    private var backend: CompilerBackend? = null

    override fun apply(target: Project) {

        val extension = target.extensions
            .create("dootah", DootahExtension::class.java)

        // Read from the contract rather than written out, which is what the
        // other three holders of this value already do. It was a fourth copy,
        // and it had already gone stale once: it said "1" long after the runtime
        // had moved on, so a project that did not set this by hand published a
        // manifest every installed app refused as incompatible.
        extension.runtimeVersion.convention(RuntimeVersion.CURRENT)
        extension.bundleVersion.convention(1)
        extension.bundleUrl.convention("https://example.invalid/bundle.js")

        // Automatic by default, which is the whole product: a developer adds the
        // plugin and keeps writing ordinary Compose.
        extension.discovery.convention("auto")
        extension.failOnUnsupportedScreen.convention(false)

        rejectComposeDeclaredFirst(target)
        gateKotlinVersion(target)
        addRuntimeDependencies(target)

        // Milestone 1 wires the debug variant only. Per-variant registration
        // follows once the bundle build is in place.
        target.afterEvaluate { evaluated ->
            if (evaluated.tasks.findByName(DEBUG_COMPILE_TASK) != null) {

                val generatedSources = evaluated.layout.buildDirectory
                    .dir(GENERATED_SOURCES_PATH).get()

                registerExtractTask(evaluated, DEBUG_COMPILE_TASK, generatedSources)
                registerBundleTask(evaluated, EXTRACT_TASK, generatedSources)
            }
        }
    }

    /**
     * Fails when Compose was already applied as this plugin is applied.
     *
     * Plugin application order determines compiler plugin order, so the wrong
     * declaration order is caught here, at configuration time, with the fix in
     * the message. The compiler-side guard remains as the backstop for any case
     * this cannot observe.
     */
    private fun rejectComposeDeclaredFirst(target: Project) {

        if (target.plugins.hasPlugin(COMPOSE_PLUGIN_ID)) {
            throw org.gradle.api.GradleException(composeDeclaredFirstMessage())
        }
    }

    private fun gateKotlinVersion(target: Project) {
        target.plugins.withType(KotlinBasePlugin::class.java) { kotlinPlugin ->
            backend = selectCompilerBackend(kotlinPlugin.pluginVersion)
        }
    }

    /**
     * Puts the optional native opt-out annotation and the Dootah runtime on the app's classpath.
     *
     * Wired automatically because requiring a host app to declare dependencies
     * it did not choose is exactly the manual step this plugin exists to remove
     * -- and because the runtime is what the compiler's own output calls into,
     * so the two cannot be allowed to come from different releases. Both are
     * resolved at this plugin's version for that reason.
     *
     * The runtime used to be copied into each app by hand. A `git stash` once
     * reverted one of those copies underneath a device test, which then passed
     * against code that was not the code being tested.
     */
    private fun addRuntimeDependencies(target: Project) {
        target.configurations
            .matching { it.name == "implementation" }
            .configureEach { configuration ->
                listOf(ANNOTATIONS_ARTIFACT, RUNTIME_ARTIFACT).forEach { artifact ->
                    target.dependencies.add(
                        configuration.name,
                        "$DOOTAH_GROUP:$artifact:${pluginVersion()}",
                    )
                }
            }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = DOOTAH_GROUP

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = DOOTAH_GROUP,
        artifactId = requireNotNull(backend) { "Dootah cannot select a compiler backend before Kotlin is applied" }.artifactId,
        version = pluginVersion(),
    )

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>,
    ): Provider<List<SubpluginOption>> {

        val project = kotlinCompilation.project
        val extension = project.extensions.getByType(DootahExtension::class.java)

        val reportDirectory = extension.reportDirectory.orElse(
            project.layout.buildDirectory.dir("dootah/reports")
                .map { it.asFile.absolutePath }
        )

        return project.provider {
            listOf(
                SubpluginOption("mode", "intercept"),
                SubpluginOption("reportDir", reportDirectory.get()),
                SubpluginOption("discovery", extension.discovery.get()),
                SubpluginOption("filter", screenFilterOf(extension).encode()),
            )
        }
    }

    /**
     * The version of this plugin, used for the compiler plugin and annotation it
     * must stay in lockstep with.
     */
    private fun pluginVersion(): String =
        DOOTAH_VERSION

    private companion object {
        const val DEBUG_COMPILE_TASK = "compileDebugKotlin"
        const val EXTRACT_TASK = "dootahExtract"

        /** Where the compiler writes bundle Kotlin, and the bundle task reads it. */
        const val GENERATED_SOURCES_PATH = "dootah/generated/jsMain/kotlin"
    }
}

/**
 * The include/exclude patterns as the compiler receives them.
 *
 * Built here once and handed to both compiler invocations. The app's own build
 * decides which screens the APK can render; the extraction pass decides which
 * screens a bundle describes. Those two answers are compared across a network
 * and across time, so they are produced from one value encoded by one piece of
 * code rather than assembled twice.
 */
internal fun screenFilterOf(extension: DootahExtension): ScreenFilter =
    ScreenFilter.of(
        include = extension.includes.getOrElse(emptyList()),
        exclude = extension.excludes.getOrElse(emptyList()),
    )
