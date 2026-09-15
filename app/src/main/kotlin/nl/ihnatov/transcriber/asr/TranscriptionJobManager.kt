package nl.ihnatov.transcriber.asr

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.ihnatov.transcriber.data.PendingTask
import nl.ihnatov.transcriber.data.PendingTaskDao
import nl.ihnatov.transcriber.data.RecordingRepository

/**
 * Process-lifetime manager for transcription jobs.
 *
 * Concurrency model: one transcription runs at a time (the Gemma 4 engine
 * is itself single-stream and the device wouldn't survive parallel runs
 * anyway). Additional jobs queue behind it in [PendingTaskDao], FIFO by
 * arrival. The user can bulk-enqueue a stack of recordings and walk away.
 *
 * Charger-parking: when [start] is called with `runOnCharger = true` and
 * the device isn't on AC, the task is persisted with `waitForCharger=true`.
 * [PowerConnectedReceiver] calls [onPowerConnected] when AC arrives and the
 * oldest charger-parked task runs (subject to the single-job invariant).
 *
 * Persistence: tasks live in the `pending_tasks` Room table, which is
 * cascade-deleted with the parent Recording. Process death no longer drops
 * the queue — on construction we replay it. Tasks created while the
 * manager existed but never persisted (the older in-memory `waitingTasks`
 * map) are gone forever; that's the migration cost of getting persistence.
 */
class TranscriptionJobManager(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val runner: TranscriptionRunner,
    private val repository: RecordingRepository,
    private val pendingTasks: PendingTaskDao,
) {

    /** Per-recording job state. The Detail screen filters by its recordingId. */
    data class JobStatus(
        /** Engine + ASR are running right now. */
        val running: Boolean = false,
        /** Task is parked, will start when AC arrives. */
        val waitingForCharger: Boolean = false,
        /** Task is queued behind another job (not charger-parked). */
        val queued: Boolean = false,
        /**
         * Stop was requested but the coroutine hasn't unwound yet.
         * Coroutine cancellation is cooperative and only checked at
         * suspension points — a chunk already in flight (especially a
         * single-shot whisper.cpp call, which has none) keeps running
         * until it returns. This distinguishes "asked to stop, still
         * finishing the in-flight step" from a plain running job so the
         * UI can say so instead of looking unresponsive.
         */
        val stopping: Boolean = false,
        val stageLabel: String = "",
        val progress: Float = 0f,
        val error: String? = null,
    ) {
        /** True when the recording has any open work (running OR waiting). */
        val active: Boolean get() = running || waitingForCharger || queued
    }

    /** Snapshot of the call's intent — used to replay when a parked task wakes up. */
    data class Params(
        val backend: AsrBackendKind,
        val languages: List<String>,
        val translateTo: String?,
        val diarize: Boolean,
        val expectedSpeakers: Int = -1,
        /**
         * Hybrid mode (sherpa-clustered hints + Gemma transcription with
         * sherpa-authoritative reconciliation). Only meaningful when
         * diarize=true and backend=Gemma4. Silently ignored otherwise.
         */
        val hybridDiarize: Boolean = false,
        /**
         * Super mode (Phase 3 of the 2026-09 plan): run [superPairA] and
         * [superPairB] together and vote-merge instead of just [backend].
         * Persisted on the `pending_tasks` row (schema 7) — every start,
         * even an immediate one, round-trips through that row, so these
         * have to survive it or Super mode never runs.
         */
        val superMode: Boolean = false,
        val superPairA: AsrBackendKind? = null,
        val superPairB: AsrBackendKind? = null,
        /** "Max quality" — the constrained-JSON arbitration second pass. Meaningless unless [superMode]. */
        val maxQuality: Boolean = false,
    )

    private val _statuses = MutableStateFlow<Map<Long, JobStatus>>(emptyMap())
    val statuses: StateFlow<Map<Long, JobStatus>> = _statuses.asStateFlow()

    /** What's currently running. null when idle. */
    private var runningId: Long? = null
    private var runningParams: Params? = null
    private var currentJob: Job? = null
    /** Set by [checkpointRunning]; cleared (and its row deleted) when that job ends in-process. */
    @Volatile private var checkpointedId: Long? = null

    init {
        // Restore queued + charger-parked tasks from the DB so a process
        // restart doesn't drop them. We re-paint status (queued/waiting),
        // then attempt to drain the FIFO head if the device is plugged in
        // and no job is running.
        appScope.launch {
            val saved = runCatching { pendingTasks.listAll() }.getOrElse {
                Log.e(TAG, "failed to load pending tasks on init", it); emptyList()
            }
            for (t in saved) {
                if (t.waitForCharger) {
                    updateStatus(t.recordingId) {
                        JobStatus(
                            waitingForCharger = true,
                            stageLabel = "Waiting for charger",
                        )
                    }
                } else {
                    updateStatus(t.recordingId) {
                        JobStatus(queued = true, stageLabel = "Queued")
                    }
                }
            }
            if (saved.isNotEmpty()) {
                Log.i(TAG, "restored ${saved.size} pending task(s) from DB")
                tryStartNext()
            }
        }
    }

    fun statusOf(recordingId: Long): JobStatus =
        _statuses.value[recordingId] ?: JobStatus()

    /** Convenience: is anything at all running right now? */
    fun anyRunning(): Boolean = runningId != null

    /**
     * Schedule a transcription for [recordingId].
     *
     * Behaviour matrix:
     *   - already running this id: no-op
     *   - this id already pending in DB: replace its params with the new ones
     *     (idempotent, easier to reason about than "first call wins")
     *   - runOnCharger=true and !isCharging: persist as charger-parked
     *   - else if nothing else is running: start immediately
     *   - else: persist as plain FIFO queued; drains when the current job ends
     */
    fun start(
        recordingId: Long,
        params: Params,
        runOnCharger: Boolean = false,
    ) {
        if (runningId == recordingId) return
        // Persist first so a crash between here and startInternal doesn't
        // drop the task. The FIFO order is determined by queuedAtMillis at
        // persist time — running the task removes it from the table.
        val waitCharger = runOnCharger && !isCharging()
        appScope.launch {
            runCatching {
                pendingTasks.upsert(params.toEntity(recordingId, waitCharger))
            }.onFailure { Log.e(TAG, "failed to persist pending task", it) }

            if (waitCharger) {
                updateStatus(recordingId) {
                    JobStatus(
                        waitingForCharger = true,
                        stageLabel = "Waiting for charger",
                    )
                }
                Log.i(TAG, "parked recording=$recordingId until ACTION_POWER_CONNECTED")
                return@launch
            }

            if (runningId != null) {
                // Queue behind the running job. We don't reject — the whole
                // point of FIFO is that the user can bulk-enqueue without
                // babysitting the queue.
                updateStatus(recordingId) {
                    JobStatus(queued = true, stageLabel = "Queued")
                }
                Log.i(TAG, "queued recording=$recordingId behind running=$runningId")
                return@launch
            }

            // Idle — start now. Pull the persisted entity so any concurrent
            // start() races can't have us start with a different params copy.
            val task = pendingTasks.get(recordingId) ?: return@launch
            pendingTasks.delete(recordingId)
            startInternal(recordingId, task.toParams())
        }
    }

    private fun startInternal(recordingId: Long, params: Params) {
        runningId = recordingId
        runningParams = params
        updateStatus(recordingId) {
            JobStatus(running = true, stageLabel = "Starting", progress = 0f)
        }
        currentJob = appScope.launch {
            try {
                TranscriptionService.start(context)
                val recording = repository.get(recordingId) ?: run {
                    updateStatus(recordingId) { it.copy(running = false, error = "Recording not found") }
                    return@launch
                }
                runner.run(
                    recordingId = recordingId,
                    audioPath = recording.audioPath,
                    backend = params.backend,
                    languages = params.languages,
                    translateTo = params.translateTo,
                    diarize = params.diarize,
                    expectedSpeakers = params.expectedSpeakers,
                    hybridDiarize = params.hybridDiarize,
                    superMode = params.superMode,
                    superPairA = params.superPairA,
                    superPairB = params.superPairB,
                    maxQuality = params.maxQuality,
                ).collect { ev ->
                    val curr = _statuses.value[recordingId] ?: JobStatus(running = true)
                    val next = when (ev) {
                        is AsrEvent.Stage ->
                            curr.copy(running = true, stageLabel = ev.label, progress = ev.fraction, error = null)
                        is AsrEvent.Done ->
                            curr.copy(stageLabel = "Done", progress = 1f)
                        is AsrEvent.Failed ->
                            curr.copy(error = ev.reason)
                    }
                    updateStatus(recordingId) { next }
                }
            } catch (t: CancellationException) {
                // User pressed Stop. Don't paint an error — paint "Cancelled".
                updateStatus(recordingId) {
                    it.copy(
                        running = false,
                        stopping = false,
                        stageLabel = "Cancelled",
                        progress = 0f,
                        error = null,
                    )
                }
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "transcription failed for $recordingId", t)
                updateStatus(recordingId) {
                    it.copy(running = false, error = t.message ?: "Transcription failed")
                }
            } finally {
                if (runningId == recordingId) {
                    runningId = null
                    runningParams = null
                    val curr = _statuses.value[recordingId]
                    if (curr != null && curr.running) {
                        updateStatus(recordingId) { it.copy(running = false) }
                    }
                }
                // A checkpointRunning() call (FGS timeout) re-inserts this
                // job's row so a process kill can resume it. If we got
                // here, the job ended in-process (done, failed, or
                // cancelled by the user) — drop the row, otherwise the
                // tryStartNext() below would immediately re-run the same
                // recording and overwrite the transcript it just wrote.
                // NonCancellable: this finally also runs on user Stop, and
                // a cancelled coroutine can't otherwise suspend into Room.
                if (checkpointedId == recordingId) {
                    checkpointedId = null
                    withContext(NonCancellable) {
                        runCatching { pendingTasks.delete(recordingId) }
                            .onFailure { Log.e(TAG, "failed to clear checkpoint row for $recordingId", it) }
                    }
                }
                TranscriptionService.stop(context)
                // Drain the next FIFO task (non-charger-parked). Runs on
                // appScope, not this child coroutine, so a cancellation of
                // the just-finished job doesn't propagate into the next one.
                appScope.launch { tryStartNext() }
            }
        }
    }

    /**
     * Try to start the next eligible pending task. "Eligible" =
     *   - we have nothing running
     *   - either the task isn't charger-parked, or the device is on AC
     * Picks the oldest queued task (FIFO by queuedAtMillis).
     */
    private suspend fun tryStartNext() {
        if (runningId != null) return
        val charging = isCharging()
        val pool = runCatching { pendingTasks.listAll() }.getOrElse {
            Log.e(TAG, "tryStartNext: list failed", it); return
        }
        // Walk in FIFO order, skipping orphans (pending task with no
        // matching recording — possible if the cascade-delete didn't fire,
        // or if the user deleted via a path that doesn't go through Room).
        // Without this guard we'd silently fail-start on every drain and
        // never reach the next valid task.
        for (pick in pool) {
            if (pick.waitForCharger && !charging) continue
            val rec = runCatching { repository.get(pick.recordingId) }.getOrNull()
            if (rec == null) {
                Log.w(TAG, "tryStartNext: dropping orphan pending task for " +
                    "missing recording=${pick.recordingId}")
                pendingTasks.delete(pick.recordingId)
                updateStatus(pick.recordingId) {
                    it.copy(running = false, waitingForCharger = false, queued = false)
                }
                continue
            }
            pendingTasks.delete(pick.recordingId)
            Log.i(TAG, "draining next: recording=${pick.recordingId} " +
                "(waitedForCharger=${pick.waitForCharger})")
            startInternal(pick.recordingId, pick.toParams())
            return
        }
    }

    /**
     * Cancel a running or pending task for [recordingId]. Running tasks
     * are cancelled cooperatively (the catch block on
     * CancellationException paints "Cancelled" into the status).
     * Pending tasks are dropped from the DB immediately.
     */
    fun cancel(recordingId: Long) {
        if (runningId == recordingId) {
            // Paint "Stopping…" immediately — cancellation is cooperative
            // and the in-flight step (a whisper.cpp chunk call has no
            // suspension points at all) may take a while to actually
            // unwind. Without this the UI keeps showing the last
            // stageLabel/progress, unchanged, and looks stuck rather than
            // working on it.
            updateStatus(recordingId) { it.copy(stopping = true) }
            currentJob?.cancel()
            // Job's finally block clears running + status + drains next.
            return
        }
        appScope.launch {
            val existed = pendingTasks.get(recordingId) != null
            if (existed) {
                pendingTasks.delete(recordingId)
                updateStatus(recordingId) {
                    JobStatus(stageLabel = "Cancelled")
                }
            }
        }
    }

    /**
     * Called from [TranscriptionService.onTimeout] when the OS is about to
     * force-stop the foreground service (only reachable on the
     * mediaProcessing/dataSync fallback types — specialUse has no enforced
     * timeout). Starting a job deletes its `pending_tasks` row (see
     * [start]), so without this the in-flight task would be silently lost
     * instead of resuming on next launch. Runs blocking: onTimeout only
     * grants a short grace window before the process is killed outright,
     * so a fire-and-forget coroutine could easily lose the race.
     */
    fun checkpointRunning() {
        val id = runningId ?: return
        val params = runningParams ?: return
        runBlocking {
            runCatching {
                pendingTasks.upsert(params.toEntity(id, waitForCharger = false))
                checkpointedId = id
            }.onFailure { Log.e(TAG, "checkpoint failed for $id", it) }
        }
        Log.w(TAG, "checkpointed running recording=$id ahead of FGS timeout kill")
    }

    /** Called by PowerConnectedReceiver when AC plugs in. */
    fun onPowerConnected() {
        appScope.launch { tryStartNext() }
    }

    /** Clear the displayed error so the UI's error banner can be dismissed. */
    fun dismissError(recordingId: Long) {
        val curr = _statuses.value[recordingId] ?: return
        if (curr.error == null) return
        updateStatus(recordingId) { it.copy(error = null) }
    }

    private fun updateStatus(recordingId: Long, mutate: (JobStatus) -> JobStatus) {
        val curr = _statuses.value[recordingId] ?: JobStatus()
        _statuses.value = _statuses.value + (recordingId to mutate(curr))
    }

    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun Params.toEntity(recordingId: Long, waitForCharger: Boolean) = PendingTask(
        recordingId = recordingId,
        backend = backend.name,
        languages = languages.joinToString(","),
        translateTo = translateTo,
        diarize = diarize,
        expectedSpeakers = expectedSpeakers,
        hybridDiarize = hybridDiarize,
        waitForCharger = waitForCharger,
        queuedAtMillis = System.currentTimeMillis(),
        superMode = superMode,
        superPairA = superPairA?.name,
        superPairB = superPairB?.name,
        maxQuality = maxQuality,
    )

    private fun PendingTask.toParams(): Params = Params(
        backend = runCatching { AsrBackendKind.valueOf(backend) }
            .getOrDefault(AsrBackendKind.Gemma4),
        languages = if (languages.isEmpty()) emptyList() else languages.split(","),
        translateTo = translateTo,
        diarize = diarize,
        expectedSpeakers = expectedSpeakers,
        hybridDiarize = hybridDiarize,
        superMode = superMode,
        superPairA = superPairA?.let { n -> runCatching { AsrBackendKind.valueOf(n) }.getOrNull() },
        superPairB = superPairB?.let { n -> runCatching { AsrBackendKind.valueOf(n) }.getOrNull() },
        maxQuality = maxQuality,
    )

    companion object {
        private const val TAG = "TxJobManager"
    }
}
