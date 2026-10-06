package dev.dootah.runtime

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import dev.dootah.portable.PortableProgram
import dev.dootah.runtime.sandbox.RestrictedSandbox
import expo.modules.updates.UpdatesController
import expo.modules.updates.EnabledUpdatesController
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RenderValue(val title: String, val updateId: UUID?,
  val overrides: Map<String, String> = emptyMap(), val embedded: Boolean = false,
  val program: PortableProgram? = null)

/** Native updater and native Compose. No ReactHost is constructed or started. */
open class DootahApplication : Application() {
  private val mutableValue = MutableStateFlow(RenderValue("Dootah Native Baseline", null))
  val value = mutableValue.asStateFlow()
  internal var dispatchValue by mutableStateOf(mutableValue.value)
    private set
  private var cloudTelemetry: CloudTelemetry? = null
  fun enrollTelemetry(ticket: String, result: (Boolean) -> Unit) {
    cloudTelemetry?.enroll(ticket, result) ?: result(false)
  }
  private var acknowledged: UUID? = null
  private val frames = NativeFrameObserver(this)
  private val worker = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + worker)
  private var sandbox: RestrictedSandbox? = null
  private var rejected: UUID? = null
  private data class Invocation(val id: String, val types: List<String>, val values: List<Any?>)
  private val results = androidx.compose.runtime.mutableStateMapOf<Invocation, String>()
  private val pending = HashSet<Invocation>()

  // A miss only reads snapshot state and queues work; it creates no Compose groups.
  internal fun lookup(value: RenderValue, id: String, types: List<String>, input: List<Any?>): String? {
    if (dispatchValue !== value) return null
    val program = value.program ?: return null
    try {
      require(program.parameters(id) == types && input.size == types.size) { "Installed input ABI mismatch" }
      input.forEachIndexed { index, item -> PortableProgram.validateValue(item, types[index]) }
      val key = Invocation(id, types, input)
      results[key]?.let { return it }
      if (key in pending) return null
      require(results.size + pending.size < 128 && pending.size < 32) { "Invocation budget" }
      pending.add(key)
      scope.launch {
        val text = evaluate(value, id, types, input)
        withContext(Dispatchers.Main) {
          pending.remove(key)
          if (text != null && dispatchValue === value) results[key] = text
        }
      }
    } catch (e: Exception) {
      val fallback = RenderValue("Dootah Native Baseline", null)
      mutableValue.value = fallback; dispatchValue = fallback
      scope.launch { reject(value.updateId, e) }
    }
    return null
  }

  fun onFrameCommitted(value: RenderValue) {
    val id = value.updateId ?: return
    if (mutableValue.value !== value || acknowledged == id || rejected == id) return
    val controller = UpdatesController.instance as? EnabledUpdatesController ?: return
    if (controller.onNativeContentRendered(id)) {
      acknowledged = id
      cloudTelemetry?.healthy(id)
      Log.i("Dootah", "Compose frame committed; health accepted update=$id title=${value.title}")
    }
  }

  override fun onCreate() {
    super.onCreate()
    registerActivityLifecycleCallbacks(frames)
    UpdatesController.initializeWithoutStarting(this, false)
    (UpdatesController.instance as? EnabledUpdatesController)?.let {
      cloudTelemetry = CloudTelemetry(this, it)
    }
    UpdatesController.instance.start()
    scope.launch {
      var id: UUID? = null
      try {
        val controller = UpdatesController.instance as? EnabledUpdatesController ?: return@launch
        // This is Expo's verified/cached selection, never a ReactHost bundle loader.
        val path = controller.launchAssetFile
        id = controller.launchedUpdateId ?: return@launch
        controller.startNativeMonitoring()
        val next = if (id == controller.embeddedUpdateId) {
          RenderValue("Dootah Native Baseline", id, embedded = true)
        } else {
          require(path != null) { "Missing remote artifact" }
          val file = File(path)
          require(file.length() in 1..PortableProgram.MAX_BYTES.toLong()) { "Artifact size" }
          val bytes = file.inputStream().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
              val count = stream.read(buffer)
              if (count < 0) break
              require(output.size() + count <= PortableProgram.MAX_BYTES) { "Artifact size" }
              output.write(buffer, 0, count)
            }
            output.toByteArray()
          }
          require(bytes.size <= PortableProgram.MAX_BYTES)
          val runtime = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData.getString("expo.modules.updates.EXPO_RUNTIME_VERSION")!!
          val source = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
          val program = PortableProgram(source, runtime)
          sandbox = RestrictedSandbox(this@DootahApplication)
          RenderValue("Dootah portable logic", id, program = program)
        }
        cloudTelemetry?.selected(id, next.embedded)
        withContext(Dispatchers.Main) {
          mutableValue.value = next; dispatchValue = next
          if (next.embedded) frames.invalidate()
        }
        Log.i("Dootah", "Native update selected update=$id embedded=${next.embedded} sandbox=${next.program != null}")
      } catch (e: Exception) { reject(id, e) }
    }
  }

  internal suspend fun evaluate(value: RenderValue, id: String, types: List<String>, input: List<Any?>): String? =
    withContext(worker) {
      if (mutableValue.value !== value || rejected == value.updateId) return@withContext null
      try {
        val program = value.program ?: return@withContext null
        val result = sandbox!!.evaluate(program.source(id, types, input))
        program.result(result)
      } catch (e: Exception) { reject(value.updateId, e); null }
    }

  private suspend fun reject(id: UUID?, error: Exception) {
    Log.w("Dootah", "Portable override rejected; native fallback retained: ${error.javaClass.simpleName}: ${error.message}")
    sandbox?.close(); sandbox = null
    if (id != null && rejected != id) {
      rejected = id
      cloudTelemetry?.rejected(id)
      (UpdatesController.instance as? EnabledUpdatesController)?.rejectNativeLaunch(id)
    }
    withContext(Dispatchers.Main) {
      val fallback = RenderValue("Dootah Native Baseline", null)
      mutableValue.value = fallback; dispatchValue = fallback
      results.clear(); pending.clear()
    }
  }
}
