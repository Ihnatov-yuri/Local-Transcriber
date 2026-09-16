package nl.ihnatov.transcriber.asr

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Super" dual-engine merge, ported from the Mac app's
 * `Transcriberr/ASR/Backends/EnsembleBackend.swift`. Runs two ASR engines
 * on every chunk and votes a merged transcript: keep what both agree on,
 * resolve conflicts by confidence × per-language prior, never introduce
 * content found in neither. Sequential on the phone — no ANE/GPU split to
 * exploit the way the Mac's concurrent `async let` does, so engine B
 * starts only after engine A returns.
 *
 * Chunking happens INSIDE this backend (reusing
 * [SherpaOfflineBackend.computeInMemoryCutPoints]) rather than in
 * [TranscriptionRunner], because the merge only makes sense chunk-by-chunk
 * — two engines' whole-buffer segment lists have no reason to share
 * boundaries. [AsrBackend.transcribe] still takes one whole buffer, same
 * as every other backend; the chunking is an internal detail.
 *
 * Word-level voting needs real per-word confidence, which no Android
 * engine has yet (see [RoverMerge]'s doc comment) — every merge here runs
 * with confidence defaulted to 1f, so disputed words are resolved purely
 * by [votePrior] until that lands upstream. Still meaningfully better
 * than picking one engine outright: matches stay matched, and genuinely
 * divergent chunks still reach arbitration.
 */
class EnsembleBackend(
    private val kindA: AsrBackendKind,
    private val kindB: AsrBackendKind,
    private val factory: AsrFactory,
    /** "Max quality" RUN sheet toggle — enables the constrained-JSON arbitration second pass on low-agreement chunks. */
    private val arbitrationEnabled: Boolean = false,
    /** Languages of this run — lets each sub-engine's [AsrFactory.resolveModel] prefer a matching specialised model. */
    private val languages: List<String> = emptyList(),
) : AsrBackend {

    init {
        require(kindA != kindB) { "Super mode needs two different engines, got $kindA twice" }
        require(kindA != AsrBackendKind.NemotronStream && kindB != AsrBackendKind.NemotronStream) {
            "NemotronStream is streaming-only, not usable as a Super mode sub-engine"
        }
    }

    private var backendA: AsrBackend? = null
    private var backendB: AsrBackend? = null

    /** Only set (and loaded) when arbitration is enabled and neither sub-engine is already Gemma4. */
    private var standaloneArbiter: Gemma4Backend? = null
    private var gemmaWedgeCount = 0
    private var gemmaBenched = false

    override val id: String get() = "ensemble / ${kindA.name}+${kindB.name}"
    override val isReady: Boolean get() = backendA?.isReady == true && backendB?.isReady == true

    /** Ignored — each sub-engine resolves its own model via [AsrFactory]; there's no single "ensemble model file". */
    override suspend fun load(modelPath: String): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            val a = loadSub(kindA) ?: return@withContext Result.failure(
                IllegalStateException("No model installed for $kindA")
            )
            val b = loadSub(kindB) ?: return@withContext Result.failure(
                IllegalStateException("No model installed for $kindB")
            )
            backendA = a
            backendB = b
            if (arbitrationEnabled) {
                standaloneArbiter = (a as? Gemma4Backend) ?: (b as? Gemma4Backend) ?: run {
                    val g = factory.create(AsrBackendKind.Gemma4) as Gemma4Backend
                    val gModel = factory.resolveModel(AsrBackendKind.Gemma4)
                    if (gModel != null) {
                        val res = g.load(gModel.absolutePath)
                        if (res.isFailure) {
                            Log.w(TAG, "standalone arbiter failed to load, arbitration disabled this run", res.exceptionOrNull())
                            null
                        } else g
                    } else null
                }
            }
            gemmaWedgeCount = 0
            gemmaBenched = false
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun loadSub(kind: AsrBackendKind): AsrBackend? {
        val modelFile = factory.resolveModel(kind, languages) ?: return null
        val backend = factory.create(kind)
        val res = backend.load(modelFile.absolutePath)
        return if (res.isSuccess) backend else null
    }

    /** The non-Gemma sub-engine, when Gemma is one of the pair and got benched. Falls back to A otherwise. */
    private fun soloBackend(): AsrBackend? = when {
        kindA == AsrBackendKind.Gemma4 -> backendB
        kindB == AsrBackendKind.Gemma4 -> backendA
        else -> backendA
    }

    private val gemmaIsSubEngine: Boolean
        get() = kindA == AsrBackendKind.Gemma4 || kindB == AsrBackendKind.Gemma4

    override suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        language: String?,
        translate: Boolean,
        progress: ((Float) -> Unit)?,
    ): Result<List<RawSegment>> = withContext(Dispatchers.Default) {
        val a = backendA
        val b = backendB
        if (a == null || b == null) return@withContext Result.failure(IllegalStateException("Ensemble not loaded"))

        if (gemmaBenched) {
            val solo = soloBackend() ?: a
            return@withContext solo.transcribe(samples, sampleRate, language, translate, progress)
        }

        try {
            // Gemma4Backend.transcribe splits anything longer than
            // CHUNK_SECONDS into a second inference, so with Gemma in the
            // pair the target sits low enough that target + flex never
            // exceeds it: one chunk here means one Gemma call, not a 28 s
            // call plus a 6 s tail.
            val targetChunkSec = if (gemmaIsSubEngine) {
                Gemma4Backend.CHUNK_SECONDS - SherpaOfflineBackend.FLEX_SEC
            } else {
                SherpaOfflineBackend.TARGET_CHUNK_SEC
            }
            val cuts = SherpaOfflineBackend.computeInMemoryCutPoints(samples, sampleRate, targetChunkSec)
            val out = mutableListOf<RawSegment>()
            for ((i, cut) in cuts.withIndex()) {
                val slice = samples.copyOfRange(cut.startSample, cut.endSample)
                val offsetSec = cut.startSample.toDouble() / sampleRate
                val durationSec = (cut.endSample - cut.startSample).toDouble() / sampleRate
                val merged = transcribeChunk(slice, sampleRate, language, translate, offsetSec, durationSec)
                if (merged != null) out.add(merged)
                progress?.invoke((i + 1).toFloat() / cuts.size)
            }
            Result.success(out)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "ensemble transcribe failed", t)
            Result.failure(t)
        }
    }

    private suspend fun transcribeChunk(
        slice: FloatArray,
        sampleRate: Int,
        language: String?,
        translate: Boolean,
        offsetSec: Double,
        durationSec: Double,
    ): RawSegment? {
        val a = backendA!!
        val b = backendB!!
        // Sequential — the plan calls for this explicitly (no ANE/GPU split
        // to exploit on a phone the way the Mac's concurrent call has).
        //
        // kotlin.runCatching catches CancellationException the same as any
        // other Throwable — .getOrNull() alone would turn a cancelled
        // Whisper call (WhisperCppBackend.transcribe now throws on Stop)
        // into a plain null result, and this per-chunk loop would just
        // treat it as "that engine produced nothing" and carry on to the
        // next chunk instead of stopping. onFailure rethrows it first.
        val resultA = runCatching { a.transcribe(slice, sampleRate, language, translate, null) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        val resultB = runCatching { b.transcribe(slice, sampleRate, language, translate, null) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        val segsA = resultA?.getOrNull()
        val segsB = resultB?.getOrNull()
        trackWedge(engineFailed = kindA == AsrBackendKind.Gemma4 && segsA == null)
        trackWedge(engineFailed = kindB == AsrBackendKind.Gemma4 && segsB == null)

        val textA = segsA?.joinToString(" ") { it.text }?.trim().orEmpty()
        val textB = segsB?.joinToString(" ") { it.text }?.trim().orEmpty()
        if (textA.isEmpty() && textB.isEmpty()) return null
        if (textA.isEmpty()) return RawSegment(offsetSec, offsetSec + durationSec, textB)
        if (textB.isEmpty()) return RawSegment(offsetSec, offsetSec + durationSec, textA)

        val priorA = votePrior(kindA, language)
        val priorB = votePrior(kindB, language)
        val wordsA = segsA?.flatMap { it.words.orEmpty() }.orEmpty()
        val wordsB = segsB?.flatMap { it.words.orEmpty() }.orEmpty()
        val haveWords = wordsA.isNotEmpty() && wordsB.isNotEmpty()

        val agreement: Double
        val mergedText: String
        if (haveWords) {
            val scoredA = wordsA.map { ScoredWord.of(it.text, it.confidence ?: 1f) }
            val scoredB = wordsB.map { ScoredWord.of(it.text, it.confidence ?: 1f) }
            agreement = RoverMerge.diceSimilarity(scoredA.map { it.norm }, scoredB.map { it.norm })
            mergedText = if (agreement >= 0.999) {
                if (priorA >= priorB) textA else textB
            } else {
                RoverMerge.merge(scoredA, scoredB, priorA, priorB).ifEmpty { if (priorA >= priorB) textA else textB }
            }
        } else {
            agreement = RoverMerge.tokenSimilarity(textA, textB)
            mergedText = if (priorA >= priorB) textA else textB
        }

        val finalText = if (arbitrationEnabled && agreement < DISPUTE_THRESHOLD && standaloneArbiter != null &&
            gemmaWedgeCount < MAX_GEMMA_WEDGES_PER_RUN
        ) {
            arbitrateChunk(textA, textB, wordsA, wordsB, mergedText)
        } else {
            mergedText
        }
        return RawSegment(offsetSec, offsetSec + durationSec, finalText)
    }

    private suspend fun arbitrateChunk(
        textA: String,
        textB: String,
        wordsA: List<Word>,
        wordsB: List<Word>,
        fallback: String,
    ): String {
        val arbiter = standaloneArbiter ?: return fallback
        val scoredA = wordsA.map { ScoredWord.of(it.text, it.confidence ?: 1f) }
        val scoredB = wordsB.map { ScoredWord.of(it.text, it.confidence ?: 1f) }
        val disputes = if (scoredA.isNotEmpty() && scoredB.isNotEmpty()) {
            RoverMerge.extractDisputes(scoredA, scoredB).take(MAX_DISPUTES_PER_CHUNK)
        } else emptyList()
        val result = if (disputes.isNotEmpty()) {
            arbiter.arbitrateDisputes(disputes, kindA.name, kindB.name)
        } else {
            // No word-level data for this chunk — arbitrate the two whole
            // texts as a single "dispute" of one choice between A and B.
            arbiter.arbitrateDisputes(
                listOf(RoverMerge.Dispute(0, textA, textB, 1f, 1f)),
                kindA.name, kindB.name,
            )
        }
        if (result == null) return fallback
        return if (disputes.isNotEmpty()) {
            RoverMerge.applyChoices(scoredA, scoredB, disputes, result)
        } else {
            (result.getOrNull(0)?.let { if (it == 0) textA else textB }) ?: fallback
        }
    }

    private fun trackWedge(engineFailed: Boolean) {
        if (!engineFailed || !gemmaIsSubEngine) return
        gemmaWedgeCount++
        if (shouldBench(gemmaWedgeCount, MAX_GEMMA_WEDGES_PER_RUN) && !gemmaBenched) {
            gemmaBenched = true
            Log.w(TAG, "Gemma benched after $gemmaWedgeCount wedges — rest of run is " +
                "${if (kindA == AsrBackendKind.Gemma4) kindB else kindA} only")
        }
    }

    override suspend fun release() {
        runCatching { backendA?.release() }
        runCatching { backendB?.release() }
        val arb = standaloneArbiter
        if (arb != null && arb !== backendA && arb !== backendB) {
            runCatching { arb.release() }
        }
        backendA = null
        backendB = null
        standaloneArbiter = null
    }

    companion object {
        private const val TAG = "EnsembleBackend"

        /** Chunks whose vote agreement falls below this are candidates for arbitration. */
        const val DISPUTE_THRESHOLD = 0.85

        /** Per Mac precedent: stop feeding Gemma after this many wedges in one run, finish on the other engine alone. */
        const val MAX_GEMMA_WEDGES_PER_RUN = 2

        /** Cost bound: a chunk with many disputed words only sends the worst few to Gemma, not all of them. */
        const val MAX_DISPUTES_PER_CHUNK = 8

        /** Pure predicate behind the benching decision — see [MAX_GEMMA_WEDGES_PER_RUN]'s doc comment. */
        fun shouldBench(wedgeCount: Int, maxWedges: Int): Boolean = wedgeCount >= maxWedges

        /**
         * Per-language trust multiplier for the vote. The Mac's own priors
         * (Parakeet weak on Ukrainian, Parakeet-v2 weak on non-English) come
         * from real sweep data across Mac-only model variants that don't
         * exist on Android — there's no equivalent data here yet, so every
         * pairing defaults to neutral (1.0) rather than guessing. Re-tune
         * from real Android transcripts once available.
         */
        fun votePrior(kind: AsrBackendKind, language: String?): Float = 1f
    }
}
