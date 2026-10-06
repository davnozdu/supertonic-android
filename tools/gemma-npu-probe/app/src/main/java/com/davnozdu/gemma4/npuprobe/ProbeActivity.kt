package com.davnozdu.gemma4.npuprobe

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.runanywhere.proto.v1.*
import com.runanywhere.sdk.npu.qhexrt.QHexRT
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Independent, offline NPU experiment. Does not bind Android TTS or touch MyTTS. */
class ProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var output: TextView
    private var running: Job? = null
    private val resultFile by lazy { File(getExternalFilesDir(null), "probe-results.jsonl") }
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitor: Job? = null
    private var peakPssKiB = 0L

    private fun memory(): JSONObject {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        fun counters(path: String): Map<String, Long> = File(path).useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                parts.getOrNull(1)?.toLongOrNull()?.let { parts[0].removeSuffix(":") to it }
            }.toMap()
        }
        val system = counters("/proc/meminfo")
        val own = counters("/proc/self/status")
        peakPssKiB = maxOf(peakPssKiB, info.totalPss.toLong())
        return JSONObject().put("pssKiB", info.totalPss).put("peakPssKiB", peakPssKiB)
            .put("rssKiB", own["VmRSS"]).put("vmSwapKiB", own["VmSwap"])
            .put("memAvailableKiB", system.getValue("MemAvailable"))
            .put("swapFreeKiB", system["SwapFree"]).put("ionUsedKiB", system["IonTotalUsed"])
            .put("gpuUsedKiB", system["GPUTotalUsed"])
    }

    // Native model loading may block cancellation. The isolated probe can end its
    // own process without interrupting MyTTS if the RAM reserve is exhausted.
    private fun startMemoryMonitor() {
        monitor = memoryScope.launch {
            val deadline = SystemClock.elapsedRealtime() + 240_000
            while (isActive) {
                val sample = memory()
                record("memory", sample)
                if (sample.getLong("memAvailableKiB") < 786_432 ||
                    sample.getLong("pssKiB") > 5L * 1024 * 1024 ||
                    SystemClock.elapsedRealtime() > deadline) {
                    record("budget_stop", sample.put("reason", "RAM reserve / 5 GiB PSS / 240s deadline"))
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@launch
                }
                delay(500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        output = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        layout.addView(Button(this).apply {
            text = "Проверить NPU"
            setOnClickListener { startProbe(false) }
        })
        layout.addView(Button(this).apply {
            text = "Запустить Gemma 4 E2B"
            setOnClickListener { startProbe(true) }
        })
        layout.addView(Button(this).apply {
            text = "Отменить"
            setOnClickListener {
                scope.launch(Dispatchers.IO) { runCatching { RunAnywhere.cancelGeneration() } }
                running?.cancel()
            }
        })
        layout.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        output.text = "Gemma 4 / QHexRT 0.20.19 / ARM64\nМодель: models/gemma4-e2b-v81/gemma4-e2b.json\n"
        startProbe(intent.getBooleanExtra("run", false))
    }

    @Synchronized private fun record(stage: String, details: JSONObject) {
        val entry = JSONObject().put("stage", stage).put("uptimeMs", SystemClock.elapsedRealtime()).put("details", details)
        resultFile.appendText(entry.toString() + "\n")
        Log.i("GemmaNpuProbe", entry.toString())
        runOnUiThread { output.append("\n$stage: $details\n") }
    }

    private fun startProbe(generate: Boolean) {
        if (running?.isActive == true) return
        running = scope.launch(Dispatchers.IO) {
            try {
                RunAnywhere.initialize(applicationContext)
                val capability = QHexRT.probeNpu()
                record("capability", JSONObject().put("soc", capability.soc_model)
                    .put("arch", capability.hexagon_arch.name).put("supported", capability.supported)
                    .put("gemma4E2bSupported", QHexRT.modelSupportsArchitecture("gemma4_e2b", capability.hexagon_arch)))
                check(capability.supported) { "QHexRT does not support this device" }
                QHexRT.register()
                record("registered", JSONObject().put("qhexrtVersion", QHexRT.version))
                if (!generate) return@launch
                val modelDir = File(getExternalFilesDir(null), "models/gemma4-e2b-v81")
                val manifest = File(modelDir, "gemma4-e2b.json")
                check(manifest.isFile) { "Model missing: $manifest" }
                val pinned = JSONObject(assets.open("model-files.json").bufferedReader().use { it.readText() }).getJSONArray("files")
                for (i in 0 until pinned.length()) {
                    val spec = pinned.getJSONObject(i)
                    val file = File(modelDir, spec.getString("name"))
                    check(file.isFile && file.length() == spec.getLong("size")) { "Missing or incomplete model file: ${file.name}" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().buffered().use { input ->
                        val buf = ByteArray(1024 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                        }
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    check(sha == spec.getString("sha256")) { "SHA-256 mismatch: ${file.name}" }
                }
                val before = memory()
                record("before_load", before)
                check(before.getLong("memAvailableKiB") >= 2L * 1024 * 1024) { "Less than 2 GiB available before loading" }
                startMemoryMonitor()
                val started = SystemClock.elapsedRealtime()
                val loaded = RunAnywhere.loadModel(ModelLoadRequest(
                    model_id = manifest.absolutePath,
                    category = ModelCategory.MODEL_CATEGORY_LANGUAGE,
                    framework = InferenceFramework.INFERENCE_FRAMEWORK_QHEXRT,
                    accelerator_policy = AcceleratorPolicy.ACCELERATOR_POLICY_NPU))
                record("loaded", JSONObject().put("loadMs", SystemClock.elapsedRealtime() - started)
                    .put("framework", loaded.framework.name).put("deviceKind", loaded.actual_device_kind)
                    .put("deviceName", loaded.actual_device_name).put("runtime", loaded.runtime_version)
                    .put("fallbackReason", loaded.fallback_reason).put("error", loaded.error?.toString()))
                check(loaded.error == null) { loaded.error.toString() }
                check(loaded.framework == InferenceFramework.INFERENCE_FRAMEWORK_QHEXRT) { "Unexpected backend" }
                val options = LLMGenerationOptions(max_output_tokens = 64, temperature = 0f, top_k = 1,
                    preferred_framework = InferenceFramework.INFERENCE_FRAMEWORK_QHEXRT,
                    execution_target = ExecutionTarget.EXECUTION_TARGET_ON_DEVICE,
                    system_prompt = "Отвечай кратко по-русски.")
                val prompts = listOf("Сколько будет два плюс два? Ответь одной короткой фразой.",
                    "Назови столицу Чехии одним словом.")
                for ((index, prompt) in prompts.withIndex()) {
                    ensureActive()
                    val t0 = SystemClock.elapsedRealtime()
                    val cpu0 = android.os.Process.getElapsedCpuTime()
                    val response = withTimeout(120_000) { RunAnywhere.generate(prompt, options) }
                    record("generated", JSONObject().put("index", index).put("wallMs", SystemClock.elapsedRealtime() - t0)
                        .put("cpuMs", android.os.Process.getElapsedCpuTime() - cpu0)
                        .put("text", response.text).put("framework", response.framework)
                        .put("executedOn", response.executed_on?.name).put("tokens", response.response_tokens)
                        .put("decodeMs", response.decode_time_ms).put("error", response.error?.toString()))
                    check(response.error == null && response.text.isNotBlank()) { "No usable generation" }
                    check(response.executed_on == ExecutionTarget.EXECUTION_TARGET_ON_DEVICE) { "Not on-device" }
                }
                record("complete", JSONObject().put("success", true))
            } catch (cancelled: CancellationException) {
                runCatching { RunAnywhere.cancelGeneration() }
                record("cancelled", JSONObject().put("reason", cancelled.javaClass.simpleName))
            } catch (failure: Throwable) {
                record("failed", JSONObject().put("type", failure.javaClass.name).put("message", failure.message))
                Log.e("GemmaNpuProbe", "Probe failed", failure)
            } finally {
                if (generate) withContext(NonCancellable) {
                    runCatching { RunAnywhere.unloadModel(ModelUnloadRequest(
                        category = ModelCategory.MODEL_CATEGORY_LANGUAGE,
                        framework = InferenceFramework.INFERENCE_FRAMEWORK_QHEXRT,
                        unload_all = true)) }
                    monitor?.cancel()
                    record("after_unload", memory())
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        memoryScope.cancel()
        super.onDestroy()
    }
}
