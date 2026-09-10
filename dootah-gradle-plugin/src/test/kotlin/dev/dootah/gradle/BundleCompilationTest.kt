package dev.dootah.gradle

import dev.dootah.gradle.internal.BUNDLE_MODULE_NAME
import dev.dootah.gradle.internal.compiledBundleFile
import dev.dootah.gradle.internal.klibArguments
import dev.dootah.gradle.internal.linkArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundle protocol is decided by these compiler arguments, so the couplings
 * that would break it silently are pinned here.
 */
class BundleCompilationTest {

    private val libraries = listOf(File("/libs/kotlin-stdlib-js.klib"), File("/libs/runtime.klib"))
    private val output = File("/out")

    /**
     * The installed app resolves the bundle through a global of exactly this
     * name, and the compiler names both the output file and the UMD global after
     * the module name.
     */
    @Test
    fun `names the module what the runtime resolves`() {

        assertEquals("dootah-bundle", BUNDLE_MODULE_NAME)

        val arguments = linkArguments(File("/out/in.klib"), libraries, output).arguments

        assertEquals(BUNDLE_MODULE_NAME, arguments.valueOf("-ir-output-name"))
        assertEquals(File("/out/dootah-bundle.js"), compiledBundleFile(output))
    }

    /**
     * The Android isolate has no module system, so the wrapper must take its
     * global branch. A CommonJS or plain build would export nothing findable.
     */
    @Test
    fun `links as UMD without calling main`() {

        val arguments = linkArguments(File("/out/in.klib"), libraries, output).arguments

        assertEquals("umd", arguments.valueOf("-module-kind"))
        assertEquals("noCall", arguments.valueOf("-main"))
    }

    /**
     * Without dead code elimination the whole standard library ships in every
     * bundle, which is an order of magnitude more to download.
     */
    @Test
    fun `eliminates dead code when linking`() {

        val arguments = linkArguments(File("/out/in.klib"), libraries, output).arguments

        assertTrue(arguments.toString(), arguments.contains("-Xir-dce"))
    }

    @Test
    fun `produces a klib from sources before linking`() {

        val arguments = klibArguments(
            generatedSources = listOf(File("/src/A.kt")),
            libraries = libraries,
            outputDirectory = output,
        ).arguments

        assertTrue(arguments.toString(), arguments.contains("-Xir-produce-klib-file"))
        assertTrue(arguments.toString(), arguments.contains("/src/A.kt"))
    }

    /** Identical sources must produce identical compiler invocations. */
    @Test
    fun `orders sources deterministically`() {

        val unsorted = listOf(File("/src/Z.kt"), File("/src/A.kt"), File("/src/M.kt"))

        val first = klibArguments(unsorted, libraries, output).arguments
        val second = klibArguments(unsorted.reversed(), libraries, output).arguments

        assertEquals(first, second)
        assertEquals(listOf("/src/A.kt", "/src/M.kt", "/src/Z.kt"), first.takeLast(3))
    }

    private fun List<String>.valueOf(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 }?.let { this[it + 1] }
}
