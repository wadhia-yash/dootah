package dev.dootah.gradle

import com.android.build.api.instrumentation.*
import org.objectweb.asm.ClassVisitor

abstract class ComposeEntryFactory implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    @Override boolean isInstrumentable(ClassData data) {
        // PROJECT already bounds this visitor; select exact metadata/annotations
        // from the bytecode itself rather than AGP's annotation summary.
        true
    }
    @Override ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        new ComposeEntryVisitor(next)
    }
}
