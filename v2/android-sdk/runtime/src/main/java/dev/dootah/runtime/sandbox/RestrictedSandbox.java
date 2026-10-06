package dev.dootah.runtime.sandbox;

import android.content.Context;
import androidx.javascriptengine.IsolateStartupParameters;
import androidx.javascriptengine.JavaScriptSandbox;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Own on a worker thread. No Java objects, host callbacks, file descriptors or module loaders enter JS. */
public final class RestrictedSandbox implements AutoCloseable {
    public static final int MAX_SOURCE_BYTES = 65536;
    public static final int MAX_RESULT_BYTES = 16384;
    public static final long MAX_HEAP_BYTES = 8 * 1024 * 1024;
    public static final long TIMEOUT_MS = 1000;
    private final JavaScriptSandbox sandbox;

    public RestrictedSandbox(Context context) throws Exception {
        if (!JavaScriptSandbox.isSupported()) throw new IllegalStateException("Sandbox unavailable");
        var pending = JavaScriptSandbox.createConnectedInstanceAsync(context.getApplicationContext());
        try { sandbox = pending.get(10, TimeUnit.SECONDS); }
        catch (Exception e) {
            pending.addListener(() -> {
                try { pending.get().close(); } catch (Exception ignored) { }
            }, Runnable::run);
            throw e;
        }
        for (String feature : new String[] {
                JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
                JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE}) {
            if (!sandbox.isFeatureSupported(feature)) {
                sandbox.close();
                throw new IllegalStateException("Required sandbox feature unavailable: " + feature);
            }
        }
    }

    public synchronized String evaluate(String source) throws Exception {
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES)
            throw new IllegalArgumentException("Program size exceeded");
        var parameters = new IsolateStartupParameters();
        parameters.setMaxHeapSizeBytes(MAX_HEAP_BYTES);
        parameters.setMaxEvaluationReturnSizeBytes(MAX_RESULT_BYTES);
        try (var isolate = sandbox.createIsolate(parameters)) {
            String result = isolate.evaluateJavaScriptAsync(source).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES)
                throw new IllegalArgumentException("Result size exceeded");
            return result;
        }
    }

    @Override public void close() { sandbox.close(); }
}
