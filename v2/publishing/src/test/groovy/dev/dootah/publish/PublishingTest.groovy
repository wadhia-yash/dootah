package dev.dootah.publish

import org.junit.Test
import static org.junit.Assert.*

class PublishingTest {
    private static String source(String body) {
        "package demo\nimport androidx.compose.runtime.Composable\n@Composable fun Screen() { $body }\n"
    }
    private static Map lower(String body) {
        new SourceAnalyzer().withCloseable { a -> a.lower(a.inspect('Demo.kt', source(body)).entries.first()) }
    }
    private static void refused(Closure work) {
        try { work(); fail('Expected conservative refusal') } catch (IllegalArgumentException expected) { }
    }

    @Test void constantTextEscapesAndConditions() {
        assertEquals('Hello\nworld', lower('androidx.compose.foundation.text.BasicText("Hello\\nworld")').value.value)
        assertEquals('named', lower('androidx.compose.foundation.text.BasicText(text = "named")').value.value)
        assertEquals('yes', lower('val flag = true; val label = "yes"; if (flag) { androidx.compose.foundation.text.BasicText(label) } else { androidx.compose.foundation.text.BasicText("no") }').value.value)
        assertEquals(lower('androidx.compose.foundation.text.BasicText("same")'), lower('androidx.compose.foundation.text.BasicText("same")'))
    }

    @Test void unsupportedWholeFunctionsNeverPartiallyLower() {
        [
            'androidx.compose.material3.Text("theme dependent")',
            'println("side effect"); androidx.compose.foundation.text.BasicText("x")',
            'if (true) { androidx.compose.foundation.text.BasicText("x") } else { Unsupported() }',
            'var title = "x"; androidx.compose.foundation.text.BasicText(title)',
            'const val title = "x"; androidx.compose.foundation.text.BasicText(title)',
            'androidx.compose.foundation.text.BasicText("bad\\q")',
            'val title = System.getProperty("x"); androidx.compose.foundation.text.BasicText(title)',
            'androidx.compose.foundation.text.BasicText("x", modifier = Modifier)',
            'androidx.compose.foundation.text.BasicText("$parameter")',
            'val androidx = unknown(); androidx.compose.foundation.text.BasicText("x")',
            'androidx.compose.foundation.text.BasicText("")',
            'androidx.compose.foundation.text.BasicText("' + ('x' * 257) + '")'
        ].each { body -> refused { lower(body) } }
    }

    @Test void sourceAgreesWithRetainedPhase4InstrumentationAndFinalDex() {
        def fixture = Contract.read(new File(getClass().getResource('/phase4-identities.json').toURI()))
        String source = getClass().getResource('/ComposeForms.kt').getText('UTF-8')
        new SourceAnalyzer().withCloseable { a ->
            def entries = a.inspect('ComposeForms.kt', source).entries
            fixture.functions.each { installed ->
                def entry = entries.find { it.owner == installed.owner && it.descriptor == installed.descriptor && it.name == installed.name }
                assertNotNull(installed.name, entry)
                assertEquals(installed.id, entry.id)
            }
            assertEquals(2, entries.findAll { it.name == 'Overloaded' }*.id.toSet().size())
            assertNotNull(entries.find { it.name == 'Member' }.id)
            assertNotNull(entries.find { it.name == 'ExtensionScreen' }.id)
            assertNotNull(entries.find { it.name == 'NullableDefault' }.id)
            assertFalse(fixture.functions.find { it.name == 'NullableDefault' }.installed)
            assertNull(entries.find { it.name == 'GenericScreen' }.id)
            assertNull(entries.find { it.name == 'UnsupportedScreen' }.id)
        }
    }

    @Test void bodyEditKeepsIdentityButNotSignatureChanges() {
        new SourceAnalyzer().withCloseable { a ->
            def before = a.inspect('Demo.kt', source('androidx.compose.foundation.text.BasicText("old")'))
            def after = a.inspect('Demo.kt', source('androidx.compose.foundation.text.BasicText("new")'))
            assertEquals(before.skeleton, after.skeleton)
            assertEquals(before.entries.first().id, after.entries.first().id)
            def renamed = a.inspect('Demo.kt', source('androidx.compose.foundation.text.BasicText("new")').replace('Screen()', 'Screen(value: Int)'))
            assertNotEquals(before.skeleton, renamed.skeleton)
            assertNotEquals(before.entries.first().id, renamed.entries.first().id)
        }
    }

    @Test void syntaxAndUnsupportedIdentityAreRefused() {
        new SourceAnalyzer().withCloseable { a ->
            refused { a.inspect('Bad.kt', 'fun bad(') }
            assertNull(a.inspect('Demo.kt', source('Unit').replace('Screen()', 'Screen(value: List<String>)')).entries.first().id)
            assertNull(a.inspect('Demo.kt', '@file:JvmName("Custom")\n' + source('Unit')).entries.first().id)
        }
    }

    @Test void importsCannotRedirectTheKnownComposeSymbol() {
        new SourceAnalyzer().withCloseable { a ->
            ['import other.Root as androidx', 'import other.*',
             'import other.BasicText\nimport androidx.compose.foundation.text.BasicText'].each { extra ->
                def text = source('androidx.compose.foundation.text.BasicText("x")')
                    .replace('import androidx.compose.runtime.Composable', 'import androidx.compose.runtime.Composable\n' + extra)
                def entry = a.inspect('Demo.kt', text).entries.first()
                refused { a.lower(entry) }
            }
        }
    }

    @Test void unchangedUnknownAndUnsupportedChangesAreSafe() {
        String before = source('androidx.compose.foundation.text.BasicText("old")')
        Map entry
        new SourceAnalyzer().withCloseable { a -> entry = a.inspect('Demo.kt', before).entries.first() }
        def contract = [sources:['Demo.kt':before], functions:[[installed:true, id:entry.id, path:'Demo.kt', owner:entry.owner, descriptor:entry.descriptor]], runtimeVersion:'test', apkSha256:'test']
        assertTrue(Contract.analyze(contract, contract.sources).ir.overrides.empty)
        def changed = ['Demo.kt':before.replace('"old"','"new"')]
        def result = Contract.analyze(contract, changed)
        assertFalse(result.refused)
        assertEquals('new', result.ir.overrides.first().tree.value.value)
        assertEquals(Contract.json(result), Contract.json(Contract.analyze(contract, changed)))
        contract.functions = []
        assertTrue(Contract.analyze(contract, changed).refused)
        refused { Contract.analyze(contract, ['Demo.kt':before.replace('Screen()', 'Screen(value: Int)')]) }
        refused { Contract.analyze(contract, changed + ['Extra.kt':'fun extra() {}']) }
    }

    @Test void staleAndAbiContractsAreRejected() {
        def file = File.createTempFile('dootah-contract-', '.json')
        try {
            def contract = [schema:1, irAbi:1, dispatchAbi:1, capabilities:[Contract.CAPABILITY], apkSha256:'apk',
                runtimeVersion:'runtime', packageName:'package', appId:'app', channel:'development', functions:[]]
            file.text = Contract.json(contract)
            def config = contract + [contract:file.path, contractSha256:Contract.hash(file.bytes)]
            Contract.validate(contract, config)
            refused { Contract.validate(contract + [irAbi:2], config) }
            refused { Contract.validate(contract + [apkSha256:'stale'], config) }
            refused { Contract.validate(contract + [capabilities:['new']], config) }
            file.text += ' '
            refused { Contract.validate(contract, config) }
        } finally { file.delete() }
    }

    @Test void publishingNeverJoinsAndroidTaskGraph() {
        def repository = new File(System.getProperty('repository'))
        ['settings.gradle', 'build.gradle', 'app/build.gradle'].each { name ->
            String text = new File(repository, 'v2/native-consumer/' + name).text
            assertFalse(text.contains('publishing'))
            assertFalse(text.contains('dootahPublish'))
        }
    }
}
