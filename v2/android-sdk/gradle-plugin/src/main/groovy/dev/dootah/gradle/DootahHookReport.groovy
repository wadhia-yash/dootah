package dev.dootah.gradle

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import java.util.zip.ZipFile

/** Counts the OTA entry points actually present in a variant's instrumented project classes. */
abstract class DootahHookReport extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ListProperty<Directory> getClassDirectories()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ListProperty<RegularFile> getClassJars()
    @Input abstract Property<String> getVariantName()
    @Input abstract Property<Boolean> getRequireHooks()
    @OutputFile abstract RegularFileProperty getReport()

    @TaskAction void count() {
        def entries = []
        classDirectories.get().each { dir ->
            dir.asFile.eachFileRecurse { if (it.isFile() && it.name.endsWith('.class')) entries.addAll(hooks(it.bytes)) }
        }
        classJars.get().each { jar ->
            new ZipFile(jar.asFile).withCloseable { zip ->
                zip.entries().findAll { it.name.endsWith('.class') }.each { entries.addAll(hooks(zip.getInputStream(it).bytes)) }
            }
        }
        entries.sort { a, b -> a.owner <=> b.owner ?: a.method <=> b.method ?: a.descriptor <=> b.descriptor }
        def file = report.get().asFile
        file.parentFile.mkdirs()
        file.text = JsonOutput.prettyPrint(JsonOutput.toJson([schema: 1, variant: variantName.get(),
            sdkVersion: DootahPlugin.SDK_VERSION, hooks: entries.size(), entries: entries])) + '\n'
        def message = message(variantName.get(), entries.size())
        if (entries.empty && requireHooks.get()) throw new GradleException(message)
        if (entries.empty) logger.warn(message) else logger.lifecycle(message)
    }

    static String message(String variant, int count) {
        count > 0 ? "Dootah: $count OTA entry point(s) instrumented in variant '$variant'" :
            "Dootah: 0 OTA entry points instrumented in variant '$variant'. Every composable stays native and " +
            "`dootah import` will refuse this build. Only application-module composables with supported " +
            "parameter forms are instrumented; see docs/v2/COMPOSE_INSTRUMENTATION.md."
    }

    static List<Map> hooks(byte[] classBytes) {
        def node = new ClassNode()
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        node.methods.findAll { m ->
            m.instructions.any { it instanceof MethodInsnNode && it.owner == ComposeEntryVisitor.HOOK &&
                it.name == 'tryRenderV2' && it.desc == ComposeEntryVisitor.HOOK_DESC }
        }.collect { m ->
            def id = m.instructions.find { it instanceof LdcInsnNode && it.cst instanceof String && it.cst.startsWith('dth1:') }
            [owner: node.name, method: m.name, descriptor: m.desc, id: id?.cst]
        }
    }
}
