package com.pravah.runtime

import android.content.Context
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import kotlinx.coroutines.guava.await
import androidx.core.content.ContextCompat
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePort
import androidx.javascriptengine.MessagePortClient

class JavaScriptRuntime(
    private val context: Context,
    private val onNativeMessage: (String) -> Unit
) {

    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null
    private var nativePort: MessagePort? = null

    private suspend fun ensureStarted() {
        if (isolate != null) return

        if (!JavaScriptSandbox.isSupported()) {
            error("JavaScriptSandbox is not supported")
        }

        sandbox = JavaScriptSandbox
            .createConnectedInstanceAsync(context)
            .await()

        isolate = sandbox!!.createIsolate()

        if (
            !sandbox!!.isFeatureSupported(
                JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS
            )
        ) {
            error("Message ports are not supported")
        }

        nativePort = isolate!!.createMessageChannel(
            "pravahNative",
            ContextCompat.getMainExecutor(context),
            object : MessagePortClient {

                override fun onMessage(message: Message) {

                    if (message.type == Message.TYPE_STRING) {
                        onNativeMessage(message.string)
                    }
                }
            }
        )

        isolate!!.evaluateJavaScriptAsync(
            """
            globalThis.__pravahNativePort =
                android.getNamedPort("pravahNative");
        
            globalThis.__pravahNativeCall =
                async function(message) {
                    const port =
                        await globalThis.__pravahNativePort;
        
                    port.postMessage(message);
                };
        
            "bridge-ready";
            """.trimIndent()
        ).await()
    }

    suspend fun execute(code: String): String {
        ensureStarted()

        return isolate!!
            .evaluateJavaScriptAsync(code)
            .await()
    }

    fun readAsset(fileName: String): String {
        return context.assets
            .open(fileName)
            .bufferedReader()
            .use { it.readText() }
    }

    /**
     * Releases the sandbox in reverse order of acquisition. The isolate must be
     * closed before the sandbox that owns it, otherwise its resources are leaked
     * for the lifetime of the sandbox process.
     */
    fun close() {
        nativePort?.close()
        isolate?.close()
        sandbox?.close()

        nativePort = null
        isolate = null
        sandbox = null
    }
}