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
            def adapters = result.adapters.collectEntries { [(it.id): it.signature()] }
            // The OTA edit: only the business rule changes; the installed component call is reused.
            def entry = a.inspect(PATH, installed.replace('count > 0', 'count > 1')).entries.find { it.name == 'DownloadsBadge' }
            def overrides = [[functionId: entry.id] + UiLowering.lower(entry, [:], [] as Set, adapters)]
            def artifact = [schema: 'dootah.portable', runtimeAbi: 3, logicAbi: 1, runtimeVersion: 'adapter-fixture',
                requires: Contract.requirements(overrides), overrides: overrides]
            same(new File(DEVICE, 'assets/adapters.json'), JsonOutput.prettyPrint(JsonOutput.toJson(artifact)) + '\n')
            assertEquals('native', new JsonSlurper().parse(new File(DEVICE, 'assets/adapters.json')).overrides[0].tree.then[0].type)
        }
    }
}
