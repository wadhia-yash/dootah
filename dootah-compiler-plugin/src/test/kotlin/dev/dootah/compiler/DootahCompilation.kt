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

fun supportsExplicitPluginOrdering(): Boolean = K2JVMCompilerArguments::class.java.methods
    .any { it.name == "setPluginOrderConstraints" }

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

        val flattened = dev.dootah.compiler.generate.sanitizeForIdentifier(screenId)

        // By prefix, because a screen is identified by its declaration -- the
        // qualified name *and* the parameters declared with it -- and a test
        // naming a fixture should not have to restate the fixture's signature.
        return File(reportDirectory, "extract")
            .listFiles()
            .orEmpty()
            .firstOrNull { file -> file.name.startsWith(flattened) && file.extension == "txt" }
            ?.readText()
    }

    /**
     * The fully qualified names the extraction pass decided it may take over.
     *
     * Read from the per-function discovery records rather than from what was
     * generated, because a function that was eligible and then failed to lower
     * is still a function both passes have to have agreed about.
     */
    fun discoveredScreens(): Set<String> =
        File(reportDirectory, "discovery")
            .takeIf { it.isDirectory }
            ?.listFiles()
            .orEmpty()
            .map { file ->
                file.readLines().mapNotNull { line ->
                    line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }.toMap()
            }
            .filter { it["outcome"] != "INELIGIBLE" }
            .mapNotNull { it["fqName"] }
            .toSet()

    /** The fully qualified names the app's own compilation intercepted. */
    fun interceptedScreens(): Set<String> =
        orderingReport()
            .orEmpty()
            .lineSequence()
            .filter { it.startsWith("discovered=") }
            .map { it.removePrefix("discovered=") }
            .toSet()

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

    /**
     * What the published bundle declares it needs the installed app to have.
     *
     * The file the publish check reads. Asserting on it rather than on the
     * generated source is what makes a test about requirements a test of the
     * thing the build actually compares.
     */
    fun requirementsFragment(): String =
        File(reportDirectory, "requirements")
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.joinToString("\n") { it.readText() }
            .orEmpty()

    fun installedContractFragment(): String =
        File(reportDirectory, "contract").listFiles().orEmpty()
            .joinToString("\n") { it.readText() }

    /** Why the compiler refused to bundle a screen, as the build would report it. */
    fun rejectionReport(): String? =
        File(reportDirectory, "unsupported")
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.singleOrNull()
            ?.readText()

    /**
     * What a lowered screen kept native, as the build would report it.
     *
     * The counterpart to [rejectionReport]. A screen that lowered with a region
     * kept native produces no rejection at all, so asserting on the absence of
     * one would pass for a screen that had no such region either.
     */
    fun degradationReport(): String? =
        File(reportDirectory, "degraded")
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.singleOrNull()
            ?.readText()

    /**
     * What the build decided about each screen it looked at, by name.
     *
     * Read from the same records the coverage tooling reads, so a test asserting
     * an outcome is asserting the thing the corpus figures are counted from.
     */
    fun discoveryOutcomes(): Map<String, String> =
        File(reportDirectory, "discovery")
            .takeIf { it.isDirectory }
            ?.listFiles()
            .orEmpty()
            .mapNotNull { file ->
                val fields = file.readLines()
                    .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }
                    .associate { (key, value) -> key to value }
                val name = fields["fqName"] ?: return@mapNotNull null
                // The fixtures' own declarations only. Every composable in the
                // Compose stubs is recorded too, and none of them is what a test
                // is asserting about.
                name.takeIf { it.startsWith("com.example.") }?.to(fields.getValue("outcome"))
            }
            .toMap()

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
 * Marks the stubs as code Dootah must not take over.
 *
 * In a real build Compose and the Dootah runtime arrive as compiled artifacts on
 * the classpath, so discovery never sees them. Here they are sources in the same
 * compilation, and without this Dootah would find `Text`, `Icon` and its own
 * `DootahRemoteContent` and intercept them -- which is not a thing that can
 * happen to a real app, and would make every fixture assert against noise.
 *
 * The real runtime carries the same annotation for the same reason, so this is
 * the arrangement being tested rather than a convenience for the tests.
 */
private fun List<SourceFile>.asLibraryCode(): List<SourceFile> = map { source ->
    source.copy(contents = "@file:dev.dootah.DootahNative\n\n" + source.contents)
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
    screenFilter: String = "",
    discovery: String? = null,
    realCompose: Boolean = false,
): CompilationResult {

    val sourceDirectory = File(workingDirectory, "src").apply { mkdirs() }
    val outputDirectory = File(workingDirectory, "out").apply { mkdirs() }
    val reportDirectory = File(workingDirectory, "reports").apply { mkdirs() }
    val generatedDirectory = File(workingDirectory, "generated").apply { mkdirs() }

    val stubs = (composeStubs().filterNot { realCompose && it.name == "ComposeStubs.kt" } + if (withDootahRuntime) dootahRuntimeStubs() else emptyList())
        .asLibraryCode()

    val sourceFiles = (stubs + sources).map { source ->
        File(sourceDirectory, source.name).apply {
            parentFile.mkdirs()
            writeText(source.contents)
        }
    }

    // Plugin order on the command line is the order the compiler registers
    // extensions in, which is exactly what these tests need to control.
    // The contract travels with the plugin: a real build resolves it from the
    // plugin's POM, and these tests pass it explicitly for the same reason.
    val dootahJars = listOf(requiredJar("dootah.plugin.jar")) + System.getProperty("dootah.contract.jar").split(File.pathSeparator).map(::File)
    val composeJars = if (realCompose) System.getProperty("dootah.compose.compiler").split(File.pathSeparator).map(::File) else emptyList()
    val pluginClasspath =
        if (dootahFirst) dootahJars + extraPluginClasspath
        else extraPluginClasspath + dootahJars

    val arguments = K2JVMCompilerArguments().apply {
        freeArgs = sourceFiles.map { it.absolutePath }
        destination = outputDirectory.absolutePath
        classpath = testCompileClasspath() + if (realCompose) File.pathSeparator + System.getProperty("dootah.compose.runtime") else ""
        noStdlib = true
        noReflect = true
        moduleName = "dootah-fixture"
        pluginClasspaths = (if (realCompose && !dootahFirst) composeJars + pluginClasspath else pluginClasspath + composeJars)
            .map { it.absolutePath }.toTypedArray()
        if (realCompose && supportsExplicitPluginOrdering()) {
            javaClass.getMethod("setPluginOrderConstraints", Array<String>::class.java)
                .invoke(this, arrayOf("dev.dootah>androidx.compose.compiler.plugins.kotlin"))
        }
        pluginOptions = arrayOf(
            "plugin:dev.dootah:mode=$mode",
            "plugin:dev.dootah:reportDir=${reportDirectory.absolutePath}",
            "plugin:dev.dootah:generatedDir=${generatedDirectory.absolutePath}",
            "plugin:dev.dootah:filter=$screenFilter",
        ) + (discovery?.let { arrayOf("plugin:dev.dootah:discovery=$it") } ?: emptyArray())
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

                // Compose's own, and the reason a screen can write
                // `val (value, setValue) = remember { mutableStateOf(x) }`.
                operator fun component1(): T = value
                operator fun component2(): (T) -> Unit = { value = it }
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

            // Shaped like the real Alignment: the values live on the companion,
            // and the two axes are separate types. Lowering matches on resolved
            // fully qualified names, so a stub that merged the axes or moved the
            // values off the companion would prove nothing about real Compose.
            interface Alignment {

                interface Horizontal

                interface Vertical

                companion object {
                    val Start: Horizontal = object : Horizontal {}
                    val CenterHorizontally: Horizontal = object : Horizontal {}
                    val End: Horizontal = object : Horizontal {}

                    val Top: Vertical = object : Vertical {}
                    val CenterVertically: Vertical = object : Vertical {}
                    val Bottom: Vertical = object : Vertical {}

                    val TopStart: Alignment = object : Alignment {}
                    val TopCenter: Alignment = object : Alignment {}
                    val TopEnd: Alignment = object : Alignment {}
                    val CenterStart: Alignment = object : Alignment {}
                    val Center: Alignment = object : Alignment {}
                    val CenterEnd: Alignment = object : Alignment {}
                    val BottomStart: Alignment = object : Alignment {}
                    val BottomCenter: Alignment = object : Alignment {}
                    val BottomEnd: Alignment = object : Alignment {}
                }
            }
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeUnitStubs.kt",
        contents = """
            package androidx.compose.ui.unit

            // Real `Dp` is arithmetic, and a test that a computed length is
            // refused needs a length that can actually be computed.
            class Dp(val value: Float) {
                operator fun plus(other: Dp): Dp = Dp(value + other.value)
                operator fun times(factor: Float): Dp = Dp(value * factor)
            }

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
            class Color(val packed: ULong) {

                // The named colours the real companion carries. A screen picks
                // one of these as often as it writes a literal, and until they
                // were here no fixture could be written the way a screen is.
                companion object {
                    val Black: Color = Color(0uL)
                    val DarkGray: Color = Color(0uL)
                    val Gray: Color = Color(0uL)
                    val LightGray: Color = Color(0uL)
                    val White: Color = Color(0uL)
                    val Red: Color = Color(0uL)
                    val Green: Color = Color(0uL)
                    val Blue: Color = Color(0uL)
                    val Yellow: Color = Color(0uL)
                    val Cyan: Color = Color(0uL)
                    val Magenta: Color = Color(0uL)
                    val Transparent: Color = Color(0uL)
                }
            }

            fun Color(color: Long): Color = Color(color.toULong())

            interface Shape

            // A property, the way the real one is declared. As an object it
            // resolved to a qualifier instead of a read, which is a different
            // shape of expression and let a fixture pass where a screen failed.
            val RectangleShape: Shape = object : Shape {}
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposePainterStubs.kt",
        contents = """
            package androidx.compose.ui.graphics.painter

            abstract class Painter
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeShapeStubs.kt",
        contents = """
            package androidx.compose.foundation.shape

            import androidx.compose.ui.graphics.Shape

            val CircleShape: Shape = object : Shape {}
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeResourceStubs.kt",
        contents = """
            package androidx.compose.ui.res

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.graphics.painter.Painter

            @Composable
            fun painterResource(id: Int): Painter = object : Painter() {}

            @Composable
            fun stringResource(id: Int): String = ""
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeLayoutStubs.kt",
        contents = """
            package androidx.compose.foundation.layout

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.Dp

            interface ColumnScope {
                fun Modifier.weight(weight: Float): Modifier
            }

            interface RowScope {
                fun Modifier.weight(weight: Float): Modifier
            }

            interface BoxScope

            // Shaped like the real Arrangement: one object, members that
            // implement one axis or both, a spacedBy with the overload that also
            // takes an alignment, and the nested Absolute -- which is here
            // precisely because it is a real arrangement Dootah does not bundle.
            object Arrangement {

                interface Horizontal

                interface Vertical

                val Top: Vertical = object : Vertical {}
                val Bottom: Vertical = object : Vertical {}

                val Start: Horizontal = object : Horizontal {}
                val End: Horizontal = object : Horizontal {}

                val Center: HorizontalOrVertical = object : HorizontalOrVertical {}
                val SpaceBetween: HorizontalOrVertical = object : HorizontalOrVertical {}
                val SpaceAround: HorizontalOrVertical = object : HorizontalOrVertical {}
                val SpaceEvenly: HorizontalOrVertical = object : HorizontalOrVertical {}

                interface HorizontalOrVertical : Horizontal, Vertical

                fun spacedBy(space: Dp): HorizontalOrVertical =
                    object : HorizontalOrVertical {}

                fun spacedBy(space: Dp, alignment: Alignment.Horizontal): Horizontal =
                    object : Horizontal {}

                fun spacedBy(space: Dp, alignment: Alignment.Vertical): Vertical =
                    object : Vertical {}

                object Absolute {
                    val Right: Horizontal = object : Horizontal {}
                }
            }

            @Composable
            fun Column(
                modifier: Modifier = Modifier,
                verticalArrangement: Arrangement.Vertical = Arrangement.Top,
                horizontalAlignment: Alignment.Horizontal = Alignment.Start,
                content: @Composable ColumnScope.() -> Unit,
            ) {}

            @Composable
            fun Row(
                modifier: Modifier = Modifier,
                horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
                verticalAlignment: Alignment.Vertical = Alignment.Top,
                content: @Composable RowScope.() -> Unit,
            ) {}

            @Composable
            fun Box(
                modifier: Modifier = Modifier,
                contentAlignment: Alignment = Alignment.TopStart,
                propagateMinConstraints: Boolean = false,
                content: @Composable BoxScope.() -> Unit,
            ) {}

            class PaddingValues(val all: Dp)

            fun Modifier.padding(all: Dp): Modifier = this

            fun Modifier.padding(horizontal: Dp = Dp(0f), vertical: Dp = Dp(0f)): Modifier = this

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
            import androidx.compose.ui.graphics.RectangleShape
            import androidx.compose.ui.graphics.Shape

            // Takes a shape like the real one, because an icon drawn on a
            // coloured circle is how a selected tool is usually shown.
            fun Modifier.background(color: Color, shape: Shape = RectangleShape): Modifier = this
        """.trimIndent(),
    ),
    // A lazy list, shaped like the real one: a scope that is not a composable
    // receiver, items declared on it, and a content lambda that is not
    // `@Composable`. A screen whose native part is a `LazyColumn` almost always
    // reads that scope -- through `item`, or through an extension the app wrote
    // on it -- so this is the shape that decides whether such a screen can be
    // kept native at all.
    SourceFile(
        name = "ComposeLazyStubs.kt",
        contents = """
            package androidx.compose.foundation.lazy

            import androidx.compose.foundation.layout.PaddingValues
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.Dp

            interface LazyItemScope

            interface LazyListScope {
                fun item(content: @Composable LazyItemScope.() -> Unit)
            }

            class LazyListState

            @Composable
            fun rememberLazyListState(): LazyListState = LazyListState()

            @Composable
            fun LazyColumn(
                modifier: Modifier = Modifier,
                state: LazyListState = LazyListState(),
                contentPadding: PaddingValues = PaddingValues(Dp(0f)),
                content: LazyListScope.() -> Unit,
            ) {}
        """.trimIndent(),
    ),
    SourceFile(
        name = "ComposeMaterialStubs.kt",
        contents = """
            package androidx.compose.material3

            import androidx.compose.foundation.layout.ColumnScope
            import androidx.compose.foundation.layout.RowScope
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.graphics.painter.Painter
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.graphics.Color

            @Composable
            fun Text(
                text: String,
                modifier: Modifier = Modifier,
                color: Color = Color(0L),
                fontWeight: Int = 0,
                style: TextStyle = TextStyle(),
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
            fun Icon(
                name: String,
                modifier: Modifier = Modifier,
                tint: Color = Color(0L),
            ) {}

            // The overload a real screen calls: a drawable and the words that
            // describe it, both named rather than carried. Declared beside the
            // one above because Compose declares several, and because an
            // adapter is named by the declaration it was built from -- two
            // overloads are two components, and have to be.
            @Composable
            fun Icon(
                painter: Painter,
                contentDescription: String?,
                modifier: Modifier = Modifier,
                tint: Color = Color(0L),
            ) {}

            // Declared on a layout scope, the way Compose declares several of
            // its own components. The receiver shifts every argument's position,
            // and the defaults mean a call supplies only some of them -- which
            // is what makes naming this call the same way twice a real problem.
            @Composable
            fun ColumnScope.Badge(
                label: String,
                tint: Color = Color(0L),
                outlined: Boolean = false,
            ) {}

            // A component that wraps content and is given a click handler --
            // the shape almost every real toolbox is built out of.
            @Composable
            fun IconButton(
                onClick: () -> Unit,
                modifier: Modifier = Modifier,
                content: @Composable () -> Unit,
            ) {}

            // Content handed the scope it is laid out in, the way the real
            // `Button` takes `@Composable RowScope.() -> Unit`. Content that
            // takes an argument is still content: asking for none classified
            // this one as a handler and copied the label inside it into a
            // lambda with no composer to draw it with.
            @Composable
            fun Chip(
                onClick: () -> Unit,
                modifier: Modifier = Modifier,
                content: @Composable RowScope.() -> Unit,
            ) {}

            // Reached through an object, the way icon packs are usually
            // declared. Has a receiver like Badge, but reads nothing around it,
            // so it is a component Dootah can keep native -- and naming it
            // correctly means counting past the receiver.
            object Icons {
                @Composable
                fun Star(label: String, tint: Color = Color(0L)) {}
            }

            class ColorScheme {
                val primary: Color = Color(0L)
                val inversePrimary: Color = Color(0L)
            }

            // A composable that produces a *value* instead of placing anything,
            // the way the real `MaterialTheme.colorScheme` does. It is read, not
            // drawn: registering it as a component gave the app an adapter whose
            // whole body was an expression it then threw away.
            class TextStyle

            class Typography {
                val titleLarge: TextStyle = TextStyle()
            }

            object MaterialTheme {
                val colorScheme: ColorScheme
                    @Composable get() = ColorScheme()

                // Read the same way, and the reason a styled `Text` is a region
                // kept as written rather than one Dootah describes.
                val typography: Typography
                    @Composable get() = Typography()
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

            import androidx.compose.foundation.lazy.LazyListScope
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier

            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.graphics.Shape
            import androidx.compose.ui.graphics.painter.Painter
            import androidx.compose.ui.unit.Dp

            class DootahArguments
            class DootahCallbacks

            class DootahAdapter(val id: String, val parameters: String)
            class DootahAdapters
            class DootahCapability(val id: String)
            class DootahCapabilities
            class DootahHandles
            class DootahResources
            class DootahAnchors
            class DootahBuilders
            class DootahBuilder(val id: String)
            class DootahNativeBindings

            // The adapter surface. An adapter is given its arguments rather than
            // capturing them, which is what lets one registration serve any
            // number of instances.
            class DootahProps {
                fun string(name: String): String = ""
                fun boolean(name: String): Boolean = false
                fun int(name: String): Int = 0
                fun long(name: String): Long = 0L
                fun float(name: String): Float = 0f
                fun double(name: String): Double = 0.0
                fun modifier(name: String): Modifier = Modifier
                fun color(name: String): Color = Color(0L)
                fun dp(name: String): Dp = Dp(0f)
                fun shape(name: String): Shape? = null
                fun painter(name: String): Painter? = null
                fun stringOrNull(name: String): String? = null
                fun handle(name: String): Any? = null
                fun callback(name: String): () -> Unit = {}
                fun callback1(name: String): (Any?) -> Unit = {}
                @Composable fun children(name: String) {}
                fun entries(name: String): LazyListScope.() -> Unit = {}
            }

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
                signatures: String,
                vararg callbacks: Any,
            ): DootahCallbacks = DootahCallbacks()

            fun dootahAdapter(
                id: String,
                parameters: String,
                content: @Composable (DootahProps) -> Unit,
            ): DootahAdapter = DootahAdapter(id, parameters)

            fun dootahAdapters(vararg adapters: DootahAdapter): DootahAdapters = DootahAdapters()

            fun dootahCapability(
                id: String,
                action: (List<Any?>) -> Unit,
            ): DootahCapability = DootahCapability(id)

            fun dootahCapabilities(
                vararg capabilities: DootahCapability,
            ): DootahCapabilities = DootahCapabilities()

            fun dootahHandle(name: String, value: Any?): Pair<String, Any?> = name to value

            fun dootahHandles(vararg handles: Pair<String, Any?>): DootahHandles = DootahHandles()

            fun dootahResource(key: String, id: Int): Pair<String, Int> = key to id

            fun dootahResources(vararg resources: Pair<String, Int>): DootahResources =
                DootahResources()

            fun dootahAnchor(name: String, value: Dp): Pair<String, Dp> = name to value

            fun dootahAnchors(vararg anchors: Pair<String, Dp>): DootahAnchors = DootahAnchors()

            fun dootahBuilder(id: String, entries: LazyListScope.() -> Unit): DootahBuilder =
                DootahBuilder(id)

            fun dootahBuilders(vararg builders: DootahBuilder): DootahBuilders = DootahBuilders()

            fun dootahBindings(
                adapters: DootahAdapters,
                capabilities: DootahCapabilities,
                handles: DootahHandles,
                resources: DootahResources,
                anchors: DootahAnchors,
                builders: DootahBuilders,
            ): DootahNativeBindings = DootahNativeBindings()

            @Composable
            fun rememberDootahScreen(
                screenId: String,
                arguments: DootahArguments,
                callbacks: DootahCallbacks,
                bindings: DootahNativeBindings,
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
 * and the real optional native opt-out annotation this module already depends on.
 */
private fun testCompileClasspath(): String =
    System.getProperty("dootah.fixture.classpath")
