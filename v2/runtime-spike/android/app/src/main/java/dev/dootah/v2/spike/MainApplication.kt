package dev.dootah.v2.spike

import android.app.Application
import expo.modules.updates.UpdatesController
import expo.modules.updates.EnabledUpdatesController
import java.util.UUID
import android.content.res.Configuration

import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.ReactHost
import com.facebook.react.common.ReleaseLevel
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint

import expo.modules.ApplicationLifecycleDispatcher
import expo.modules.ExpoReactHostFactory
import expo.modules.brownfield.BrownfieldMessaging
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RenderValue(val title: String, val updateId: UUID?)

class MainApplication : Application(), ReactApplication {
  private val mutableValue = MutableStateFlow(RenderValue("Dootah Native Baseline", null))
  val value = mutableValue.asStateFlow()
  private var acknowledged: UUID? = null

  fun onFrameCommitted(value: RenderValue) {
    val id = value.updateId ?: return
    if (mutableValue.value != value || acknowledged == id) return
    val controller = UpdatesController.instance as? EnabledUpdatesController ?: return
    if (controller.onNativeContentRendered(id)) {
      acknowledged = id
      Log.i("DootahV2", "Compose frame committed; health accepted update=$id title=${value.title}")
    }
  }

  override val reactHost: ReactHost by lazy {
    ExpoReactHostFactory.getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages,
      useDevSupport = false
    )
  }

  override fun onCreate() {
    super.onCreate()
    DefaultNewArchitectureEntryPoint.releaseLevel = try {
      ReleaseLevel.valueOf(BuildConfig.REACT_NATIVE_RELEASE_LEVEL.uppercase())
    } catch (e: IllegalArgumentException) {
      ReleaseLevel.STABLE
    }
    loadReactNative(this)
    ApplicationLifecycleDispatcher.onApplicationCreate(this)
    val mainHandler = Handler(Looper.getMainLooper())
    BrownfieldMessaging.addListener { message ->
      val title = message["title"] as? String
      if (message["type"] == "dootah.title.v1" && title != null && title.length <= 256) {
        mainHandler.post {
          val controller = UpdatesController.instance as? EnabledUpdatesController
          val id = controller?.launchedUpdateId
          mutableValue.value = RenderValue(title, id)
          Log.i("DootahV2", "Hermes bridge received update=$id title=$title")
        }
      }
    }
    // Start only the JS instance. The Activity owns the native Compose surface.
    reactHost.start()
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    ApplicationLifecycleDispatcher.onConfigurationChanged(this, newConfig)
  }
}
