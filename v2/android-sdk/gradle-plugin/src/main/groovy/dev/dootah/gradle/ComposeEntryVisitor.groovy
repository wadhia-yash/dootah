package dev.dootah.gradle

import dev.dootah.identity.FunctionIdentity
import kotlin.metadata.Attributes
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.JvmMetadataUtil
import kotlin.metadata.jvm.JvmExtensionsKt
import org.gradle.api.logging.Logging
import java.util.concurrent.atomic.AtomicBoolean
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import static org.objectweb.asm.Opcodes.*

/** Only prefixes declared methods. Metadata and the existing instruction graph are never rewritten. */
class ComposeEntryVisitor extends ClassNode {
    static final String HOOK = 'dev/dootah/runtime/ComposeEntry'
    static final String HOOK_DESC = '(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;Landroidx/compose/runtime/Composer;I)Z'
    private static final AtomicBoolean unreadableReported = new AtomicBoolean()
    private final ClassVisitor downstream

    ComposeEntryVisitor(ClassVisitor downstream) { super(ASM9); this.downstream = downstream }

    @Override void visitEnd() {
        def functions = declarations()
        methods.each { MethodNode method ->
            def function = functions[method.name + method.desc]
            if (function != null && eligible(method, function)) prefix(method, function)
        }
        accept(downstream)
    }

    private Map declarations() {
        def annotation = visibleAnnotations?.find { it.desc == 'Lkotlin/Metadata;' }
        if (!annotation) return [:]
        def values = [:]
        for (int i = 0; i < annotation.values.size(); i += 2) values[annotation.values[i]] = annotation.values[i + 1]
        try {
            def metadata = JvmMetadataUtil.Metadata(values.k as Integer, values.mv as int[],
                values.d1 as String[], values.d2 as String[], values.xs as String,
                values.pn as String, values.xi as Integer)
            // Unknown metadata fails closed to native, without selecting compiler adapters.
            def parsed = KotlinClassMetadata.readStrict(metadata)
            def functions
            if (parsed instanceof KotlinClassMetadata.FileFacade || parsed instanceof KotlinClassMetadata.MultiFileClassPart) functions = parsed.kmPackage.functions
            else if (parsed instanceof KotlinClassMetadata.Class) functions = parsed.kmClass.functions
            else return [:]
            return functions.collectEntries { f ->
                def signature = JvmExtensionsKt.getSignature(f)
                signature == null ? [:] : [(signature.name + signature.descriptor): f]
            }
        } catch (IllegalArgumentException e) {
            // Still fail closed, but never silently: an unreadable toolchain leaves every class native.
            if (unreadableReported.compareAndSet(false, true)) {
                Logging.getLogger(ComposeEntryVisitor).warn("Dootah: Kotlin metadata unreadable; affected classes stay native (${name}: ${e.message})")
            }
            return [:]
        }
    }

    private static boolean eligible(MethodNode m, f) {
        if ((m.access & (ACC_ABSTRACT | ACC_NATIVE | ACC_SYNTHETIC | ACC_BRIDGE | ACC_SYNCHRONIZED)) != 0) return false
        if (!m.invisibleAnnotations?.any { it.desc == 'Landroidx/compose/runtime/Composable;' }) return false
        if (m.invisibleAnnotations.any { it.desc == 'Landroidx/compose/runtime/ReadOnlyComposable;' }) return false
        def target = m.invisibleAnnotations.find { it.desc == 'Landroidx/compose/runtime/ComposableTarget;' }
        if (target?.values != ['applier', 'androidx.compose.ui.UiComposable']) return false
        if (Attributes.isInline(f) || Attributes.isSuspend(f) || !f.typeParameters.empty) return false
        if (Type.getReturnType(m.desc) != Type.VOID_TYPE) return false
        def args = Type.getArgumentTypes(m.desc)
        int sourceCount = f.valueParameters.size() + (f.receiverParameterType == null ? 0 : 1)
        if (sourceCount >= args.length || args[sourceCount].descriptor != 'Landroidx/compose/runtime/Composer;') return false
        // A bounded, inspected mask form. Larger forms remain native until validated.
        if (sourceCount > 9 || f.contextReceiverTypes.size() != 0) return false
        int defaults = f.valueParameters.any { Attributes.getDeclaresDefaultValue(it) } ? 1 : 0
        if (args.length != sourceCount + 2 + defaults) return false
        if (!args.drop(sourceCount + 1).every { it == Type.INT_TYPE }) return false
        if (!args.take(sourceCount).every { it.sort in [Type.BOOLEAN, Type.INT] || it.descriptor in ['Ljava/lang/String;', 'Ljava/lang/Integer;', 'Ljava/lang/Boolean;'] }) return false
        return !m.instructions.any { it instanceof MethodInsnNode && it.owner == HOOK && it.name == 'tryRenderV2' && it.desc == HOOK_DESC }
    }

    private void prefix(MethodNode m, f) {
        def args = Type.getArgumentTypes(m.desc)
        int count = f.valueParameters.size() + (f.receiverParameterType == null ? 0 : 1)
        boolean isStatic = (m.access & ACC_STATIC) != 0
        int composerSlot = isStatic ? 0 : 1
        args.take(count).each { composerSlot += it.size }
        def prefix = new InsnList()
        def nativeBody = new LabelNode()
        def sourceTypes = (f.receiverParameterType == null ? [] : [f.receiverParameterType]) + f.valueParameters.collect { it.type }
        int slot = isStatic ? 0 : 1
        sourceTypes.eachWithIndex { type, i ->
            if (args[i].sort == Type.OBJECT && !Attributes.isNullable(type)) {
                prefix.add(new VarInsnNode(ALOAD, slot))
                prefix.add(new JumpInsnNode(IFNULL, nativeBody))
            }
            slot += args[i].size
        }
        if (args.length == count + 3) {
            // Defaults execute in the untouched body; never skip their evaluation.
            prefix.add(new VarInsnNode(ILOAD, composerSlot + 2))
            prefix.add(new JumpInsnNode(IFNE, nativeBody))
        }
        prefix.add(new LdcInsnNode(FunctionIdentity.of(name, m.name, m.desc, isStatic,
            f.receiverParameterType != null, m.signature, sourceTypes.collect { Attributes.isNullable(it) ? '?' : '!' }.join(''))))
        def portableTypes = args.take(count).toList().withIndex().collect { arg, i ->
            def type = arg.sort == Type.INT || arg.descriptor == 'Ljava/lang/Integer;' ? 'Int' :
                arg.sort == Type.BOOLEAN || arg.descriptor == 'Ljava/lang/Boolean;' ? 'Boolean' : 'String'
            type + (Attributes.isNullable(sourceTypes[i]) ? '?' : '')
        }
        prefix.add(new LdcInsnNode(portableTypes.join(',')))
        prefix.add(new IntInsnNode(BIPUSH, count))
        prefix.add(new TypeInsnNode(ANEWARRAY, 'java/lang/Object'))
        slot = isStatic ? 0 : 1
        args.take(count).eachWithIndex { arg, i ->
            prefix.add(new InsnNode(DUP))
            prefix.add(new IntInsnNode(BIPUSH, i))
            prefix.add(new VarInsnNode(arg.getOpcode(ILOAD), slot))
            if (arg.sort in [Type.INT, Type.BOOLEAN]) {
                def owner = arg.sort == Type.INT ? 'java/lang/Integer' : 'java/lang/Boolean'
                prefix.add(new MethodInsnNode(INVOKESTATIC, owner, 'valueOf', '(' + arg.descriptor + ')L' + owner + ';', false))
            }
            prefix.add(new InsnNode(AASTORE))
            slot += arg.size
        }
        prefix.add(new VarInsnNode(ALOAD, composerSlot))
        prefix.add(new InsnNode(ICONST_0))
        prefix.add(new MethodInsnNode(INVOKESTATIC, HOOK, 'tryRenderV2', HOOK_DESC, false))
        prefix.add(new JumpInsnNode(IFEQ, nativeBody))
        prefix.add(new InsnNode(RETURN))
        prefix.add(nativeBody)
        m.instructions.insert(prefix)
        m.maxStack = Math.max(m.maxStack, 8)
    }
}
