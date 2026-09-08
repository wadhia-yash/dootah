package com.pravah.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pravah.FallbackReason
import com.pravah.UpdateFailure
import com.pravah.UpdateResult
import com.pravah.ui.PravahHostState
import com.pravah.ui.PravahPatchHost
import com.pravah.ui.rememberPravahHostState

/**
 * The one isolated screen used to validate Pravah inside a real app.
 *
 * Structure worth copying into a host app: a Pravah-backed region with a
 * required native fallback, and nothing else in the app aware that remote
 * content exists. The diagnostics panel is validation scaffolding and is
 * expected to be deleted afterwards.
 */
@Composable
fun OfferDemoScreen() {

    val pravah = rememberPravahHostState()

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        Text(
            text = "Pravah OTA Validation",
            style = MaterialTheme.typography.titleLarge,
        )

        PravahDiagnostics(
            state = pravah,
            onCheckForUpdate = pravah::checkForUpdate,
        )

        HorizontalDivider()

        // Everything below this line may be replaced over the air.
        PravahPatchHost(
            state = pravah,
            loading = { Text("Loading Pravah content...") },
            fallback = { reason, message ->
                NativeOfferContent(reason = reason, message = message)
            },
        )
    }
}

/**
 * The native offer screen. This is what a real user sees whenever Pravah is
 * disabled, offline with no stored patch, or failing for any reason.
 */
@Composable
private fun NativeOfferContent(
    reason: FallbackReason,
    message: String,
) {

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Text(
            text = "Standard Offer",
            style = MaterialTheme.typography.titleMedium,
        )

        Text(
            text = "Price: Rs 999",
            style = MaterialTheme.typography.headlineSmall,
        )

        Text("This is the native screen built into the APK.")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Fallback reason: $reason",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PravahDiagnostics(
    state: PravahHostState,
    onCheckForUpdate: () -> Unit,
) {

    val context = LocalContext.current

    val appVersion = remember(context) {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
        }.getOrNull() ?: "unknown"
    }

    Card(modifier = Modifier.fillMaxWidth()) {

        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {

            DiagnosticRow("App version", appVersion)
            DiagnosticRow("Patch version", state.status.patchVersion.toString())
            DiagnosticRow("Runtime version", state.status.runtimeVersion)
            DiagnosticRow("Patch source", state.status.source.name)
            DiagnosticRow("Kill switch", if (state.status.isRemotelyDisabled) "DISABLED" else "enabled")

            state.lastUpdateResult?.let { result ->
                DiagnosticRow("Last check", result.describe())
            }

            Spacer(modifier = Modifier.height(6.dp))

            Button(
                onClick = onCheckForUpdate,
                enabled = !state.isCheckingForUpdate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.isCheckingForUpdate) "Checking..." else "Check Pravah Update"
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {

    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
    )
}

/** Renders an [UpdateResult] for the diagnostics panel. */
private fun UpdateResult.describe(): String = when (this) {

    is UpdateResult.Updated ->
        "updated $previousPatchVersion -> $patchVersion"

    is UpdateResult.NoUpdate ->
        "no update (on $patchVersion)"

    UpdateResult.Disabled ->
        "disabled by manifest"

    is UpdateResult.IncompatibleRuntime ->
        "incompatible: patch wants $patchRuntimeVersion, runtime is $supportedRuntimeVersion"

    is UpdateResult.Failed -> when (reason) {
        UpdateFailure.NETWORK -> "network failure"
        UpdateFailure.INVALID_MANIFEST -> "invalid manifest"
        UpdateFailure.FAILED_VERIFICATION -> "hash verification failed"
        UpdateFailure.STORAGE -> "could not store patch"
        UpdateFailure.UNKNOWN -> "failed"
    } + ": $message"
}
