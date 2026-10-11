package dev.dootah.publish

import dev.dootah.adapters.AdapterGen
import org.junit.Test
import static org.junit.Assert.*

class AdapterGenTest {
    static final File PROOF = new File(System.getProperty('repository'), 'v2/android-sdk/tests/adapter-proof')

    static AdapterGen.Result generate(String path, String source) {
        new SourceAnalyzer().withCloseable { AdapterGen.generate(it.inspect(path, source).file, path) }
    }

    static String render(String path, String source) {
        new SourceAnalyzer().withCloseable { a ->
            def file = a.inspect(path, source).file
            AdapterGen.render(file, AdapterGen.generate(file, path))
        }
    }

    static String fixture() { new File(PROOF, 'badges/src/main/kotlin/dev/dootah/proof/badges/Badges.kt').getText('UTF-8') }

    @Test void adaptersAreOnePerCallShapeAndTypedFromHookInputs() {
        def result = generate('src/main/kotlin/Badges.kt', fixture())
        assertEquals([], result.refused)
        assertEquals(4, result.adapters.size())
        assertTrue(result.adapters.every { it.signature() == 'String' && it.id ==~ AdapterGen.ID_PATTERN })
        assertTrue(result.adapters.every { it.callee == 'dev.dootah.proof.badges.Badge' })
        def source = render('src/main/kotlin/Badges.kt', fixture())
        // Defaults stay Kotlin's: omitted arguments never appear in the generated call; frozen text is copied.
        assertFalse(source.contains('paletteDefault'))
        assertTrue(source, source.contains('Badge(text = (dootahCall.arguments[0] as kotlin.String), shape = FrozenShape)'))
        assertTrue(source.contains('@file:kotlin.jvm.JvmName("' + result.facade + '")'))
    }

    @Test void identityIsStableAcrossUnrelatedEditsAndBoundToTheFile() {
        def base = fixture()
        def edited = '// an unrelated edit\n\n\n' + base.replace('internal const val FrozenShape', 'val unrelated = 1\ninternal const val FrozenShape')
        assertEquals(generate('a/Badges.kt', base).adapters*.id, generate('a/Badges.kt', edited).adapters*.id)
        // The same text in another file may resolve differently, so it never shares an identity.
        assertTrue(generate('b/Badges.kt', base).adapters*.id.disjoint(generate('a/Badges.kt', base).adapters*.id))
    }

    @Test void refusesWhatAnAdapterCannotCarry() {
        def source = '''package demo
import androidx.compose.runtime.Composable
private val Hidden = 1
@Composable fun Hook(count: Int, name: String, maybe: String?) {
    val local = name.length
    Card(count + 1)
    Card(onClick = { count })
    Card(text = name) { Inner(name) }
    Card(local)
    Card(maybe)
    Card(Hidden)
    Card(this)
    Row { Card(text = name) }
    Card(text = name)
}
@Composable fun NotAHook(items: List<String>) { Card(text = "x") }
@Composable fun String.Extension() { Card(text = "x") }
'''
        def result = generate('Hook.kt', source)
        def plain = result.adapters.find { !it.container }, row = result.adapters.find { it.callee == 'demo.Row' }
        def card = result.adapters.find { it.container && it.callee == 'demo.Card' }
        assertEquals(3, result.adapters.size())
        assertEquals('String', plain.signature())
        // Containers: Row { Card(text = name) } and Card(text = name) { Inner(name) }; children are scoped to them.
        assertEquals('@content', row.signature())
        assertEquals('String,@content', card.signature())
        assertTrue(row.children.keySet().every { it.startsWith(row.id + '/demo.Card#') } && row.children.size() == 1)
        assertTrue(card.children.keySet().every { it.startsWith(card.id + '/demo.Inner#') } && card.children.size() == 1)
        assertEquals(6, result.refused.size())
        assertTrue(render('Hook.kt', source).contains('Card(text = (dootahCall.arguments[0] as kotlin.String))'))
    }

    @Test void resolvesCalleesThroughImportsAndRefusesAmbiguity() {
        def aliased = '''package demo
import androidx.compose.runtime.Composable
import lib.ui.Badge as Chip
@Composable fun Hook(n: Int) { Chip(text = "$n") }
'''
        def shape = generate('Hook.kt', aliased).adapters.first()
        assertEquals('lib.ui.Badge', shape.callee)
        assertEquals('Badge', shape.calleeName())
        def star = '''package demo
import androidx.compose.runtime.Composable
import lib.ui.*
@Composable fun Hook(n: Int) { Chip(text = "$n") }
'''
        def refused = generate('Hook.kt', star)
        assertTrue(refused.adapters.empty)
        assertTrue(refused.refused.first().contains('Wildcard'))
    }

    @Test void indexIsAlwaysGenerated() {
        assertTrue(AdapterGen.index([]).contains('dev.dootah.runtime.NativeAdapterRegistry.Empty'))
        def result = generate('src/main/kotlin/Badges.kt', fixture())
        assertTrue(AdapterGen.index([result]).contains('dev.dootah.proof.badges.' + result.registry))
    }

    /** Children are copied inside the installed content lambda, so implicit receivers (RowScope) resolve as in source. */
    @Test void containerCopiesItsContentLambdaWithScopedChildren() {
        def source = '''package demo
import androidx.compose.runtime.Composable
@Composable fun Details(title: String, count: Int, wide: Boolean) {
    Row(modifier = Modifier.padding(4.dp)) {
        Text(text = title, maxLines = 1)
        if (wide) {
            Text(text = "$count", modifier = Modifier.weight(1f))
        } else Spacer(Modifier.width(2.dp))
    }
}
@Composable fun Refused(title: String) { Row { val x = title; Text(x) } }
'''
        def result = generate('Details.kt', source)
        assertEquals(1, result.adapters.size())
        def row = result.adapters[0]
        assertEquals(3, row.children.size())
        assertEquals(['String', 'String', ''] as Set, row.children.values()*.signature() as Set)
        assertTrue(result.refused.toString(), result.refused.any { it.startsWith('Refused: Row: ') && it.endsWith('Unsupported content statement') })
        def text = render('Details.kt', source)
        assertTrue(text, text.contains('private fun ' + row.method + '(dootahCall: dev.dootah.runtime.NativeCall) {'))
        assertTrue(text, text.contains('Row(modifier = Modifier.padding(4.dp)) {\n        dootahCall.content(0) { dootahChild1, dootahCall1 ->'))
        assertTrue(text, text.contains('Text(text = (dootahCall1.arguments[0] as kotlin.String), modifier = Modifier.weight(1f))'))
        assertTrue(text, text.contains('"' + row.id + '" -> ' + row.method + '(call)'))
    }
}
