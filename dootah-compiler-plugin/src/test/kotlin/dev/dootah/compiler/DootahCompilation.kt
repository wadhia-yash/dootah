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
    val generatedDirectory: File,
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

    /** Every generated bundle source file, keyed by name. */
    fun generatedSources(): Map<String, String> =
        generatedDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to it.readText() }

    /** The generated screen implementation, which there is exactly one of. */
    fun generatedScreen(): String =
        generatedSources()
            .entries
            .single { it.key.startsWith("DootahScreen_") }
            .value

    /** Why the compiler refused to bundle a screen, as the build would report it. */
    fun rejectionReport(): String? =
        File(reportDirectory, "unsupported")
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.singleOrNull()
            ?.readText()

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
    val generatedDirectory = File(workingDirectory, "generated").apply { mkdirs() }

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
            "plugin:dev.dootah:generatedDir=${generatedDirectory.absolutePath}",
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
        generatedDirectory = generatedDirectory,
    )
}

/**
 * Stand-ins for the Compose declarations the plugin reasons about.
 *
 * Stubs rather than the real artifacts so these tests stay independent of a
 * Compose release and of Android packaging. Every one mirrors the real
 * declaration's fully qualified name and the shape of its parameter list,
 * because those are exactly what the plugin resolves against -- and the same
 * names are verified against the real artifacts by the Cahier run.
 */
private fun composeStubs(): List<SourceFile> = listOf(
    SourceFile(
        name = "ComposeStubs.kt",
        contents = """
            package androidx.compose.runtime

            import kotlin.reflect.KProperty

            @Target(
                AnnotationTarget.FUNCTION,
                AnnotationTarget.TYPE,
                AnnotationTarget.TYPE_PARAMETER,
                AnnotationTarget.PROPERTY_GETTER,
            )
            annotation class Composable

            interface Composer

            @Composable
            fun <T> remember(calculation: () -> T): T = calculation()

            interface MutableState<T> {
                var value: T
            }

            fun <T> mutableStateOf(initial: T): MutableState<T> =
                object : MutableState<T> {
                    override var value: T = initial
                }

            operator fun <T> MutableState<T>.getValue(owner: Any?, property: KProperty<*>): T =
                value

            operator fun <T> MutableState<T>.setValue(
                owner: Any?,
                property: KProperty<*>,
                newValue: T,
            ) {
                value = newValue
            }
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeUiStubs.kt",
        contents = """
            package androidx.compose.ui

            interface Modifier {
                companion object : Modifier
            }
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeUnitStubs.kt",
        contents = """
            package androidx.compose.ui.unit

            class Dp(val value: Float)

            val Int.dp: Dp get() = Dp(toFloat())
            val Float.dp: Dp get() = Dp(this)
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeGraphicsStubs.kt",
        contents = """
            package androidx.compose.ui.graphics

            // Compose declares Color as a value class over ULong with a
            // top-level Long factory. The factory is what a call site resolves
            // to, and what Dootah reads.
            class Color(val packed: ULong)

            fun Color(color: Long): Color = Color(color.toULong())
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeLayoutStubs.kt",
        contents = """
            package androidx.compose.foundation.layout

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.Dp

            interface ColumnScope {
                fun Modifier.weight(weight: Float): Modifier
            }

            interface RowScope {
                fun Modifier.weight(weight: Float): Modifier
            }

            interface BoxScope

            @Composable
            fun Column(
                modifier: Modifier = Modifier,
                verticalArrangement: Int = 0,
                content: @Composable ColumnScope.() -> Unit,
            ) {}

            @Composable
            fun Row(
                modifier: Modifier = Modifier,
                verticalAlignment: Int = 0,
                content: @Composable RowScope.() -> Unit,
            ) {}

            @Composable
            fun Box(
                modifier: Modifier = Modifier,
                content: @Composable BoxScope.() -> Unit,
            ) {}

            fun Modifier.padding(all: Dp): Modifier = this

            fun Modifier.padding(horizontal: Dp, vertical: Dp): Modifier = this

            fun Modifier.padding(
                start: Dp = Dp(0f),
                top: Dp = Dp(0f),
                end: Dp = Dp(0f),
                bottom: Dp = Dp(0f),
            ): Modifier = this

            fun Modifier.fillMaxWidth(fraction: Float = 1f): Modifier = this

            fun Modifier.fillMaxHeight(fraction: Float = 1f): Modifier = this

            fun Modifier.fillMaxSize(fraction: Float = 1f): Modifier = this

            fun Modifier.size(size: Dp): Modifier = this

            fun Modifier.size(width: Dp, height: Dp): Modifier = this

            fun Modifier.width(width: Dp): Modifier = this

            fun Modifier.height(height: Dp): Modifier = this
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeFoundationStubs.kt",
        contents = """
            package androidx.compose.foundation

            import androidx.compose.ui.Modifier
            import androidx.compose.ui.graphics.Color

            fun Modifier.background(color: Color): Modifier = this
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeMaterialStubs.kt",
        contents = """
            package androidx.compose.material3

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.graphics.Color

            @Composable
            fun Text(
                text: String,
                modifier: Modifier = Modifier,
                color: Color = Color(0L),
                fontWeight: Int = 0,
            ) {}

            @Composable
            fun Button(
                onClick: () -> Unit,
                modifier: Modifier = Modifier,
                enabled: Boolean = true,
                content: @Composable () -> Unit,
            ) {}

            // Stands in for the styled components a real screen leans on, which
            // Dootah keeps native rather than reimplementing.
            @Composable
            fun Icon(name: String, tint: Color = Color(0L)) {}

            // Reached through an object, the way icon packs are usually
            // declared. The dispatch receiver shifts every argument's position,
            // so naming this call correctly means counting past it.
            object Icons {
                @Composable
                fun Star(label: String, tint: Color = Color(0L)) {}
            }
        """.trimIndent(),
    ),
)

/**
 * Stand-ins for the Dootah runtime surface the transform calls into.
 *
 * Stubs rather than the real `dootah-android` artifact so these tests do not
 * need an Android AAR or a Compose release on the classpath. The transform
 * resolves this surface by fully qualified name and reads the declared vararg
 * element types out of it, which is what is under test.
 */
private fun dootahRuntimeStubs(): List<SourceFile> = listOf(
    SourceFile(
        name = "DootahRuntimeStubs.kt",
        contents = """
            package com.dootah.ui

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier

            class DootahArguments
            class DootahCallbacks
            class DootahSlots

            class DootahScreenState(val screenId: String)

            fun dootahArguments(names: String, vararg values: Any?): DootahArguments =
                DootahArguments()

            fun dootahModifiedArguments(
                modifier: Modifier,
                names: String,
                vararg values: Any?,
            ): DootahArguments = DootahArguments()

            fun dootahCallbacks(
                names: String,
                vararg callbacks: () -> Unit,
            ): DootahCallbacks = DootahCallbacks()

            fun dootahSlots(
                ids: String,
                vararg slots: @Composable () -> Unit,
            ): DootahSlots = DootahSlots()

            @Composable
            fun rememberDootahScreen(
                screenId: String,
                arguments: DootahArguments,
                callbacks: DootahCallbacks,
                slots: DootahSlots,
            ): DootahScreenState = DootahScreenState(screenId)

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
