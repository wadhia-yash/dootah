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

        extension.runtimeVersion.convention("1")

        rejectComposeDeclaredFirst(target)
        gateKotlinVersion(target)
        addAnnotationDependency(target)
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
    }
}
