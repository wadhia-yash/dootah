package dev.dootah.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import com.android.build.api.variant.BuiltArtifactsLoader
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/** A bounded, private release record. Uses public AGP artifacts, never intermediate paths. */
abstract class DootahRetainRelease extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ListProperty<Directory> getClassDirectories()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ListProperty<RegularFile> getClassJars()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ConfigurableFileCollection getSourceFiles()
    @Input abstract ListProperty<String> getSourceRoots()
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getApkDirectory()
    @Internal abstract Property<BuiltArtifactsLoader> getLoader()
    @Internal abstract DirectoryProperty getSourceRoot()
    @Input abstract MapProperty<String, String> getMetadata()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    DootahRetainRelease() { sourceRoots.convention([]) }

    static String hash(byte[] bytes) { MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }
    static void save(File root, String path, Object value) {
        def target = new File(root, path)
        target.parentFile.mkdirs()
        target.setText(JsonOutput.prettyPrint(JsonOutput.toJson(value)) + '\n', 'UTF-8')
    }
    static void addClass(Map<String, byte[]> classes, String path, byte[] bytes) {
        if (!path.endsWith('.class')) return
        if (path.startsWith('/') || path.contains('\\') || path.split('/').any { it in ['..', '.'] })
            throw new GradleException('Unsafe class archive entry')
        if (classes.containsKey(path)) throw new GradleException("Duplicate retained class: $path")
        classes[path] = bytes
    }

    @TaskAction void retain() {
        def artifacts = loader.get().load(apkDirectory.get())
        if (artifacts == null || artifacts.elements.size() != 1)
            throw new GradleException('Retention requires exactly one APK output. Select a single ABI/density output for this variant.')
        def apk = new File(artifacts.elements.first().outputFile)
        capture(apk)
    }

    void capture(File apk) {
        def classes = new TreeMap<String, byte[]>()
        classDirectories.get().each { dir ->
            dir.asFile.eachFileRecurse { file ->
                if (file.isFile()) addClass(classes, dir.asFile.toPath().relativize(file.toPath()).toString(), file.bytes)
            }
        }
        classJars.get().each { jar ->
            new ZipFile(jar.asFile).withCloseable { zip ->
                zip.entries().findAll { it.name.endsWith('.class') }.each {
                    addClass(classes, it.name, zip.getInputStream(it).bytes)
                }
            }
        }
        def hooks = classes.values().collectMany { DootahHookReport.hooks(it) }
            .sort { a, b -> a.owner <=> b.owner ?: a.method <=> b.method ?: a.descriptor <=> b.descriptor }
        if (hooks.empty) throw new GradleException('No installed dispatch identities; this build stays native.')
        def sources = new TreeMap<String, byte[]>()
        def root = sourceRoot.get().asFile.toPath().toRealPath()
        sourceFiles.files.findAll { it.name.endsWith('.kt') }.each { file ->
            def path = file.toPath().toRealPath()
            if (!path.startsWith(root)) throw new GradleException('Retention sources must be inside the application module; external source roots need a local release workspace.')
            sources[root.relativize(path).toString()] = Files.readAllBytes(path)
        }
        if (sources.isEmpty()) throw new GradleException('No variant Kotlin sources to retain')
        def capabilities
        new ZipFile(apk).withCloseable { zip ->
            def entry = zip.getEntry('assets/dootah-capabilities.json')
            if (entry == null) throw new GradleException('APK lacks the installed capability contract')
            capabilities = new JsonSlurper().parse(zip.getInputStream(entry))
        }
        if (capabilities.runtimeAbi != 2 || capabilities.logicAbi != 1)
            throw new GradleException('Retention requires Runtime ABI 2 / Logic ABI 1')
        def out = outputDirectory.get().asFile
        // A complete new capture replaces stale output; archive the record before the next native build.
        if (out.exists()) out.deleteDir()
        out.mkdirs()
        new File(out, 'accepted.apk').bytes = apk.bytes
        [classes: classes, sources: sources].each { prefix, files ->
            files.each { path, bytes ->
                def target = new File(out, "$prefix/$path")
                target.parentFile.mkdirs(); target.bytes = bytes
            }
        }
        save(out, 'classHashes.json', classes.collectEntries { path, bytes -> [(path): hash(bytes)] })
        save(out, 'hooks.json', [schema: 1, entries: hooks, hooks: hooks.size()])
        save(out, 'capabilities.json', capabilities)
        save(out, 'release.json', [schema: 1, metadata: new TreeMap(metadata.get()), runtimeAbi: 2, logicAbi: 1,
            apk: 'accepted.apk', apkSha256: hash(apk.bytes), classes: 'classes', classHashes: 'classHashes.json',
            sources: 'sources', sourceRoots: sourceRoots.get().unique(false).sort(), sourceHashes: sources.collectEntries { path, bytes -> [(path): hash(bytes)] },
            hooks: 'hooks.json', capabilities: 'capabilities.json'])
        logger.lifecycle("Dootah retained release: ${new File(out, 'release.json')}")
    }
}
