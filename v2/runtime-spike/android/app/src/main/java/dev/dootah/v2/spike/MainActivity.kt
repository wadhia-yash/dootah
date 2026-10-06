package dev.dootah.v2.spike

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.os.Build
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : AppCompatActivity() {
  private val runtime get() = application as MainApplication

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val value by runtime.value.collectAsState()
      val view = LocalView.current
      MaterialTheme {
        ExampleScreen(value.title, Modifier.drawWithContent {
          drawContent()
          if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated && value.updateId != null) {
            view.viewTreeObserver.registerFrameCommitCallback {
              view.post { runtime.onFrameCommitted(value) }
            }
          }
        })
      }
    }
  }

  override fun onResume() {
    super.onResume()
    runtime.reactHost.onHostResume(this)
  }

  override fun onPause() {
    runtime.reactHost.onHostPause(this)
    super.onPause()
  }

  override fun onDestroy() {
    runtime.reactHost.onHostDestroy(this)
    super.onDestroy()
  }
}

@Composable
fun ExampleScreen(title: String, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(title)
  }
}
