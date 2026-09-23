package dev.dootah.contract

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceContractFragmentsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `source set removal prunes even when the file still exists`() {
        val root = temporary.newFolder()
        val source = root.resolve("Screen.kt").apply { writeText("source") }
        val reports = root.resolve("reports")
        SourceContractFragments.replace(reports, root, source, InstalledContract(runtimeVersion = "9", screens = emptyList()))
        val owner = reports.resolve("contract-sources").listFiles()!!.single()
        reports.resolve("contract/${owner.nameWithoutExtension}.json").writeText("obsolete")
        SourceContractFragments.prune(reports, root, emptySet())
        assertTrue(source.exists())
        assertEquals(0, reports.resolve("contract").listFiles()!!.size)
        assertEquals(0, reports.resolve("contract-sources").listFiles()!!.size)
    }

    @Test fun `relocated cache ownership remains attached to the source`() {
        val original = temporary.newFolder()
        val source = original.resolve("Screen.kt").apply { writeText("source") }
        SourceContractFragments.replace(original.resolve("reports"), original, source, InstalledContract(runtimeVersion = "9", screens = emptyList()))
        val relocated = temporary.newFolder()
        original.copyRecursively(relocated, overwrite = true)
        original.deleteRecursively()
        val reports = relocated.resolve("reports")
        SourceContractFragments.prune(reports, relocated, setOf(relocated.resolve("Screen.kt")))
        assertEquals("Screen.kt", reports.resolve("contract-sources").listFiles()!!.single().readText())
    }

    @Test fun `legacy unowned fragments are never mistaken for installed capabilities`() {
        val root = temporary.newFolder()
        val reports = root.resolve("reports")
        val legacy = reports.resolve("contract/oldScreen.json").apply { parentFile.mkdirs(); writeText("old") }
        SourceContractFragments.prune(reports, root)
        assertFalse(legacy.exists())
    }
}
