package com.dootah.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePort
import androidx.javascriptengine.MessagePortClient
import com.dootah.DOOTAH_LOG_TAG
import com.dootah.ota.BundleExecutionException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private const val NATIVE_PORT_NAME = "dootahNative"

/**
 * Caps the memory a bundle can allocate. Remote code should not be able to take
 * the host app down by allocating without bound.
 */
private const val MAX_ISOLATE_HEAP_BYTES = 64L * 1024 * 1024

/**
 * Executes bundle JavaScript inside an AndroidX JavaScriptSandbox.
 *
 * The sandbox is the security boundary: bundle code runs in a separate process
 * with no Android API access, and reaches native capabilities only by posting
 * messages through [NATIVE_PORT_NAME]. Nothing in this class hands the bundle a
 * reference to anything Android.
 */
internal class JavaScriptRuntime(
    private val context: Context,
    private val executionTimeoutMillis: Long,
    private val onNativeMessage: (String) -> Unit,
) {

    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null
    private var nativePort: MessagePort? = null

    /** Serialises startup so concurrent callers cannot create two sandboxes. */
    private val lifecycleLock = Mutex()

    /**
     * Evaluates [code] and returns the value of its last expression.
     *
     * On timeout the isolate is torn down, which is the only way to stop a script
     * that will not yield. Without this a bundle containing an endless loop would
     * leave the calling coroutine suspended forever and the screen stuck.
     */
    suspend fun evaluate(code: String): String {

        val activeIsolate = lifecycleLock.withLock { startIfNeeded() }

        return try {
            withTimeout(executionTimeoutMillis) {
                activeIsolate.evaluateJavaScriptAsync(code).await()
            }
        } catch (cause: TimeoutCancellationException) {

            Log.e(DOOTAH_LOG_TAG, "bundle exceeded ${executionTimeoutMillis}ms, terminating isolate")
            lifecycleLock.withLock { shutdown() }

            throw BundleExecutionException(
                "Bundle did not finish within ${executionTimeoutMillis}ms",
                cause,
            )
        } catch (cause: Exception) {
            throw BundleExecutionException(
                "Bundle evaluation failed: ${cause.message}",
                cause,
            )
        }
    }

    /**
     * Discards the isolate so the next evaluation starts from a clean global
     * scope.
     *
     * Required when replacing one bundle with another: re-evaluating a new bundle
     * over the previous one leaves the old module's globals in place, which makes
     * a swapped bundle behave differently from the same bundle loaded fresh.
     */
    suspend fun restart() {
        lifecycleLock.withLock { shutdown() }
    }

    suspend fun close() {
        lifecycleLock.withLock { shutdown() }
    }

    private suspend fun startIfNeeded(): JavaScriptIsolate {

        isolate?.let { return it }

        if (!JavaScriptSandbox.isSupported()) {
            throw BundleExecutionException(
                "JavaScriptSandbox is not supported on this device"
            )
        }

        val startedSandbox = JavaScriptSandbox
            .createConnectedInstanceAsync(context)
            .await()

        sandbox = startedSandbox

        if (!startedSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN)) {
            Log.w(DOOTAH_LOG_TAG, "sandbox cannot return promises; bundle async work may be lost")
        }

        if (
            !startedSandbox.isFeatureSupported(
                JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT
            )
        ) {
            // Results cross a Binder transaction, so a large UI tree can fail on
            // devices without this feature.
            Log.w(DOOTAH_LOG_TAG, "sandbox limits evaluation size; large bundle UI may fail")
        }

        val startedIsolate = createIsolate(startedSandbox)
        isolate = startedIsolate

        if (!startedSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)) {
            throw BundleExecutionException(
                "This device's sandbox does not support message ports, " +
                    "so the Dootah native bridge cannot be established"
            )
        }

        nativePort = startedIsolate.createMessageChannel(
            NATIVE_PORT_NAME,
            ContextCompat.getMainExecutor(context),
            object : MessagePortClient {
                override fun onMessage(message: Message) {
                    if (message.type == Message.TYPE_STRING) {
                        onNativeMessage(message.string)
                    }
                }
            },
        )

        startedIsolate.evaluateJavaScriptAsync(BRIDGE_BOOTSTRAP).await()

        return startedIsolate
    }

    private fun createIsolate(sandbox: JavaScriptSandbox): JavaScriptIsolate {

        if (!sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
            Log.w(DOOTAH_LOG_TAG, "sandbox cannot cap isolate heap on this device")
            return sandbox.createIsolate()
        }

        val parameters = IsolateStartupParameters().apply {
            maxHeapSizeBytes = MAX_ISOLATE_HEAP_BYTES
        }

        return sandbox.createIsolate(parameters)
    }

    /** Releases resources in reverse order of acquisition. */
    private fun shutdown() {

        nativePort?.close()
        isolate?.close()
        sandbox?.close()

        nativePort = null
        isolate = null
        sandbox = null
    }
}

/**
 * Installs the single function a bundle may call to reach native code.
 *
 * The bundle gets a message-posting function and nothing else. Widening what a
 * bundle can do is a deliberate change to [com.dootah.bridge.NativeBridge], never
 * a side effect of loading a new bundle.
 */
private val BRIDGE_BOOTSTRAP = """
    globalThis.__dootahNativePort =
        android.getNamedPort("$NATIVE_PORT_NAME");

    globalThis.__dootahNativeCall =
        async function(message) {
            const port = await globalThis.__dootahNativePort;
            port.postMessage(message);
        };

    "bridge-ready";
""".trimIndent()
