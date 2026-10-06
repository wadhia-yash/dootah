package dev.dootah.gradle

import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import groovy.json.JsonSlurper
import org.objectweb.asm.ClassWriter
import static org.objectweb.asm.Opcodes.*
import static org.junit.Assert.*

class DootahPluginTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder()

    private DootahExtension valid() {
        new DootahExtension(appId: UUID.randomUUID().toString(), updateUrl: 'https://updates.example.test/manifest',
            runtimeVersion: 'test-1', publicCertificate: new File('../../runtime-spike/xprem-public-certificate.pem'))
    }

    @Test void requiresPublicTrustAndSecureOrigin() {
        def config = valid()
        DootahPlugin.validate(config)
        config.updateUrl = 'http://updates.example.test/manifest'
        assertThrows(GradleException) { DootahPlugin.validate(config) }
        config.allowLocalHttp = true
        assertThrows(GradleException) { DootahPlugin.validate(config) }
        config.updateUrl = 'http://127.0.0.1:3100/manifest'
        DootahPlugin.validate(config)
        config.publicCertificate = temp.newFile('bad.pem')
        config.publicCertificate.text = '-----BEGIN PRIVATE KEY-----'
        assertThrows(GradleException) { DootahPlugin.validate(config) }
        config.publicCertificate.text = 'not a certificate'
        assertThrows(GradleException) { DootahPlugin.validate(config) }
    }

    // Phase 8: ABI splits bypass the NDK filter and produced non-arm64 APKs without Dootah natives.
    @Test void rejectsNonArm64AbiSplitOutputs() {
        DootahPlugin.requireArm64Outputs([])
        DootahPlugin.requireArm64Outputs(['arm64-v8a'])
        assertThrows(GradleException) { DootahPlugin.requireArm64Outputs(['armeabi-v7a']) }
        assertThrows(GradleException) { DootahPlugin.requireArm64Outputs(['arm64-v8a', 'x86', 'x86_64']) }
    }

    @Test void embeddedIdentityChangesWithConfiguration() {
        def project = ProjectBuilder.builder().withProjectDir(temp.newFolder()).build()
        def task = project.tasks.create('assets', DootahAssets)
        task.outputDirectory.set(new File(project.buildDir, 'assets'))
        task.identity.set('first-app-url-headers-certificate-runtime')
        task.generate()
        def first = new JsonSlurper().parse(new File(task.outputDirectory.get().asFile, 'app.manifest'))
        task.identity.set('second-app-url-headers-certificate-runtime')
        task.generate()
        def second = new JsonSlurper().parse(new File(task.outputDirectory.get().asFile, 'app.manifest'))
        assertNotEquals(first.id, second.id)
        assertEquals([], second.assets)
        assertTrue(second.commitTime > 0)
        task.generate()
        def rebuilt = new JsonSlurper().parse(new File(task.outputDirectory.get().asFile, 'app.manifest'))
        assertNotEquals(second.id, rebuilt.id)
    }

    static byte[] screen(boolean hooked) {
        def w = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        w.visit(V1_8, ACC_PUBLIC, 'example/ScreenKt', null, 'java/lang/Object', null)
        def m = w.visitMethod(ACC_PUBLIC | ACC_STATIC, 'Screen', '(Landroidx/compose/runtime/Composer;I)V', null, null)
        m.visitCode()
        if (hooked) {
            m.visitLdcInsn('dth1:' + 'a' * 64); m.visitLdcInsn(''); m.visitInsn(ICONST_0)
            m.visitTypeInsn(ANEWARRAY, 'java/lang/Object'); m.visitVarInsn(ALOAD, 0); m.visitInsn(ICONST_0)
            m.visitMethodInsn(INVOKESTATIC, ComposeEntryVisitor.HOOK, 'tryRenderV2', ComposeEntryVisitor.HOOK_DESC, false)
            m.visitInsn(POP)
        }
        m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd(); w.visitEnd()
        w.toByteArray()
    }

    private DootahHookReport hookReport(boolean hooked, boolean required) {
        def project = ProjectBuilder.builder().withProjectDir(temp.newFolder()).build()
        def classes = new File(project.projectDir, 'classes/example')
        classes.mkdirs()
        new File(classes, 'ScreenKt.class').bytes = screen(hooked)
        def task = project.tasks.create('report', DootahHookReport)
        task.classDirectories.add(project.layout.projectDirectory.dir('classes'))
        task.variantName.set('release')
        task.requireHooks.set(required)
        task.report.set(new File(project.buildDir, 'hooks.json'))
        task
    }

    // E2E: a build with zero instrumented entry points passed silently until import refused it.
    @Test void hookReportCountsEntryPointsAndWarnsOrFailsOnZero() {
        assertTrue(DootahPlugin.SDK_VERSION ==~ /\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?/)
        def hooked = hookReport(true, true)
        hooked.count()
        def report = new JsonSlurper().parse(hooked.report.get().asFile)
        assertEquals(1, report.hooks)
        assertEquals([[owner: 'example/ScreenKt', method: 'Screen', descriptor: '(Landroidx/compose/runtime/Composer;I)V', id: 'dth1:' + 'a' * 64]], report.entries)
        assertEquals(DootahPlugin.SDK_VERSION, report.sdkVersion)

        def empty = hookReport(false, false)
        empty.count() // normal builds warn, never fail
        assertEquals(0, new JsonSlurper().parse(empty.report.get().asFile).hooks)
        assertTrue(DootahHookReport.message('release', 0).contains('0 OTA entry points'))

        def required = hookReport(false, true)
        def failure = assertThrows(GradleException) { required.count() }
        assertTrue(failure.message.contains("0 OTA entry points instrumented in variant 'release'"))
    }
}
