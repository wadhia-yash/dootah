package dev.dootah.compiler

import dev.dootah.contract.ContractJson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Replay partial compiler invocations: untouched sources must retain their contracts. */
class IncrementalContractTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `partial compiles match clean contracts after structural source edits`() {
        val unchanged = source("Unchanged.kt", "@Composable fun Untouched() { Text(\"keep\") }")
        val baseline = source("Edited.kt", "@Composable fun Original(value: String) { Text(value) }")
        val edits = listOf(
            baseline,
            source("Edited.kt", "fun noScreen() = Unit"),
            source("Edited.kt", "@Composable fun Renamed(value: String) { Text(value) }"),
            source("Edited.kt", "@Composable fun Original(value: Int) { Text(\"changed\") }"),
            source("Edited.kt", "@Composable fun Original(value: String) { Text(value) }\n@Composable fun Original(value: Int) { Text(\"overload\") }"),
        )
        edits.forEach { edit ->
            val incremental = temporary.newFolder()
            compile(incremental, listOf(unchanged, baseline))
            compile(incremental, listOf(edit))
            val clean = temporary.newFolder()
            compile(clean, listOf(unchanged, edit))
            assertEquals(edit.contents, contract(clean), contract(incremental))
        }
    }

    @Test fun `deleted and renamed source files lose their old capabilities`() {
        val directory = temporary.newFolder()
        val keep = source("Keep.kt", "@Composable fun Keep() { Text(\"keep\") }")
        val removed = source("Remove.kt", "@Composable fun Removed() { Text(\"remove\") }")
        compile(directory, listOf(keep, removed))
        assertTrue(directory.resolve("src/Remove.kt").delete())
        compile(directory, listOf(keep))
        val clean = temporary.newFolder()
        compile(clean, listOf(keep))
        assertEquals(contract(clean), contract(directory))
        compile(directory, listOf(removed.copy(name = "Renamed.kt")))
        val renamedClean = temporary.newFolder()
        compile(renamedClean, listOf(keep, removed.copy(name = "Renamed.kt")))
        assertEquals(contract(renamedClean), contract(directory))
    }

    private fun compile(directory: File, files: List<SourceFile>) {
        val result = compileWithDootah(directory, files)
        assertTrue(result.messages.joinToString("\n"), result.succeeded)
    }
    private fun contract(directory: File) = directory.resolve("reports/contract").listFiles().orEmpty()
        .filter { it.extension == "json" }.flatMap { ContractJson.readContract(it.readText()).screens }.sortedBy { it.id }
    private fun source(name: String, body: String) = SourceFile(name,
        "package com.example\nimport androidx.compose.runtime.Composable\nimport androidx.compose.material3.Text\n$body")
}
