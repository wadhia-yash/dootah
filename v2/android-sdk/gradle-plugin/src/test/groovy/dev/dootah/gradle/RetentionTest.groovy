package dev.dootah.gradle

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.GradleException
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import static org.junit.Assert.*

class RetentionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder()

    private DootahRetainRelease task() {
        def project = ProjectBuilder.builder().withProjectDir(temp.newFolder()).build()
        def task = project.tasks.create('retain', DootahRetainRelease)
        def classes = new File(project.projectDir, 'classes/example')
        classes.mkdirs()
        new File(classes, 'Screen.class').bytes = DootahPluginTest.screen(true)
        def source = new File(project.projectDir, 'src/main/kotlin/Screen.kt')
        source.parentFile.mkdirs(); source.text = 'fun Screen() {}'
        task.sourceRoot.set(project.layout.projectDirectory)
        task.sourceFiles.from(source)
        task.classDirectories.add(project.layout.projectDirectory.dir('classes'))
        task.metadata.set([variant:'release', applicationId:'example'])
        task.outputDirectory.set(new File(project.buildDir, 'retained'))
        task
    }
    private File apk(int abi = 2) {
        def file = temp.newFile()
        new ZipOutputStream(new FileOutputStream(file)).withCloseable { zip ->
            zip.putNextEntry(new ZipEntry('assets/dootah-capabilities.json'))
            zip.write("{\"runtimeAbi\":$abi,\"logicAbi\":1}".bytes); zip.closeEntry()
        }
        file
    }
    @Test void deterministicBoundedRecordIncludesHashesSourcesAndHooks() {
        def task = task(), apk = apk()
        task.capture(apk)
        def output = task.outputDirectory.get().asFile
        String record = new File(output, 'release.json').text
        def parsed = new JsonSlurper().parseText(record)
        assertEquals(DootahRetainRelease.hash(apk.bytes), parsed.apkSha256)
        assertEquals(['src/main/kotlin/Screen.kt'] as Set, parsed.sourceHashes.keySet())
        assertEquals(1, new JsonSlurper().parse(new File(output, 'hooks.json')).hooks)
        def hashes = new JsonSlurper().parse(new File(output, 'classHashes.json'))
        hashes.each { path, digest -> assertEquals(digest, DootahRetainRelease.hash(new File(output, "classes/$path").bytes)) }
        new File(output, 'stale').text = 'stale'
        task.capture(apk)
        assertEquals(record, new File(output, 'release.json').text)
        assertFalse(new File(output, 'stale').exists())
        assertFalse(record.contains(task.sourceRoot.get().asFile.absolutePath))
    }
    @Test void unsafeDuplicateAndForeignSourcesFailClosed() {
        def classes = [:]
        DootahRetainRelease.addClass(classes, 'A.class', [1] as byte[])
        assertThrows(GradleException) { DootahRetainRelease.addClass(classes, 'A.class', [1] as byte[]) }
        assertThrows(GradleException) { DootahRetainRelease.addClass(classes, '../A.class', [1] as byte[]) }
        def task = task()
        def outside = temp.newFile('Other.kt'); outside.text = 'fun Other() {}'
        task.sourceFiles.from(outside)
        assertThrows(GradleException) { task.capture(apk()) }
    }
    @Test void abiMismatchAndNoHooksAreRefused() {
        def task = task()
        assertThrows(GradleException) { task.capture(apk(3)) }
        new File(task.sourceRoot.get().asFile, 'classes/example/Screen.class').bytes = DootahPluginTest.screen(false)
        assertThrows(GradleException) { task.capture(apk()) }
    }
    @Test void httpCannotReachNonDebuggableBuilds() {
        DootahAssets.validateTransport(false, false)
        DootahAssets.validateTransport(true, true)
        assertThrows(GradleException) { DootahAssets.validateTransport(true, false) }
        assertThrows(GradleException) { DootahPlugin.validateUrl('http://example.com', true) }
        assertThrows(GradleException) { DootahPlugin.validateUrl('http://127.0.0.1', false) }
        DootahPlugin.validateUrl('http://127.0.0.1', true)
    }
}
