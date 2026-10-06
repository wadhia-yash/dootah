package dev.dootah.consumer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.dootah.runtime.DootahActivity

/** Device-only diagnostic fixture, absent from the release APK. */
class BenchmarkActivity : DootahActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({
            val table = dootah.value.value.overrides
            val key = "dth1:" + "0".repeat(64)
            var matches = 0
            repeat(7) { round ->
                val start = System.nanoTime()
                repeat(100_000) { if (table[key] != null) matches++ }
                Log.i("DootahBenchmark", "lookup round=$round ns=${System.nanoTime() - start} iterations=100000 size=${table.size} matches=$matches")
            }
            setContent { DispatchBenchmark() }
        }, 3000)
    }
}

// An empty benchmark has no calls from which Compose can infer its UI applier.
@androidx.compose.runtime.ComposableTarget("androidx.compose.ui.UiComposable")
@Composable fun EmptyProbe() {}

@Composable fun DispatchBenchmark() {
    repeat(7) { round ->
        val start = System.nanoTime()
        repeat(10_000) { EmptyProbe() }
        Log.i("DootahBenchmark", "entry round=$round ns=${System.nanoTime() - start} iterations=10000")
    }
    Text("Dispatch benchmark complete")
}
