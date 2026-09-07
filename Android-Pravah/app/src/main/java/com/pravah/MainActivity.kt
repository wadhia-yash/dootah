package com.pravah

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.pravah.bridge.NativeBridge
import com.pravah.ota.PatchException
import com.pravah.ota.PatchManager
import com.pravah.runtime.JavaScriptRuntime
import com.pravah.ui.PatchRenderer
import com.pravah.ui.PatchUiNode
import com.pravah.ui.PatchUiParser
import kotlinx.coroutines.launch

/**
 * The UMD global the patch bundle exports. It is derived from the :patch-bundle
 * Gradle project name by the Kotlin/JS webpack build, so the two must be changed
 * together -- see the comment in patch-bundle/build.gradle.kts.
 */
private const val PATCH_MODULE_NAME = "patch-bundle"

private const val LOG_TAG = "Pravah"

class MainActivity : ComponentActivity() {

    private lateinit var runtime: JavaScriptRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nativeBridge =
            NativeBridge(applicationContext)

        runtime = JavaScriptRuntime(
            applicationContext
        ) { message ->

            nativeBridge.handle(message)
        }

        val patchManager = PatchManager(
            applicationContext,
            "https://wadhia-yash.github.io/dootah-manifest/manifest.json"
        )

        setContent {

            var uiNode by remember {
                mutableStateOf<PatchUiNode?>(null)
            }

            var result by remember {
                mutableStateOf("Patch not loaded")
            }

            Column(
                modifier = Modifier.padding(24.dp)
            ) {

                Text("Pravah PoC")
                Text(result)

                Button(
                    onClick = {

                        lifecycleScope.launch {

                            // A failed update check must never cost us the
                            // patch that is already working. Losing the network
                            // is not evidence that the installed patch is bad,
                            // so this failure is reported and then ignored.
                            try {
                                patchManager.checkForUpdate()
                            } catch (e: PatchException) {

                                Log.w(
                                    LOG_TAG,
                                    "update check failed, continuing with " +
                                        "patch ${patchManager.installedPatchVersion}",
                                    e,
                                )

                                result = "Update check failed: ${e.message}"
                            }

                            // Executing and rendering the patch is a separate
                            // concern: a failure here does implicate the patch
                            // itself.
                            try {

                                val patch =
                                    patchManager.getActivePatch()

                                runtime.execute(
                                    """
            $patch
            "loaded";
            """.trimIndent()
                                )

                                val json = runtime.execute(
                                    """
            globalThis["$PATCH_MODULE_NAME"]
                .renderScreen();
            """.trimIndent()
                                )

                                uiNode = PatchUiParser.parse(json)

                            } catch (e: Exception) {

                                Log.e(LOG_TAG, "patch failed to render", e)

                                patchManager.discardDownloadedPatch()

                                result =
                                    "Patch failed: ${e.message}"
                            }
                        }
                    }
                ) {
                    Text("Load Patch")
                }

                uiNode?.let { node ->

                    PatchRenderer(
                        node = node,

                        onAction = { action ->

                            lifecycleScope.launch {

                                try {

                                    val json = runtime.execute(
                                        """
                                        globalThis["$PATCH_MODULE_NAME"]
                                            .handleAction("$action");
                                        """.trimIndent()
                                    )

                                    uiNode =
                                        PatchUiParser.parse(json)

                                } catch (e: Exception) {

                                    result =
                                        "Error: ${e.message}"
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        runtime.close()
        super.onDestroy()
    }
}