package dev.dootah.gradle

import dev.dootah.contract.ContractJson
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Actual KGP incremental invocations, including its up-to-date and build-cache paths. */
@RunWith(Parameterized::class)
class IncrementalContractConsumerTest(private val version: String) {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `structural edits retain exactly the clean compilation contract`() {
        val root = temporary.root
        fun write(path: String, text: String) = root.resolve(path).apply {
            parentFile.mkdirs(); writeText(text.trimIndent())
        }
        val jars = System.getProperty("dootah.test.compiler.$version").split(File.pathSeparator)
            .map(::File).filter { it.name.startsWith("dootah-") }
        jars.forEach { jar ->
            val artifact = jar.name.removeSuffix("-$DOOTAH_VERSION.jar")
            val directory = root.resolve("repo/dev/dootah/$artifact/$DOOTAH_VERSION").apply { mkdirs() }
            jar.copyTo(directory.resolve(jar.name))
            val dependencies = when {
                artifact.startsWith("dootah-compiler-plugin") -> listOf("dootah-compiler-core", "dootah-contract")
                artifact == "dootah-compiler-core" -> listOf("dootah-contract")
                else -> emptyList()
            }
            directory.resolve("$artifact-$DOOTAH_VERSION.pom").writeText("""
                <project><modelVersion>4.0.0</modelVersion><groupId>dev.dootah</groupId>
                <artifactId>$artifact</artifactId><version>$DOOTAH_VERSION</version><dependencies>
                ${dependencies.joinToString("") { "<dependency><groupId>dev.dootah</groupId><artifactId>$it</artifactId><version>$DOOTAH_VERSION</version></dependency>" }}
                </dependencies></project>
            """.trimIndent())
        }
        write("settings.gradle", "rootProject.name = 'incremental-contract'")
        write("build.gradle", """
            plugins { id 'org.jetbrains.kotlin.jvm'; id 'dev.dootah' }
            repositories { maven { url = uri('repo') }; mavenCentral() }
            configurations.implementation.dependencies.removeAll { it.group == 'dev.dootah' }
            sourceSets { debug {} }
            kotlin.sourceSets.debug.kotlin.srcDir('src/main/kotlin')
        """)
        write("src/main/kotlin/Composable.kt", """
            package androidx.compose.runtime
            @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
            annotation class Composable
        """)
        write("src/main/kotlin/Text.kt", """
            package androidx.compose.material3
            import androidx.compose.runtime.Composable
            @Composable fun Text(text: String) {}
        """)
        // Minimal native boundary for this JVM build fixture. No runtime behavior
        // is under test; real Compose and Android execution have separate checks.
        write("src/main/kotlin/Runtime.kt", """
            package com.dootah.ui
            import androidx.compose.runtime.Composable
            class DootahProps { fun string(name: String): String = "" }
            fun dootahArguments(names: String, vararg values: Any?) {}
            fun dootahModifiedArguments(modifier: Any, names: String, vararg values: Any?) {}
            fun dootahCallbacks(names: String, signatures: String, vararg callbacks: Any) {}
            fun dootahAdapter(id: String, parameters: String, content: @Composable (DootahProps) -> Unit) {}
            fun dootahAdapters(vararg adapters: Unit) {}
            fun dootahCapability(id: String, action: (List<Any?>) -> Unit) {}
            fun dootahCapabilities(vararg capabilities: Unit) {}
            fun dootahHandle(name: String, value: Any?) {}
            fun dootahHandles(vararg handles: Unit) {}
            fun dootahResource(key: String, id: Int) {}
            fun dootahResources(vararg resources: Unit) {}
            fun dootahAnchor(name: String, value: Any) {}
            fun dootahAnchors(vararg anchors: Unit) {}
            fun dootahBuilder(id: String, entries: () -> Unit) {}
            fun dootahBuilders(vararg builders: Unit) {}
            fun dootahBindings(adapters: Unit, capabilities: Unit, handles: Unit,
                resources: Unit, anchors: Unit, builders: Unit) {}
            @Composable fun rememberDootahScreen(id: String, arguments: Unit, callbacks: Unit, bindings: Unit) {}
            fun hasRemoteImplementation(state: Unit): Boolean = false
            @Composable fun DootahRemoteContent(state: Unit) {}
        """)
        fun screen(name: String, body: String) = write("src/main/kotlin/$name.kt", """
            package example
            import androidx.compose.runtime.Composable
            import androidx.compose.material3.Text
            $body
        """)
        fun run(vararg tasks: String) = GradleRunner.create().withProjectDir(root)
            .withPluginClasspath(dootahPluginClasspath() + kotlinGradlePluginClasspath("dootah.test.kgp.$version"))
            .withArguments(*tasks, "--build-cache", "--no-configuration-cache", "--stacktrace").build()
        fun contract() = ContractJson.readContract(root.resolve("dootah/contract.json").readText()).screens.sortedBy { it.id }
        screen("Keep", "@Composable fun Keep() { Text(\"keep\") }")
        val original = "@Composable fun Original(value: String) { Text(value) }"
        screen("Edited", original)
        run("dootahRecordContract")
        val unchanged = run("dootahRecordContract")
        assertEquals(unchanged.output, TaskOutcome.UP_TO_DATE, unchanged.task(":compileDebugKotlin")?.outcome)
        val baseline = contract()
        run("clean", "dootahRecordContract")
        assertEquals(baseline, contract())
        listOf(
            "fun ordinaryFunction() = Unit",
            "@Composable fun Renamed(value: String) { Text(value) }",
            "@Composable fun Original(value: Int) { Text(\"changed parameter\") }",
            original + "\n@Composable fun Original(value: Int) { Text(\"overload\") }",
        ).forEach { edit ->
            screen("Edited", edit)
            run("dootahRecordContract")
            val incremental = contract()
            run("clean", "dootahRecordContract")
            assertEquals(edit, contract(), incremental)
        }
        assertTrue(root.resolve("src/main/kotlin/Edited.kt").delete())
        run("dootahRecordContract")
        val deleted = contract()
        run("clean", "dootahRecordContract")
        assertEquals(contract(), deleted)
        assertEquals(1, deleted.count { it.id.startsWith("example.") })
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "Kotlin {0}")
        fun versions() = listOf(arrayOf("2.0.20"), arrayOf("2.3.20"))
    }
}
