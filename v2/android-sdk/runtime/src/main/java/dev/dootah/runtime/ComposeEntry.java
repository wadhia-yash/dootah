package dev.dootah.runtime;

import androidx.compose.runtime.Composer;

/** Fixed, versioned SDK ABI. Composer remains exclusively in native code. */
public final class ComposeEntry {
    private ComposeEntry() {}
    public static boolean tryRender(String functionId, Composer composer, int changed) {
        return false; // ABI 1 is never reinterpreted as a typed ABI.
    }
    public static boolean tryRenderV2(String functionId, String types, Object[] values, Composer composer, int changed) {
        RenderValue value = ComposeDispatchKt.dispatchValue(composer, 0);
        if (value == null || value.getProgram() == null || !value.getProgram().has(functionId)) return false;
        String title = ComposeDispatchKt.portableText(functionId, types, values, value, composer, 0);
        if (title == null) return false;
        ComposeDispatchKt.renderOverride(title, value, composer, 0);
        return true;
    }
}
