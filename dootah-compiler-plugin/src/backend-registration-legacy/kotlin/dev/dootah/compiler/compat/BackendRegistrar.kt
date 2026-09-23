package dev.dootah.compiler.compat

import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.extensions.ProjectExtensionDescriptor

/**
 * Registration on this compiler line, where the order is decided differently.
 *
 * `KotlinCoreEnvironment` runs every legacy `ComponentRegistrar` against the
 * project first, and only then installs what the `CompilerPluginRegistrar`s
 * collected. On this line the Compose compiler plugin is still a
 * `ComponentRegistrar`, so an IR extension registered the modern way lands
 * after Compose's no matter where Dootah sits on the plugin classpath -- and
 * Dootah's whole transform depends on running before it.
 *
 * Registering the same way Compose does puts both back under one rule, the one
 * the Gradle plugin already enforces: plugin classpath order, which follows the
 * order the app declares its plugins in. On the newer line Compose registers
 * through `ExtensionStorage` and the modern path is the one that agrees, which
 * is why this lives here and not in the shared registrar.
 */
abstract class BackendRegistrar : ComponentRegistrar {

    final override fun registerProjectComponents(
        project: MockProject,
        configuration: CompilerConfiguration,
    ) {
        ExtensionStorage(project).registerExtensions(configuration)
    }

    protected abstract fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration)

    /**
     * The shape the shared registrar writes against, registering eagerly.
     *
     * Same member-extension signature as the compiler's own storage of that
     * name, so the shared code reads identically on both lines.
     */
    class ExtensionStorage(private val project: MockProject) {
        fun <T : Any> ProjectExtensionDescriptor<T>.registerExtension(extension: T) =
            registerExtension(project, extension)
    }
}
