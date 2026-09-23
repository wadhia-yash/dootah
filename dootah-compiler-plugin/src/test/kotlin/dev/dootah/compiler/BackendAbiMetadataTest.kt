package dev.dootah.compiler

import java.io.File
import java.net.URLClassLoader
import java.util.Properties
import java.util.jar.JarFile
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ABI metadata every backend jar has to ship, checked on the jar itself.
 *
 * This runs once per backend module, against that module's own jar and its own
 * compiler, so a family that is added to the matrix without being packaged
 * fails here rather than in a consumer's build.
 */
class BackendAbiMetadataTest {

    private val repository = File(System.getProperty("dootah.repository"))
    private val pluginJar = File(System.getProperty("dootah.plugin.jar"))
    private val resource = "dev/dootah/backend-versions.txt"

    @Test
    fun `the backend jar ships the compiler versions it was built against`() {
        val packaged = JarFile(pluginJar).use { jar ->
            val entry = requireNotNull(jar.getJarEntry(resource)) { "$pluginJar is missing $resource" }
            jar.getInputStream(entry).bufferedReader().use { it.readText() }
        }.split(",").map(String::trim).filter(String::isNotEmpty)

        // The same file the Gradle plugin selects from, so a backend cannot
        // claim a version the plugin would never route to it, or refuse one it
        // would.
        val matrix = Properties().apply {
            repository.resolve("gradle/compiler-backends.properties").inputStream().use(::load)
        }
        val family = System.getProperty("dootah.backend.family")
        val probe = System.getProperty("dootah.backend.probe").takeIf { it.isNotBlank() }
        assertEquals(
            (probe ?: matrix.getProperty(family)).split(",").map(String::trim),
            packaged,
        )

        // Whatever the matrix says, this jar was compiled against the compiler
        // the test is running on, so that version has to be in its own list.
        assertTrue(
            "$family backend ships $packaged but was built against ${KotlinCompilerVersion.VERSION}",
            KotlinCompilerVersion.VERSION in packaged,
        )
    }

    @Test
    fun `the metadata is reachable from a class of the plugin jar alone`() {
        // How a real build loads it: the plugin sits in its own class loader,
        // under a compiler whose loader knows nothing about it. Reading the
        // resource through anything but a class of this jar -- the compiler's
        // `ExtensionStorage`, say, which is what an unqualified `javaClass`
        // resolves to inside `registerExtensions` -- finds nothing there.
        // `CompilerBackendConsumerTest` is the end-to-end proof; this is the
        // cheap version of the same question.
        URLClassLoader(arrayOf(pluginJar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { isolated ->
            val anchor = isolated.loadClass("dev.dootah.compiler.BackendAbi")
            assertEquals(isolated, anchor.classLoader)
            assertNotNull(anchor.getResourceAsStream("/$resource"))
        }
    }
}
