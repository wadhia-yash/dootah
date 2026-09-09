package dev.dootah.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File

/** One source file in a test fixture. */
data class SourceFile(val name: String, val contents: String)

/** What a fixture compilation produced. */
class CompilationResult(
    val exitCode: ExitCode,
    val messages: List<String>,
    private val reportDirectory: File,
    private val outputDirectory: File,
) {

    val succeeded: Boolean get() = exitCode == ExitCode.OK

    /** The ordering guard's report, or null when the plugin wrote none. */
    fun orderingReport(): String? =
        File(reportDirectory, "dootah-ordering.txt")
            .takeIf { it.exists() }
            ?.readText()

    /** The extraction pass's record for one screen, or null when it found none. */
    fun extractionReport(screenId: String): String? {

        val flattened = screenId.map { character ->
            if (character.isLetterOrDigit() || character == '-') character else '_'
        }.joinToString("")

        return File(reportDirectory, "extract/$flattened.txt")
            .takeIf { it.exists() }
            ?.readText()
    }

    fun messagesContaining(fragment: String): List<String> =
        messages.filter { it.contains(fragment) }

    /**
     * A compiled class's bytes as latin-1 text.
     *
     * Method and string constants live in the constant pool as UTF-8, so
     * searching this text is a direct way to assert what the compiler actually
     * emitted -- stronger evidence than the plugin's own report, which only says
     * what the plugin believed it did.
     */
    fun compiledClassText(relativePath: String): String {

        val classFile = File(outputDirectory, relativePath)

        require(classFile.exists()) {
            val produced = outputDirectory.walkTopDown()
                .filter { it.extension == "class" }
                .joinToString("\n") { it.relativeTo(outputDirectory).path }
            "No class at '$relativePath'. Produced:\n$produced"
        }

        return String(classFile.readBytes(), Charsets.ISO_8859_1)
    }
}

/**
 * Runs the real Kotlin compiler over [sources] with the Dootah plugin attached.
 *
 * A real compilation rather than a mocked one: the whole point of these tests is
 * that the plugin behaves correctly inside the compiler, which a hand-built IR
 * fixture cannot establish.
 */
fun compileWithDootah(
    workingDirectory: File,
    sources: List<SourceFile>,
    mode: String = "intercept",
    extraPluginClasspath: List<File> = emptyList(),
    dootahFirst: Boolean = true,
    withDootahRuntime: Boolean = true,
): CompilationResult {

    val sourceDirectory = File(workingDirectory, "src").apply { mkdirs() }
    val outputDirectory = File(workingDirectory, "out").apply { mkdirs() }
    val reportDirectory = File(workingDirectory, "reports").apply { mkdirs() }

    val stubs = composeStubs() + if (withDootahRuntime) dootahRuntimeStubs() else emptyList()

    val sourceFiles = (stubs + sources).map { source ->
        File(sourceDirectory, source.name).apply {
            parentFile.mkdirs()
            writeText(source.contents)
        }
    }

    // Plugin order on the command line is the order the compiler registers
    // extensions in, which is exactly what these tests need to control.
    val dootahJar = requiredJar("dootah.plugin.jar")
    val pluginClasspath =
        if (dootahFirst) listOf(dootahJar) + extraPluginClasspath
        else extraPluginClasspath + listOf(dootahJar)

    val arguments = K2JVMCompilerArguments().apply {
        freeArgs = sourceFiles.map { it.absolutePath }
        destination = outputDirectory.absolutePath
        classpath = testCompileClasspath()
        noStdlib = true
        noReflect = true
        moduleName = "dootah-fixture"
        pluginClasspaths = pluginClasspath.map { it.absolutePath }.toTypedArray()
        pluginOptions = arrayOf(
            "plugin:dev.dootah:mode=$mode",
            "plugin:dev.dootah:reportDir=${reportDirectory.absolutePath}",
        )
    }

    val collected = mutableListOf<String>()

    val collector = object : MessageCollector {

        private var sawError = false

        override fun clear() = Unit

        override fun hasErrors(): Boolean = sawError

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            if (severity.isError) sawError = true
            collected += "$severity: $message"
        }
    }

    val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)

    return CompilationResult(
        exitCode = exitCode,
        messages = collected.toList(),
        reportDirectory = reportDirectory,
        outputDirectory = outputDirectory,
    )
}

/**
 * Minimal stand-ins for the Compose declarations the plugin reasons about:
 * the annotation and composer it detects lowering by, and the four composables
 * Milestone 1 supports.
 *
 * Stubs rather than the real artifacts so these tests stay independent of a
 * Compose release and of Android packaging. The plugin identifies all of them by
 * fully qualified name, which is precisely what is being tested -- and the same
 * names are verified against the real artifacts by the Cahier run.
 */
private fun composeStubs(): List<SourceFile> = listOf(
    SourceFile(
        name = "ComposeStubs.kt",
        contents = """
            package androidx.compose.runtime

            @Target(
                AnnotationTarget.FUNCTION,
                AnnotationTarget.TYPE,
                AnnotationTarget.TYPE_PARAMETER,
                AnnotationTarget.PROPERTY_GETTER,
            )
            annotation class Composable

            interface Composer
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeLayoutStubs.kt",
        contents = """
            package androidx.compose.foundation.layout

            import androidx.compose.runtime.Composable

            interface ColumnScope
            interface RowScope

            @Composable
            fun Column(content: @Composable ColumnScope.() -> Unit) {}

            @Composable
            fun Row(content: @Composable RowScope.() -> Unit) {}
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeMaterialStubs.kt",
        contents = """
            package androidx.compose.material3

            import androidx.compose.runtime.Composable

            @Composable
            fun Text(text: String) {}

            @Composable
            fun Button(onClick: () -> Unit, content: @Composable () -> Unit) {}
        """.trimIndent(),
    ),
)

/**
 * Stand-ins for the Dootah runtime surface the transform calls into.
 *
 * Stubs rather than the real `dootah-android` artifact so these tests do not
 * need an Android AAR or a Compose release on the classpath. The transform
 * resolves this surface by fully qualified name, which is what is under test.
 */
private fun dootahRuntimeStubs(): List<SourceFile> = listOf(
    SourceFile(
        name = "DootahRuntimeStubs.kt",
        contents = """
            package com.dootah.ui

            import androidx.compose.runtime.Composable

            class DootahScreenState(val screenId: String)

            @Composable
            fun rememberDootahScreen(screenId: String): DootahScreenState =
                DootahScreenState(screenId)

            fun hasRemoteImplementation(state: DootahScreenState): Boolean = false

            @Composable
            fun DootahRemoteContent(state: DootahScreenState) {}
        """.trimIndent(),
    ),
)

private fun requiredJar(property: String): File {

    val path = System.getProperty(property)
        ?: error(
            "System property '$property' is not set. The Gradle test task supplies " +
                "it; running these tests outside Gradle is not supported."
        )

    return File(path).also {
        require(it.exists()) { "Jar named by '$property' does not exist: $path" }
    }
}

/**
 * The fixture's compile classpath.
 *
 * Reuses the test runtime classpath so the fixtures see the same kotlin-stdlib
 * and the real `@Bundlable` annotation this module already depends on.
 */
private fun testCompileClasspath(): String =
    System.getProperty("java.class.path")
