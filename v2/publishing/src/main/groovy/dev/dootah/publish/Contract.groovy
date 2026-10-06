package dev.dootah.publish

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import java.security.MessageDigest
import java.util.zip.ZipFile
import dev.dootah.portable.PortableProgram
import org.json.JSONObject
import static dev.dootah.publish.SourceAnalyzer.require

/** Release-side inputs, kept outside the Android task graph. */
class Contract {
    static final String CAPABILITY = 'compose.basic-text.constant.v1'
    static String hash(byte[] bytes) { MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }
    static String json(Object value) { JsonOutput.toJson(value) }
    static Map read(File file) { new JsonSlurper().parse(file) as Map }
    static Map sources(File root) {
        def files = []
        root.eachFileRecurse { if (it.isFile() && it.name.endsWith('.kt')) files << it }
        require(!files.empty, 'No Kotlin sources')
        files.sort { it.path }.collectEntries { [(root.toPath().relativize(it.toPath()).toString()):it.getText('UTF-8')] }
    }

    static Map sources(File root, List paths) {
        if (paths == null) return sources(root)
        require(!paths.empty && paths.toSet().size() == paths.size(), 'Invalid retained source paths')
        def base = root.toPath().toRealPath()
        paths.sort().collectEntries { String path ->
            def file = base.resolve(path).normalize()
            require(!new File(path).absolute && file.startsWith(base) && file.toRealPath().startsWith(base) && path.endsWith('.kt'), 'Unsafe source path')
            [(path): file.toFile().getText('UTF-8')]
        }
    }

    static Map currentSources(Map config) {
        if (!config.sourceRoots) return sources(new File(config.sourceRoot), config.sourcePaths as List)
        def base = new File(config.sourceRoot).toPath().toRealPath()
        def paths = new TreeSet<String>()
        config.sourceRoots.each { String path ->
            def dir = base.resolve(path).normalize()
            require(!new File(path).absolute && dir.startsWith(base), 'Unsafe source root')
            if (dir.toFile().exists()) {
                require(dir.toRealPath().startsWith(base), 'Source root escapes project')
                dir.toFile().eachFileRecurse { file ->
                    if (file.isFile() && file.name.endsWith('.kt')) paths.add(base.relativize(file.toPath()).toString())
                }
            }
        }
        sources(base.toFile(), paths as List)
    }

    /** Only installed, Logic ABI 1 parameter forms enter the public Cloud contract. */
    static Map cloudContract(Map contract) {
        require(contract.schema == 2 && contract.irAbi == 2 && contract.dispatchAbi == 2 &&
            contract.capabilities == new JsonSlurper().parseText(PortableProgram.contract().toString()), 'Runtime/Logic ABI or capability mismatch')
        def functions = new TreeMap()
        contract.functions.findAll { it.installed && !it.extension }.each { f ->
            def args = org.objectweb.asm.Type.getArgumentTypes(f.descriptor as String)*.descriptor
            int count = f.nullable.size()
            require(args.size() >= count + 2 && args[count] == 'Landroidx/compose/runtime/Composer;' &&
                args.drop(count + 1).every { it == 'I' }, 'Malformed installed descriptor')
            def types = []
            boolean portable = true
            args.take(count).eachWithIndex { d, i ->
                def type = ['Ljava/lang/String;':'String', 'I':'Int', 'Z':'Boolean',
                    'Ljava/lang/Integer;':'Int', 'Ljava/lang/Boolean;':'Boolean'][d]
                if (type == null) portable = false
                types << (type == null ? null : type + (f.nullable[i] == '?' ? '?' : ''))
            }
            if (portable) {
                require(!functions.containsKey(f.id), 'Duplicate installed identity')
                functions[f.id] = types
            }
        }
        require(!functions.isEmpty(), 'No installed Logic ABI 1 function identities')
        [capabilities: contract.capabilities, functions: functions]
    }

    static Map create(File apk, File classes, Map expectedClassHashes, Map sources, Map manifest) {
        def methods = [:]
        def apkIds = new HashSet()
        def embedded
        def installedCapabilities
        int hermesBytecodeVersion
        new ZipFile(apk).withCloseable { zip ->
            def caps = zip.getEntry('assets/dootah-capabilities.json')
            if (caps != null) {
                installedCapabilities = new JsonSlurper().parse(zip.getInputStream(caps))
                require(installedCapabilities == new JsonSlurper().parseText(PortableProgram.contract().toString()), 'Unknown installed capability contract')
            }
            embedded = new JsonSlurper().parse(zip.getInputStream(zip.getEntry('assets/app.manifest')))
            byte[] bootstrap = zip.getInputStream(zip.getEntry('assets/index.android.bundle')).readAllBytes()
            require(bootstrap.length >= 12 && Arrays.copyOf(bootstrap, 8).encodeHex().toString() == 'c61fbc03c103191f',
                'Installed APK lacks the supported Hermes bootstrap')
            hermesBytecodeVersion = java.nio.ByteBuffer.wrap(bootstrap).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(8)
            zip.entries().each { e ->
                if (e.name ==~ /classes[0-9]*\.dex/) {
                    // IDs contain ASCII only; scan DEX string bytes, not deobfuscated names.
                    String data = new String(zip.getInputStream(e).readAllBytes(), 'ISO-8859-1')
                    (data =~ /dth1:[0-9a-f]{64}/).each { apkIds.add(it) }
                }
            }
        }
        require(!apkIds.empty && !expectedClassHashes.empty, 'No installed dispatch identities/build provenance')
        expectedClassHashes.sort().each { path, digest ->
            File file = new File(classes, path)
            require(file.isFile() && hash(file.bytes) == digest, "Stale retained class: $path")
            def node = new ClassNode()
            new ClassReader(file.bytes).accept(node, 0)
            node.methods.each { m ->
                def calls = m.instructions.findAll { it instanceof MethodInsnNode &&
                    it.owner == 'dev/dootah/runtime/ComposeEntry' &&
                    ((it.name == 'tryRender' && it.desc == '(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)Z') ||
                     (it.name == 'tryRenderV2' && it.desc == '(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;Landroidx/compose/runtime/Composer;I)Z')) }
                if (calls.empty) return
                require(calls.size() == 1, 'Duplicate entry hook')
                def id = calls.first().previous
                int scan = 0
                while (id != null && !(id instanceof LdcInsnNode && id.cst instanceof String && id.cst.startsWith('dth1:')) && ++scan <= 128) id = id.previous
                require(id instanceof LdcInsnNode && id.cst ==~ /dth1:[0-9a-f]{64}/, 'Malformed hook identity')
                methods[node.name + '.' + m.name + m.desc] = [id:id.cst, access:m.access,
                    signature:m.signature, installed:id.cst in apkIds]
            }
        }
        def functions = []
        new SourceAnalyzer().withCloseable { analyzer ->
            sources.each { path, text ->
                analyzer.inspect(path, text).entries.each { entry ->
                    if (!entry.id) return
                    def method = methods[entry.owner + '.' + entry.method + entry.descriptor]
                    // Retained class signatures and hook constants are authoritative.
                    if (method == null) return
                    require(method.id == entry.id && method.signature == null &&
                        ((method.access & 8) != 0) == entry.isStatic, "Source/installed identity disagreement: ${entry.name}")
                    functions << [path:path, owner:entry.owner, name:entry.name, descriptor:entry.descriptor,
                        id:entry.id, nullable:entry.nullability, extension:entry.extension, isStatic:entry.isStatic,
                        installed:method.installed]
                }
            }
        }
        require(!functions.empty, 'No source/installed identity agreement')
        [schema:installedCapabilities == null ? 1 : 2, irAbi:installedCapabilities == null ? 1 : 2,
            dispatchAbi:installedCapabilities == null ? 1 : 2, capabilities:installedCapabilities ?: [CAPABILITY], apkSha256:hash(apk.bytes),
            embeddedId:embedded.id, hermesBytecodeVersion:hermesBytecodeVersion,
            packageName:manifest.packageName, runtimeVersion:manifest.runtimeVersion,
            appId:manifest.appId, channel:manifest.channel, updateUrl:manifest.updateUrl,
            classes:expectedClassHashes, sources:sources, functions:functions.sort { it.id }]
    }

    static void validate(Map contract, Map config) {
        require(contract.schema in [1,2] && contract.irAbi == contract.schema && contract.dispatchAbi == contract.schema, 'Runtime/IR ABI mismatch')
        require(contract.capabilities == (contract.schema == 1 ? [CAPABILITY] : new JsonSlurper().parseText(PortableProgram.contract().toString())), 'Unsupported installed capabilities')
        require(contract.apkSha256 == config.apkSha256 && contract.runtimeVersion == config.runtimeVersion &&
            contract.packageName == config.packageName && contract.appId == config.appId &&
            contract.channel == config.channel, 'Stale installed contract or release target mismatch')
        require(hash(new File(config.contract).bytes) == config.contractSha256, 'Contract digest mismatch; re-import the trusted installed build')
        require(contract.functions*.id.unique(false).size() == contract.functions.size(), 'Duplicate installed identities')
    }

    static Map analyze(Map contract, Map current) {
        require(current.keySet() == contract.sources.keySet(), 'Source file set differs from installed contract')
        def report = []
        def overrides = []
        new SourceAnalyzer().withCloseable { analyzer ->
            require(!current.any { path, text -> !analyzer.inspect(path, text).hazards.empty },
                'Project declarations shadow a rendering/package/annotation symbol; semantic resolution required')
            current.each { path, text ->
                def baseline = analyzer.inspect(path, contract.sources[path])
                def next = analyzer.inspect(path, text)
                require(baseline.skeleton == next.skeleton, "Non-body change requires a new installed contract: $path")
                next.entries.eachWithIndex { entry, i ->
                    def status = [path:path, name:entry.name, id:entry.id, changed:entry.body != baseline.entries[i].body]
                    try {
                        require(entry.id != null, entry.reason ?: 'No compatible source identity')
                        require(contract.functions.any { it.installed && it.id == entry.id && it.path == path &&
                            it.owner == entry.owner && it.descriptor == entry.descriptor }, 'Unknown function identity; not installed')
                        def tree = contract.schema == 2 ? LogicLowering.lower(entry) : analyzer.lower(entry)
                        status.status = status.changed ? 'portable' : 'unchanged'
                        if (status.changed) overrides << (contract.schema == 2 ? [functionId:entry.id] + tree : [functionId:entry.id, requires:[CAPABILITY], tree:tree])
                    } catch (IllegalArgumentException e) {
                        status.status = 'native'
                        status.reason = e.message
                    }
                    report << status
                }
            }
        }
        def refused = report.any { it.changed && it.status == 'native' }
        if (contract.schema == 2) {
            def ir = [schema:'dootah.portable', runtimeAbi:2, logicAbi:1, runtimeVersion:contract.runtimeVersion,
                requires:[PortableProgram.LOGIC, PortableProgram.TEXT], overrides:overrides.sort { it.functionId }]
            def generated = [:]
            if (!overrides.empty) {
                def program = new PortableProgram(json(ir), contract.runtimeVersion)
                program.functionIds().each { id -> generated[id] = PortableProgram.PRELUDE + '/* input: portable array */' + program.expression(id) }
            }
            return [report:report, refused:refused, ir:ir, generatedJs:generated]
        }
        [report:report, refused:refused, ir:[schema:'dootah.portable', abi:1,
            runtimeVersion:contract.runtimeVersion, apkSha256:contract.apkSha256,
            overrides:overrides.sort { it.functionId }]]
    }
}
