package nl.ihnatov.transcriber.asr

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where Gemma 4 runs inference on this device.
 *
 *   Auto — try GPU first, fall back to CPU on init failure. This is the safe
 *          default and what the app used before the Settings page exposed
 *          the knob. Recommended for users who don't want to think about it.
 *   Gpu  — force GPU. If the device's OpenCL driver dislikes the model, load
 *          will fail outright — no silent fallback. Useful for benchmarking
 *          or for users who want to confirm GPU is actually being used.
 *   Cpu  — force CPU. Slower but more predictable; works on every device.
 *          Worth trying when GPU output looks corrupted (driver bugs do
 *          exist) or when GPU memory pressure causes OOM on big models.
 */
enum class GemmaBackendChoice(val id: String, val displayName: String, val subtitle: String) {
    Auto(
        id = "auto",
        displayName = "Auto (GPU → CPU)",
        subtitle = "Try GPU first, fall back to CPU if it fails. Default.",
    ),
    Gpu(
        id = "gpu",
        displayName = "GPU only",
        subtitle = "Force the OpenCL accelerator. Fast on Snapdragon, fails if unsupported.",
    ),
    Cpu(
        id = "cpu",
        displayName = "CPU only",
        subtitle = "Slowest but always works. Use if GPU output looks wrong.",
    ),
}

/**
 * Persisted Gemma 4 compute knobs. Lives next to [PromptStore] in the
 * `gemma_settings` SharedPreferences; reads through StateFlows so the
 * Settings UI updates without ceremony.
 *
 * The settings here apply to every Gemma 4 invocation — file transcription,
 * live transcription, and the text-only post-processing path — because all
 * three create their engine via [Gemma4Backend], which reads from this
 * store on `load()`. Changes do NOT live-mutate a running engine; the next
 * job (or the next Settings → Download / Run cycle) picks them up.
 */
class GemmaSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("gemma_settings", Context.MODE_PRIVATE)

    private val _backend = MutableStateFlow(
        GemmaBackendChoice.entries.firstOrNull { it.id == prefs.getString(KEY_BACKEND, null) }
            ?: GemmaBackendChoice.Auto
    )
    val backend: StateFlow<GemmaBackendChoice> = _backend.asStateFlow()

    /**
     * Total context window for one Gemma run. Used as `EngineConfig.maxNumTokens`.
     * Trade-off: bigger = fits more transcript at once (the Context-aware
     * rewrite preset and long single-shot translation both want this) but
     * uses more KV-cache RAM and slows decode slightly.
     *
     * Allowed values are gated in the UI: 4K / 8K / 16K / 32K. Gemma 3n E2B
     * advertises up to 32K but we cap there because S24-class devices start
     * to feel the memory pressure beyond that.
     */
    private val _maxNumTokens = MutableStateFlow(prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS))
    val maxNumTokens: StateFlow<Int> = _maxNumTokens.asStateFlow()

    /**
     * Number of CPU threads when running on CPU. 0 = let the SDK decide
     * (it picks ~half the cores). Explicit values 1..8 override; anything
     * else clamps. Ignored when backend is Gpu or when the Auto path lands
     * on GPU.
     */
    private val _cpuThreads = MutableStateFlow(prefs.getInt(KEY_CPU_THREADS, 0))
    val cpuThreads: StateFlow<Int> = _cpuThreads.asStateFlow()

    fun setBackend(value: GemmaBackendChoice) {
        _backend.value = value
        prefs.edit().putString(KEY_BACKEND, value.id).apply()
    }

    fun setMaxNumTokens(value: Int) {
        val clamped = value.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)
        _maxNumTokens.value = clamped
        prefs.edit().putInt(KEY_MAX_TOKENS, clamped).apply()
    }

    fun setCpuThreads(value: Int) {
        val clamped = value.coerceIn(0, 8)
        _cpuThreads.value = clamped
        prefs.edit().putInt(KEY_CPU_THREADS, clamped).apply()
    }

    fun resetToDefaults() {
        setBackend(GemmaBackendChoice.Auto)
        setMaxNumTokens(DEFAULT_MAX_TOKENS)
        setCpuThreads(0)
    }

    companion object {
        private const val KEY_BACKEND = "backend"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_CPU_THREADS = "cpu_threads"

        const val DEFAULT_MAX_TOKENS = 8192
        const val MIN_MAX_TOKENS = 2048
        const val MAX_MAX_TOKENS = 32768

        /** Values surfaced as chips in the UI. */
        val MAX_TOKENS_PRESETS = listOf(4096, 8192, 16384, 32768)
    }
}
