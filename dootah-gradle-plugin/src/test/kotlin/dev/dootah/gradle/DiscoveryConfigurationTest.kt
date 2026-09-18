package dev.dootah.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryConfigurationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `build script exclusion reaches the shared compiler filter and wins over inclusion`() {
        val directory = temporaryFolder.root
        directory.resolve("settings.gradle").writeText("rootProject.name = 'discovery-config'")
        directory.resolve("build.gradle").writeText("""
            plugins {
                id 'java'
                id 'dev.dootah'
            }
            dootah {
                include('com.example.**')
                exclude('com.example.payments.**')
            }
            tasks.register('verifyDiscovery') {
                doLast {
                    assert dootah.discovery.get() == 'auto'
                    def filter = dev.dootah.gradle.DootahProjectPluginKt.screenFilterOf(dootah)
                    assert filter.accepts('com.example.Home')
                    assert !filter.accepts('com.example.payments.Checkout')
                    assert !filter.accepts('com.other.Screen')
                    println 'discovery configuration verified'
                }
            }
        """.trimIndent())
        val result = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("verifyDiscovery", "--no-configuration-cache").build()
        assertTrue(result.output, result.output.contains("discovery configuration verified"))
    }
}
