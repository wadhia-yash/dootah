package dev.dootah.gradle

import org.gradle.api.GradleException
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Dootah's compiler plugin is built against one Kotlin compiler ABI. A host on
 * any other version has to be told so, because the alternative is a crash inside
 * the compiler that names nothing useful.
 */
class KotlinVersionGateTest {

    @Test
    fun `accepts the supported version`() {
        compilerBackends.forEach { verifyKotlinVersion(it.compilerVersion) }
    }

    @Test
    fun `rejects another version and names both`() {

        try {
            verifyKotlinVersion("2.4.10")
            fail("an unsupported Kotlin version must be rejected")
        } catch (expected: GradleException) {

            val message = expected.message!!

            assertTrue(message, compilerBackends.all { message.contains(it.compilerVersion) })
            assertTrue(message, message.contains("2.4.10"))
        }
    }

    @Test
    fun `selects different artifacts at the same release automatically`() {
        org.junit.Assert.assertEquals("dootah-compiler-plugin-kotlin-2.0", selectCompilerBackend("2.0.20").artifactId)
        org.junit.Assert.assertEquals("dootah-compiler-plugin", selectCompilerBackend("2.3.20").artifactId)
    }

    @Test
    fun `rejects unverified patches prereleases malformed and future versions`() {
        listOf("2.0.21", "2.3.21", "2.3.20-RC", "2.0", "", "3.0.0").forEach { version ->
            try {
                selectCompilerBackend(version)
                fail("Unverified compiler accepted: $version")
            } catch (expected: GradleException) {
                assertTrue(expected.message!!, expected.message!!.contains("no verified compiler backend"))
            }
        }
    }

    @Test
    fun `the plugin order failure names the fix`() {

        val message = composeDeclaredFirstMessage()

        assertTrue(message, message.contains("""id("dev.dootah")"""))
        assertTrue(message, message.contains("org.jetbrains.kotlin.plugin.compose"))
    }
}
