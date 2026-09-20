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

        wireVariants(target)
    }

    /**
     * Wires the workflow to a variant of the host module.
     *
     * An Android module is asked what it builds, through the variant API, and
     * the answer decides which compilation Dootah reads. That question is asked
     * from inside [org.gradle.api.plugins.PluginContainer.withId] so that this
     * project's `afterEvaluate` is registered after the Android plugin's own --
     * which is what puts it after variant creation, in either declaration order.
     *
     * A module with no Android variants keeps the wiring Dootah has always had:
     * the `debug` compilation, where one exists.
     */
    private fun wireVariants(target: Project) {

        val variants = mutableListOf<DootahVariant>()
        var wired = false

        ANDROID_PLUGIN_IDS.forEach { pluginId ->
            target.plugins.withId(pluginId) {
                if (wired) return@withId
                wired = true
                collectAndroidVariants(target, variants)
                target.afterEvaluate { evaluated -> registerDootahWorkflow(evaluated, variants) }
            }
        }

        target.afterEvaluate { evaluated ->
            if (wired) return@afterEvaluate
            if (evaluated.tasks.findByName(DEBUG_COMPILE_TASK) == null) return@afterEvaluate
            registerDootahWorkflow(
                evaluated,
                listOf(DootahVariant(name = DEBUG_COMPILATION, buildType = DEBUG_COMPILATION)),
            )
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

        // Per compilation: a flavoured module compiles the same screens once per
        // variant, and the contract has to describe one app rather than their union.
        val reportDirectory = reportDirectoryFor(project, extension, kotlinCompilation.name)

        // Declared as something this compilation produces, because it is. The
        // compiler writes the app's contract there as a side effect, and until
        // Gradle was told so, deleting `build/dootah` left the compilation
        // up to date with its report gone -- and `dootahRecordContract` then
        // failed on a missing directory, naming a path and no way out of it.
        kotlinCompilation.compileTaskProvider.configure { task ->
            task.outputs.dir(reportDirectory).withPropertyName("dootahReport")
        }

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
        /** The compilation a module with no Android variants is wired to, as it always was. */
        const val DEBUG_COMPILATION = "debug"
        const val DEBUG_COMPILE_TASK = "compileDebugKotlin"
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
