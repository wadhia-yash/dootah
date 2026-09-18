package dev.dootah.gradle

import dev.dootah.contract.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real Gradle task graph: neither failure is allowed to reach the publisher task. */
class PublicationGateTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(): java.io.File {
        val dir=temp.newFolder()
        dir.resolve("settings.gradle").writeText("rootProject.name='publish-gate'")
        dir.resolve("build.gradle").writeText("""
            plugins { id 'dev.dootah' apply false }
            extensions.create('dootah', dev.dootah.gradle.DootahExtension)
            configurations.create('dootahKotlinCompiler')
            tasks.register('fixtureExtract')
            dev.dootah.gradle.DootahBundleWiringKt.registerBundleTask(project, 'fixtureExtract', layout.projectDirectory.dir('generated'))
        """.trimIndent())
        dir.resolve("generated").mkdirs()
        dir.resolve("build/dootah/extract/requirements").mkdirs()
        dir.resolve("dootah").mkdirs()
        dir.resolve("dootah/contract.json").writeText(ContractJson.write(InstalledContract(runtimeVersion="9",screens=listOf(ScreenContract("screen",emptyList(),emptyList(),emptyList(),emptyList())))))
        return dir
    }
    private fun run(dir:java.io.File,key:String?) = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
        .withEnvironment(System.getenv().filterKeys{it != "DOOTAH_SIGNING_KEY_FILE"} + mapOf("DOOTAH_PUBLISH_TOKEN" to "test-token-".repeat(4)) + (key?.let{mapOf("DOOTAH_SIGNING_KEY_FILE" to it)}?:emptyMap()))
        .withArguments("dootahPublish","-PdootahServer=https://localhost:8443","-PdootahChannel=production","-PdootahRollout=100","--console=plain","--no-configuration-cache").buildAndFail()
    @Test fun `missing signing key fails before bundle or publication`() {
        val result=run(fixture(),null)
        assertTrue(result.output,result.output.contains("DOOTAH_SIGNING_KEY_FILE"))
        assertNull(result.task(":dootahBundle"));assertNull(result.task(":dootahPublish"))
    }
    @Test fun `contract mismatch prevents signing and publication`() {
        val dir=fixture();val key=temp.newFile("external.pem");key.writeText("not needed: validation must fail before signing")
        dir.resolve("build/dootah/extract/requirements/screen.json").writeText(ContractJson.write(BundleRequirements("9",listOf(ScreenRequirements("screen",emptyList(),emptyList(),emptyList(),listOf("drawable:missing"))))))
        val result=run(dir,key.absolutePath)
        assertTrue(result.output,result.output.contains("This bundle asks the installed app"))
        assertNull(result.task(":dootahBundle"));assertNull(result.task(":dootahPublish"))
    }
    @Test fun `absent installed contract never publishes unchecked`() {
        val dir=fixture();dir.resolve("dootah/contract.json").delete()
        val result=run(dir,null);assertTrue(result.output,result.output.contains("contract.json"));assertNull(result.task(":dootahPublish"))
    }
}
