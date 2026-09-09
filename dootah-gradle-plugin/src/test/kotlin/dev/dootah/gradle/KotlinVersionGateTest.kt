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
        verifyKotlinVersion(SUPPORTED_KOTLIN_VERSION)
    }

    @Test
    fun `rejects another version and names both`() {

        try {
            verifyKotlinVersion("2.4.10")
            fail("an unsupported Kotlin version must be rejected")
        } catch (expected: GradleException) {

            val message = expected.message!!

            assertTrue(message, message.contains(SUPPORTED_KOTLIN_VERSION))
            assertTrue(message, message.contains("2.4.10"))
        }
    }

    @Test
    fun `the plugin order failure names the fix`() {

        val message = composeDeclaredFirstMessage()

        assertTrue(message, message.contains("""id("dev.dootah")"""))
        assertTrue(message, message.contains("org.jetbrains.kotlin.plugin.compose"))
    }

    @Test
    fun `the missing settings plugin failure names the line to add`() {

        val message = missingSettingsPluginMessage()

        assertTrue(message, message.contains("""id("dev.dootah.settings")"""))
        assertTrue(message, message.contains("settings.gradle.kts"))
    }
}
