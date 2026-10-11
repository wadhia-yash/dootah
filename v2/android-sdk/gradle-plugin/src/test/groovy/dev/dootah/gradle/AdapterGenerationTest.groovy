package dev.dootah.gradle

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import static org.junit.Assert.*

/** The task runs the generator in a separate JVM and always leaves the index the SDK links against. */
class AdapterGenerationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder()

    DootahGenerateAdapters task(File module, boolean enabled) {
        def project = ProjectBuilder.builder().withProjectDir(module).build()
        def task = project.tasks.register('generateDootahAdapters', DootahGenerateAdapters) {
            sources.from(project.fileTree('src') { include '**/*.kt' })
            generatorClasspath.from(System.getProperty('java.class.path').split(File.pathSeparator).collect { new File(it) })
            enabledAdapters.set(enabled)
            moduleDirectory.set(module)
            outputDirectory.set(new File(module, 'build/generated/adapters'))
            report.set(new File(module, 'build/adapters.json'))
        }.get()
        task.generate()
        task
    }

    File module() {
        def module = temp.newFolder()
        def dir = new File(module, 'src/main/java/demo')
        dir.mkdirs()
        new File(dir, 'Badges.kt').text = '''package demo
import androidx.compose.runtime.Composable
@Composable fun Badge(text: String, color: String = "secondary") {}
@Composable fun Downloads(count: Int) { if (count > 0) Badge(text = "$count") }
@Composable fun Refused(count: Int) { Badge(text = "x") { val local = count } }
'''
        new File(dir, 'Broken.kt').text = 'package demo\nfun broken( {'
        module
    }

    @Test void generatesAdaptersIndexAndReportFromModuleSources() {
        def module = module()
        task(module, true)
        def out = new File(module, 'build/generated/adapters')
        def generated = out.listFiles().collectMany { it.isDirectory() ? it.listFiles().toList() : [it] }
        def adapters = new File(out, 'demo').listFiles().find { it.name.startsWith('DootahAdaptersKt_') }
        assertNotNull(generated.toString(), adapters)
        assertTrue(adapters.text.contains('Badge(text = a0)'))
        def index = new File(out, 'dev/dootah/generated/DootahAdapterIndex.kt').text
        assertTrue(index.contains('NativeAdapterRegistry.of('))
        def report = new JsonSlurper().parse(new File(module, 'build/adapters.json'))
        assertEquals(1, report.adapters)
        assertEquals(['src/main/java/demo/Badges.kt', 'src/main/java/demo/Broken.kt'] as Set, report.files*.path as Set)
        assertTrue(report.files.find { it.path.endsWith('Broken.kt') }.refused.first().contains('stays native'))
    }

    @Test void disabledStillGeneratesTheEmptyIndex() {
        def module = module()
        task(module, false)
        def out = new File(module, 'build/generated/adapters')
        assertTrue(new File(out, 'dev/dootah/generated/DootahAdapterIndex.kt').text.contains('NativeAdapterRegistry.Empty'))
        assertFalse(new File(out, 'demo').exists())
    }
}
