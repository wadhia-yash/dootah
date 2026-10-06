package dev.dootah.gradle

import org.junit.Test
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import static org.objectweb.asm.Opcodes.*
import static org.junit.Assert.*

class ComposeEntryTest {
    // Required, never skipped: a missing or empty fixture directory is a failure.
    private static File fixtures() {
        def root = System.getProperty('composeFixtures')
        assertNotNull('composeFixtures is required; run v2/android-sdk/tests/compose-hook.sh', root)
        def directory = new File(root, 'dev/dootah/consumer')
        assertTrue("No compiled Compose fixtures in $directory", new File(directory, 'ComposeFormsKt.class').isFile())
        directory
    }
    private static ClassNode read(byte[] bytes) {
        def n = new ClassNode(ASM9); new ClassReader(bytes).accept(n, 0); n
    }
    private static byte[] transform(byte[] bytes) {
        def w = new ClassWriter(ClassWriter.COMPUTE_MAXS) // AGP supplies frame computation in Android builds.
        new ClassReader(bytes).accept(new ComposeEntryVisitor(w), 0); w.toByteArray()
    }
    private static List hooks(MethodNode m) {
        m.instructions.findAll { it instanceof MethodInsnNode && it.owner == ComposeEntryVisitor.HOOK }
    }
    private static byte[] withMetadataVersion(byte[] bytes, List version) {
        def node = read(bytes)
        def metadata = node.visibleAnnotations.find { it.desc == 'Lkotlin/Metadata;' }
        metadata.values[metadata.values.indexOf('mv') + 1] = version
        def writer = new ClassWriter(0); node.accept(writer); writer.toByteArray()
    }
    private static List hookIds(byte[] bytes) {
        read(transform(bytes)).methods.findAll { hooks(it) }.collect { m ->
            m.name + m.desc + '=' + m.instructions.find { it instanceof LdcInsnNode && it.cst.toString().startsWith('dth1:') }.cst
        }
    }
    // Phase 8: Kotlin 2.3.20 writes metadata 2.4.0; the reader must not silently skip newer toolchains.
    @Test void newerMetadataVersionsKeepIdentitiesAndUnknownVersionsStayNative() {
        byte[] bytes = new File(fixtures(), 'ComposeFormsKt.class').bytes
        def baseline = hookIds(bytes)
        assertEquals(9, baseline.size()) // eight names; Overloaded has two hooked overloads
        [[2, 3, 0], [2, 4, 0], [2, 5, 0]].each { assertEquals(baseline, hookIds(withMetadataVersion(bytes, it))) }
        assertEquals([], hookIds(withMetadataVersion(bytes, [2, 6, 0])))
    }
    @Test void readOnlyAndUnknownAppliersStayNative() {
        ['readOnly', 'unknown', 'custom'].each { mode ->
            def original = read(new File(fixtures(), 'ComposeFormsKt.class').bytes)
            def method = original.methods.find { it.name == 'CheckoutScreen' }
            if (mode == 'readOnly') method.invisibleAnnotations.add(new AnnotationNode('Landroidx/compose/runtime/ReadOnlyComposable;'))
            else {
                method.invisibleAnnotations.removeAll { it.desc == 'Landroidx/compose/runtime/ComposableTarget;' }
                if (mode == 'custom') {
                    def target = new AnnotationNode('Landroidx/compose/runtime/ComposableTarget;')
                    target.values = ['applier', 'example.CustomApplier']
                    method.invisibleAnnotations.add(target)
                }
            }
            def writer = new ClassWriter(0)
            original.accept(new ComposeEntryVisitor(writer))
            assertEquals(0, hooks(read(writer.toByteArray()).methods.find { it.name == 'CheckoutScreen' }).size())
        }
    }
    @Test void realFormsPreserveBodiesDescriptorsMasksAndMetadata() {
        ['ComposeFormsKt', 'MemberScreens', 'StringMember'].each { owner ->
            byte[] bytes = new File(fixtures(), owner + '.class').bytes
            def visitor = new ComposeEntryVisitor(new ClassWriter(0))
            // Save the exact instruction objects before visitEnd, not a re-created approximation.
            new ClassReader(bytes).accept(new ClassVisitor(ASM9, visitor) {
                @Override void visitEnd() {}
            }, 0)
            def originals = visitor.methods.collectEntries { [(it.name + it.desc): [nodes: it.instructions.toArray().toList(), access: it.access, signature: it.signature, annotations: it.invisibleAnnotations, handlers: it.tryCatchBlocks.toList()]] }
            visitor.visitEnd()
            visitor.methods.each { m ->
                def before = originals[m.name + m.desc]
                assertNotNull(before)
                assertEquals(before.access, m.access)
                assertEquals(before.signature, m.signature)
                assertSame(before.annotations, m.invisibleAnnotations)
                assertEquals(before.handlers, m.tryCatchBlocks)
                assertEquals(before.nodes, m.instructions.toArray().toList().takeRight(before.nodes.size()))
                assertTrue(hooks(m).size() <= 1)
                // No stores in the prefix: receivers, values, masks and Composer remain identical.
                assertFalse(m.instructions.toArray().toList().take(m.instructions.size() - before.nodes.size()).any { it.opcode in [ISTORE, LSTORE, FSTORE, DSTORE, ASTORE, IINC] })
            }
            def transformed = read(transform(bytes))
            def repeated = read(transform(transform(bytes)))
            assertEquals(transformed.methods.collect { hooks(it).size() }, repeated.methods.collect { hooks(it).size() })
            if (owner == 'ComposeFormsKt') {
                def expected = ['CheckoutScreen', 'ReceiptScreen', 'Overloaded', 'NullableDefault', 'ExtensionScreen', 'PrivateScreen', 'InternalScreen', 'StatefulScreen']
                transformed.methods.each { m -> assertEquals(m.name, expected.contains(m.name) ? 1 : 0, hooks(m).size()) }
                def ids = transformed.methods.findAll { hooks(it) }.collect { m -> m.instructions.find { it instanceof LdcInsnNode && it.cst.toString().startsWith('dth1:') }.cst }
                assertEquals(ids.size(), ids.toSet().size())
            } else {
                assertEquals(1, transformed.methods.sum { hooks(it).size() })
            }
        }
    }

    @Test void prefixExecutesOnJvmWithNativeFallbackOverrideAndDefaultMasks() {
        def original = read(new File(fixtures(), 'MemberScreens.class').bytes)
        def member = original.methods.find { it.name == 'Member' }
        // Preserve actual compiled metadata/signature; substitute a recording native body to
        // exercise the prefix on a JVM without an Android UI implementation.
        member.instructions.clear(); member.tryCatchBlocks.clear(); member.localVariables?.clear()
        member.instructions.add(new VarInsnNode(ILOAD, 3)) // changed mask
        member.instructions.add(new VarInsnNode(ILOAD, 4)) // default mask
        member.instructions.add(new InsnNode(IADD))
        member.instructions.add(new FieldInsnNode(PUTSTATIC, original.name, 'observed', 'I'))
        member.instructions.add(new InsnNode(RETURN))
        original.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, 'observed', 'I', null, null))
        original.methods.removeAll { it.name != 'Member' && it.name != '<init>' }
        def writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
        original.accept(new ComposeEntryVisitor(writer))
        def loader = new FixtureLoader(getClass().classLoader)
        def composer = new ClassWriter(0)
        composer.visit(V1_8, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, 'androidx/compose/runtime/Composer', null, 'java/lang/Object', null)
        loader.define('androidx.compose.runtime.Composer', composer.toByteArray())
        def stub = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
        stub.visit(V1_8, ACC_PUBLIC, ComposeEntryVisitor.HOOK, null, 'java/lang/Object', null)
        stub.visitField(ACC_PUBLIC | ACC_STATIC, 'enabled', 'Z', null, null).visitEnd()
        stub.visitField(ACC_PUBLIC | ACC_STATIC, 'captured', '[Ljava/lang/Object;', null, null).visitEnd()
        def hook = stub.visitMethod(ACC_PUBLIC | ACC_STATIC, 'tryRenderV2', ComposeEntryVisitor.HOOK_DESC, null, null)
        hook.visitCode(); hook.visitVarInsn(ALOAD, 2); hook.visitFieldInsn(PUTSTATIC, ComposeEntryVisitor.HOOK, 'captured', '[Ljava/lang/Object;'); hook.visitFieldInsn(GETSTATIC, ComposeEntryVisitor.HOOK, 'enabled', 'Z'); hook.visitInsn(IRETURN); hook.visitMaxs(0, 0); hook.visitEnd()
        def bridge = loader.define(ComposeEntryVisitor.HOOK.replace('/', '.'), stub.toByteArray())
        def target = loader.define(original.name.replace('/', '.'), writer.toByteArray())
        def instance = target.getConstructor().newInstance()
        def method = target.methods.find { it.name == 'Member' }
        method.invoke(instance, ['value', null, 42, 0] as Object[])
        assertEquals(42, target.getField('observed').getInt(null))
        assertEquals(['value'], (bridge.getField('captured').get(null) as Object[]).toList())
        bridge.getField('enabled').setBoolean(null, true)
        method.invoke(instance, ['value', null, 87, 0] as Object[])
        assertEquals(42, target.getField('observed').getInt(null))
        method.invoke(instance, [null, null, 87, 1] as Object[])
        assertEquals(88, target.getField('observed').getInt(null))
    }
}

class FixtureLoader extends ClassLoader {
    FixtureLoader(ClassLoader parent) { super(parent) }
    Class define(String name, byte[] bytes) { defineClass(name, bytes, 0, bytes.length) }
}
