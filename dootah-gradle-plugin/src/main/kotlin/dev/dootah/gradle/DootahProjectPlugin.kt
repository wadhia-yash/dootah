package dev.dootah.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

private const val COMPOSE_PLUGIN_ID = "org.jetbrains.kotlin.plugin.compose"

private const val DOOTAH_GROUP = "dev.dootah"
private const val COMPILER_PLUGIN_ARTIFACT = "dootah-compiler-plugin"
private const val ANNOTATIONS_ARTIFACT = "dootah-annotations"

/**
 * The `dev.dootah` plugin applied to a host app.
 *
 * Responsible for build experience only -- attaching the compiler plugin, wiring
 * the annotation dependency, gating the compiler version. It holds no knowledge
 * of Kotlin or Compose semantics; that all lives in the compiler plugin.
 */
class DootahProjectPlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {

        val extension = target.extensions
            .create("dootah", DootahExtension::class.java)

        // Must match DOOTAH_RUNTIME_VERSION in dootah-android, which is the
        // token an installed app compares a manifest against by exact equality.
        // It defaulted to "1" long after the runtime had moved on, so a project
        // that did not set this by hand published a manifest every installed app
        // refused as incompatible.
        extension.runtimeVersion.convention("3")
        extension.bundleVersion.convention(1)
        extension.bundleUrl.convention("https://example.invalid/bundle.js")

        rejectComposeDeclaredFirst(target)
        gateKotlinVersion(target)
        addAnnotationDependency(target)

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
            verifyKotlinVersion(kotlinPlugin.pluginVersion)
        }
    }

    /**
     * Puts `@Bundlable` on the app's compile classpath.
     *
     * Wired automatically because requiring a host app to declare a dependency
     * on an annotation it did not choose is exactly the manual step this plugin
     * exists to remove.
     */
    private fun addAnnotationDependency(target: Project) {
        target.configurations
            .matching { it.name == "implementation" }
            .configureEach { configuration ->
                target.dependencies.add(
                    configuration.name,
                    "$DOOTAH_GROUP:$ANNOTATIONS_ARTIFACT:${pluginVersion()}",
                )
            }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = DOOTAH_GROUP

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = DOOTAH_GROUP,
        artifactId = COMPILER_PLUGIN_ARTIFACT,
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
            )
        }
    }

    /**
     * The version of this plugin, used for the compiler plugin and annotation it
     * must stay in lockstep with.
     */
    private fun pluginVersion(): String =
        DootahProjectPlugin::class.java.`package`?.implementationVersion
            ?: FALLBACK_VERSION

    private companion object {
        /** Used when running from a build output that carries no jar manifest. */
        const val FALLBACK_VERSION = "0.1.0-SNAPSHOT"

        const val DEBUG_COMPILE_TASK = "compileDebugKotlin"
        const val EXTRACT_TASK = "dootahExtract"

        /** Where the compiler writes bundle Kotlin, and the bundle task reads it. */
        const val GENERATED_SOURCES_PATH = "dootah/generated/jsMain/kotlin"
    }
}
