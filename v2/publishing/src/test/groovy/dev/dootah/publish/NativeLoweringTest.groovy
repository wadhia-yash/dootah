package dev.dootah.publish

import dev.dootah.adapters.AdapterGen
import dev.dootah.portable.PortableProgram
import dev.dootah.portable.UiTree
import groovy.json.JsonOutput
import org.json.JSONObject
import org.junit.Test
import static org.junit.Assert.*

/** App-owned components are reachable only through adapters generated from the installed source. */
class NativeLoweringTest {
    static final String ID = LogicTest.ID
    static final String PATH = 'src/main/java/demo/Badges.kt'
    static String source(String body) {
        '''package demo
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import lib.ui.Badge
@Composable fun DownloadsBadge(count: Int) {
''' + body + '\n}\n'
    }
    static final String INSTALLED = '''    if (count > 0) {
        Badge(text = "$count", color = MaterialTheme.colorScheme.tertiary)
    }'''

    /** The adapter table the installed build generated from its own source. */
    static Map installed(String body = INSTALLED) {
        new SourceAnalyzer().withCloseable { a ->
            AdapterGen.generate(a.inspect(PATH, source(body)).file, PATH).adapters.collectEntries { shape ->
                [(shape.id): shape.signature()] + shape.children.values().collectEntries { [(it.id): it.signature()] } }
        }
    }
    static Map lower(String body, Map adapters = installed()) {
        new SourceAnalyzer().withCloseable { a -> UiLowering.lower(a.inspect(PATH, source(body)).entries.first(), [:], [] as Set, adapters) }
    }
    static Map artifact(Map lowered) {
        def overrides = [[functionId: ID] + lowered]
        [schema: 'dootah.portable', runtimeAbi: 3, logicAbi: 1, runtimeVersion: 'test', requires: Contract.requirements(overrides), overrides: overrides]
    }
    static PortableProgram program(Map artifact) { new PortableProgram(JsonOutput.toJson(artifact), 'test') }
    static UiTree.Result run(PortableProgram p, int count) {
        def process = new ProcessBuilder('node', '-e', 'process.stdout.write(eval(process.argv[1]))', p.source(ID, ['Int'], [count])).start()
        def out = process.inputStream.text, err = process.errorStream.text
        assertEquals(err, 0, process.waitFor())
        p.renderResult(ID, out)
    }
    static void refused(String reason, Closure c) {
        try { c(); fail('Must refuse') } catch (IllegalArgumentException e) { assertTrue(e.message, e.message.contains(reason)) }
    }

    @Test void changedBusinessRuleKeepsTheInstalledComponentAndRendersPerValue() {
        def p = program(artifact(lower(INSTALLED.replace('count > 0', 'count > 1'))))
        assertEquals([PortableProgram.LAYOUT, PortableProgram.LOGIC, PortableProgram.NATIVE] as Set, artifact(lower(INSTALLED)).requires as Set)
        assertEquals(installed(), p.nativeAdapters())
        def one = run(p, 1), three = run(p, 3)
        assertEquals('if', one.root.kind)
        assertEquals([false, JSONObject.NULL], one.slots)          // root branch renders nothing
        assertEquals([true, '3'], three.slots)
        assertEquals(1, three.root.children.size())
        def badge = three.root.children[0]
        assertEquals('native', badge.kind)
        assertEquals(installed().keySet() as List, [badge.adapter])
        assertEquals([1], badge.arguments)
    }

    @Test void dataExpressionsMayChangeWhileTheCallShapeStaysInstalled() {
        def p = program(artifact(lower(INSTALLED.replace('"$count"', 'if (count > 99) "99+" else "$count"'))))
        assertEquals('99+', run(p, 120).slots[1])
        assertEquals('7', run(p, 7).slots[1])
    }

    @Test void anyChangeToTheInstalledCallShapeNeedsANewApk() {
        refused('No installed native adapter') { lower(INSTALLED.replace('tertiary', 'secondary')) }
        refused('No installed native adapter') { lower(INSTALLED.replace('Badge(text = "$count", ', 'Badge(text = "$count", maxLines = 1, ')) }
        refused('No installed native adapter') { lower(INSTALLED, [:]) }
        refused('Empty lambda argument') { lower(INSTALLED.replace(')\n', ') { }\n')) }
        refused('No installed native adapter') { lower(INSTALLED.replace(')\n', ') { Inner() }\n')) }
    }

    @Test void runtimeGrammarRefusesMalformedNativeNodes() {
        def good = artifact(lower(INSTALLED))
        def node = good.overrides[0].tree.then[0]
        [[adapter: 'not an id'], [adapter: node.adapter, arguments: [0]], [adapter: node.adapter, arguments: [1, 1]], [extra: true]].each { change ->
            def bad = new groovy.json.JsonSlurper().parseText(JsonOutput.toJson(good))
            bad.overrides[0].tree.then[0].putAll(change)
            LogicTest.refused { program(bad) }
        }
        def undeclared = new groovy.json.JsonSlurper().parseText(JsonOutput.toJson(good))
        undeclared.requires.remove(PortableProgram.NATIVE)
        refused('Capability requirements disagree') { program(undeclared) }
    }

    static final String COLUMN = '''    Column(modifier = Modifier.padding(4.dp)) {
        Icon(imageVector = Icons.Info, contentDescription = null)
        Text(text = "$count", style = MaterialTheme.typography.bodySmall)
    }'''

    /** Reordering and conditionally hiding existing nested children reuses the installed container adapter. */
    @Test void nestedChildrenCanBeReorderedAndHiddenWithoutANewApk() {
        def adapters = installed(COLUMN)
        assertEquals(3, adapters.size())
        def edited = '''    Column(modifier = Modifier.padding(4.dp)) {
        Text(text = "$count", style = MaterialTheme.typography.bodySmall)
        if (count > 1) {
            Icon(imageVector = Icons.Info, contentDescription = null)
        }
    }'''
        def p = program(artifact(lower(edited, adapters)))
        assertEquals(adapters, p.nativeAdapters())
        def root = run(p, 3).root
        assertTrue(root.container)
        assertEquals(['native', 'if'], root.children*.kind)
        assertTrue(root.children[0].adapter.contains('/lib.ui.Text#') || root.children[0].adapter.contains('/demo.Text#'))
        assertEquals([false, '1'] as Set, (run(p, 1).slots.findAll { it != null }) as Set)
        assertEquals([true, '3'] as Set, (run(p, 3).slots.findAll { it != null }) as Set)
        // A child call shape the installed content never made needs a new APK.
        refused('No installed native adapter') { lower(edited.replace('bodySmall', 'bodyLarge'), adapters) }
        refused('Unsupported content statement') { lower(edited.replace('if (count > 1) {', 'val x = 1\n        if (count > 1) {'), adapters) }
    }
}
