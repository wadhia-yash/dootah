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
        assertTrue(source.contains('Badge(text = a0, shape = FrozenShape)'))
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
        assertEquals(['demo.Card'], result.adapters*.callee)
        assertEquals(['String'], result.adapters*.signature())
        assertEquals(10, result.refused.size())
        assertTrue(render('Hook.kt', source).contains('Card(text = a0)'))
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
}
