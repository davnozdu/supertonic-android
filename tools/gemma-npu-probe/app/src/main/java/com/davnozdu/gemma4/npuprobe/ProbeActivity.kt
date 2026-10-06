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
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags

/** Independent, offline NPU experiment. Does not bind Android TTS or touch MyTTS. */
class ProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var output: TextView
    private var running: Job? = null
    private val resultFile by lazy { File(getExternalFilesDir(null), "probe-results.jsonl") }
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitor: Job? = null
    private var peakPssKiB = 0L
    private val backend by lazy { intent.getStringExtra("backend") ?: "npu" }
    private val benchmark by lazy { intent.getBooleanExtra("benchmark", false) }
    private val cpuThreads by lazy { intent.getIntExtra("threads", 2).coerceIn(1, 8) }
    @Volatile private var activeConversation: com.google.ai.edge.litertlm.Conversation? = null

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
                runCatching { activeConversation?.cancelProcess() }
                running?.cancel()
            }
        })
        layout.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        output.text = "Gemma 4 / QHexRT 0.20.19 / ARM64\nМодель: models/gemma4-e2b-v81/gemma4-e2b.json\n"
        startProbe(intent.getBooleanExtra("run", false))
    }

    @Synchronized private fun record(stage: String, details: JSONObject) {
        val entry = JSONObject().put("stage", stage).put("backend", backend)
            .put("benchmark", benchmark).put("threads", if (backend == "cpu") cpuThreads else JSONObject.NULL)
            .put("uptimeMs", SystemClock.elapsedRealtime()).put("details", details)
        resultFile.appendText(entry.toString() + "\n")
        Log.i("GemmaNpuProbe", entry.toString())
        runOnUiThread { output.append("\n$stage: $details\n") }
    }

    private fun startProbe(generate: Boolean) {
        if (running?.isActive == true) return
        running = scope.launch(Dispatchers.IO) {
            try {
                if (backend != "npu") {
                    if (generate) runLiteRt()
                    return@launch
                }
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
                val modelId = "gemma4-e2b-v81-probe"
                val imported = RunAnywhere.importModel(ModelImportRequest(
                    model = ModelInfo(id = modelId, name = "Gemma 4 E2B v81 offline probe",
                        category = ModelCategory.MODEL_CATEGORY_LANGUAGE,
                        format = ModelFormat.MODEL_FORMAT_FOLDER,
                        framework = InferenceFramework.INFERENCE_FRAMEWORK_QHEXRT,
                        preferred_framework = InferenceFramework.INFERENCE_FRAMEWORK_QHEXRT,
                        source = ModelSource.MODEL_SOURCE_LOCAL,
                        context_length = 512, local_path = modelDir.absolutePath),
                    source_path = modelDir.absolutePath, copy_into_managed_storage = false,
                    overwrite_existing = true, validate_before_register = true))
                record("imported", JSONObject().put("registered", imported.registered)
                    .put("path", imported.local_path).put("error", imported.error?.toString()))
                check(imported.error == null && imported.registered) { "Local import failed: ${imported.error}" }
                startMemoryMonitor()
                val started = SystemClock.elapsedRealtime()
                val loaded = RunAnywhere.loadModel(ModelLoadRequest(
                    model_id = modelId,
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
                val cases = if (benchmark) ProbeCases.benchmark else ProbeCases.legacy
                repeat(if (benchmark) 2 else 1) { round ->
                for ((index, trial) in cases.withIndex()) {
                    ensureActive()
                    val t0 = SystemClock.elapsedRealtime()
                    val cpu0 = android.os.Process.getElapsedCpuTime()
                    val trialOptions = options.copy(max_output_tokens = trial.limit, system_prompt = trial.system)
                    val response = withTimeout(120_000) { RunAnywhere.generate(trial.prompt, trialOptions) }
                    record("generated", JSONObject().put("index", index).put("case", trial.id).put("round", round)
                        .put("limit", trial.limit).put("wallMs", SystemClock.elapsedRealtime() - t0)
                        .put("cpuMs", android.os.Process.getElapsedCpuTime() - cpu0)
                        .put("text", response.text).put("framework", response.framework)
                        .put("executedOn", response.executed_on?.name).put("tokens", response.response_tokens)
                        .put("decodeMs", response.decode_time_ms).put("error", response.error?.toString()))
                    check(response.error == null && response.text.isNotBlank()) { "No usable generation" }
                    check(response.executed_on == ExecutionTarget.EXECUTION_TARGET_ON_DEVICE) { "Not on-device" }
                }
                }
                delay(5000) // Check sustained residency before unloading the native model.
                record("complete", JSONObject().put("success", true))
            } catch (cancelled: CancellationException) {
                runCatching { RunAnywhere.cancelGeneration() }
                record("cancelled", JSONObject().put("reason", cancelled.javaClass.simpleName))
            } catch (failure: Throwable) {
                record("failed", JSONObject().put("type", failure.javaClass.name).put("message", failure.message))
                Log.e("GemmaNpuProbe", "Probe failed", failure)
            } finally {
                if (generate && backend == "npu") withContext(NonCancellable) {
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

    @OptIn(ExperimentalApi::class)
    private suspend fun runLiteRt() {
        check(backend == "gpu" || backend == "cpu") { "Unknown backend: $backend" }
        val file = File(getExternalFilesDir(null), "models/gemma-4-E2B-it.litertlm")
        check(file.length() == 2588147712L) { "LiteRT Gemma file missing or incomplete" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } ==
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c") { "LiteRT model SHA-256 mismatch" }
        val before = memory()
        record("before_load", before)
        check(before.getLong("memAvailableKiB") >= 2L * 1024 * 1024) { "Less than 2 GiB available" }
        ExperimentalFlags.enableSpeculativeDecoding = false
        ExperimentalFlags.enableBenchmark = true
        val engine = Engine(EngineConfig(file.path,
            backend = if (backend == "gpu") Backend.GPU() else Backend.CPU(threadCount = cpuThreads),
            audioBackend = Backend.CPU(), maxNumTokens = 512,
            cacheDir = File(cacheDir, "litert-$backend").apply { mkdirs() }.path))
        startMemoryMonitor()
        try {
            val t0 = SystemClock.elapsedRealtime()
            engine.initialize()
            record("loaded", JSONObject().put("loadMs", SystemClock.elapsedRealtime() - t0)
                .put("framework", "LiteRT-LM 0.17.1").put("deviceKind", backend).put("context", 512)
                .put("speculative", false))
            val cases = if (benchmark) ProbeCases.benchmark else ProbeCases.legacy
            repeat(if (benchmark) 2 else 1) { round ->
                for ((index, trial) in cases.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    // Include conversation creation/prefill in the wall/CPU measurement.
                    val started = SystemClock.elapsedRealtime()
                    val cpu0 = android.os.Process.getElapsedCpuTime()
                    engine.createConversation(ConversationConfig(systemInstruction = trial.system.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                        samplerConfig = SamplerConfig(1, 0.95, 0.0),
                        thinkingConfig = ThinkingConfig(false, 0), maxOutputToken = trial.limit)).use { conversation ->
                        activeConversation = conversation
                        val response = conversation.sendMessage(trial.prompt).toString()
                        val wall = SystemClock.elapsedRealtime() - started
                        val cpu = android.os.Process.getElapsedCpuTime() - cpu0
                        // Optional metrics must not discard an otherwise valid response.
                        val metrics = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
                        record("generated", JSONObject().put("index", index).put("case", trial.id).put("round", round)
                            .put("limit", trial.limit).put("wallMs", wall).put("cpuMs", cpu).put("text", response)
                            .put("tokens", metrics?.lastDecodeTokenCount).put("prefillTokens", metrics?.lastPrefillTokenCount)
                            .put("ttftSeconds", metrics?.timeToFirstTokenInSecond)
                            .put("decodeTokensPerSecond", metrics?.lastDecodeTokensPerSecond)
                            .put("metricsAvailable", metrics != null))
                        check(response.isNotBlank()) { "No usable generation" }
                        activeConversation = null
                    }
                }
            }
            delay(5000)
            record("complete", JSONObject().put("success", true))
        } finally {
            activeConversation = null
            runCatching { engine.close() }
            monitor?.cancel()
            record("after_unload", memory())
        }
    }

    override fun onDestroy() {
        scope.cancel()
        memoryScope.cancel()
        super.onDestroy()
    }
}
