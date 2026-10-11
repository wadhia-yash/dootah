package dev.dootah.publish

import dev.dootah.adapters.AdapterGen
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.Test
import static org.junit.Assert.*

/**
 * The device fixture's generated adapter, index and OTA artifact are exactly what the generator and
 * analyzer produce from ordinary Kotlin source; set DOOTAH_REGENERATE=1 to rewrite them.
 */
class AdapterFixtureTest {
    static final File ROOT = new File(System.getProperty('repository'))
    static final String PATH = 'adapters/Badges.kt'
    static final File SOURCE = new File(ROOT, 'v2/publishing/src/test/resources/' + PATH)
    static final File DEVICE = new File(ROOT, 'v2/android-sdk/runtime/src/androidTest')

    static void same(File file, String expected) {
        if (System.getenv('DOOTAH_REGENERATE')) { file.parentFile.mkdirs(); file.setText(expected, 'UTF-8') }
        assertEquals(file.path, expected, file.getText('UTF-8'))
    }

    @Test void deviceFixtureIsTheGeneratorAndAnalyzerOutputOfOrdinarySource() {
        String installed = SOURCE.getText('UTF-8')
        new SourceAnalyzer().withCloseable { a ->
            def file = a.inspect(PATH, installed).file
            def result = AdapterGen.generate(file, PATH)
            assertEquals([], result.refused)
            same(new File(DEVICE, 'generated/dev/dootah/fixture/' + result.facade + '.kt'), AdapterGen.render(file, result))
            same(new File(DEVICE, 'generated/dev/dootah/generated/DootahAdapterIndex.kt'), AdapterGen.index([result]))
            def adapters = result.adapters.collectEntries { shape ->
                [(shape.id): shape.signature()] + shape.children.values().collectEntries { [(it.id): it.signature()] } }
            Closure artifact = { String edited, String function, File target ->
                def entry = a.inspect(PATH, edited).entries.find { it.name == function }
                def overrides = [[functionId: entry.id] + UiLowering.lower(entry, [:], [] as Set, adapters)]
                same(target, JsonOutput.prettyPrint(JsonOutput.toJson([schema: 'dootah.portable', runtimeAbi: 3, logicAbi: 1,
                    runtimeVersion: 'adapter-fixture', requires: Contract.requirements(overrides), overrides: overrides])) + '\n')
                new JsonSlurper().parse(target).overrides[0].tree
            }
            // The OTA edit: only the business rule changes; the installed component call is reused.
            assertEquals('native', artifact(installed.replace('count > 0', 'count > 1'), 'DownloadsBadge',
                new File(DEVICE, 'assets/adapters.json')).then[0].type)
            // Nested: the installed Row's existing children are reordered and one becomes conditional.
            String nested = installed.replace('''        CountBadge(text = "$count", color = MaterialTheme.colorScheme.tertiary)
        Text(text = "items", modifier = Modifier.weight(1f))''', '''        Text(text = "items", modifier = Modifier.weight(1f))
        if (count > 1) {
            CountBadge(text = "$count", color = MaterialTheme.colorScheme.tertiary)
        }''')
            assertNotEquals(installed, nested)
            def row = artifact(nested, 'BadgeRow', new File(DEVICE, 'assets/adapters-nested.json'))
            assertEquals(['native', 'if'], row.children*.type)
        }
    }
}
