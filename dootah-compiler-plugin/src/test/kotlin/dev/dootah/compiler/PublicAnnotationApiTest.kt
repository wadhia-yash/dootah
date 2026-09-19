package dev.dootah.compiler

import java.io.File
import java.util.jar.JarFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicAnnotationApiTest {
    private val repository = File(System.getProperty("dootah.repository"))

    @Test
    fun `published annotation jar contains only the native opt-out API`() {
        JarFile(System.getProperty("dootah.annotations.jar")).use { jar ->
            val classes = jar.entries().asSequence()
                .map { it.name }.filter { it.endsWith(".class") }.toSet()
            assertEquals(setOf("dev/dootah/DootahNative.class"), classes)
        }
    }

    @Test
    fun `production sources have no legacy opt-in annotation references`() {
        val sources = repository.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("dootah-") }
            .flatMap { module ->
                listOf("main", "jsMain").flatMap { sourceSet ->
                    module.resolve("src/$sourceSet").walkTopDown()
                        .filter { it.isFile }.toList()
                }
            }
        assertTrue("production sources were not found", sources.isNotEmpty())
        sources.forEach(::assertNoLegacyApi)
    }

    @Test
    fun `public documentation has no legacy opt-in annotation references`() {
        val docs = repository.listFiles().orEmpty().filter { it.extension == "md" } +
            listOf("docs", "examples", "validation").flatMap { directory ->
                repository.resolve(directory).walkTopDown()
                    .filter { it.isFile && it.extension == "md" }.toList()
            }
        assertTrue("README was not found", docs.any { it.name == "README.md" })
        docs.forEach(::assertNoLegacyApi)
    }

    private fun assertNoLegacyApi(file: File) {
        val text = file.readText()
        listOf("Bundlable", "DootahScreen").forEach { name ->
            // Generated runtime helpers may contain DootahScreen in their names;
            // only a source annotation with that name is forbidden.
            val forbidden = if (name == "DootahScreen") "@$name" else name
            assertFalse("obsolete API in ${file.relativeTo(repository)}", text.contains(forbidden, ignoreCase = true))
        }
    }
}
