package nl.ihnatov.transcriber.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import nl.ihnatov.transcriber.asr.TranscriptExporter

/**
 * Copies recordings (audio + a plain-text transcript) to a folder the user
 * picks via Storage Access Framework — outside the app's private storage,
 * so they survive an app uninstall/reinstall.
 *
 * Why this exists: recordings live ONLY in [Context.getFilesDir]/recordings,
 * which an uninstall wipes completely, and which `res/xml/backup_rules.xml`
 * / `data_extraction_rules.xml` explicitly exclude from Android's own
 * Auto Backup (deliberately, to avoid silently blowing the user's cloud
 * quota on audio) — so there has never been any safety net for this data
 * at all. This is that safety net, under the user's own control.
 */
class BackupManager(
    private val context: Context,
    private val repository: RecordingRepository,
) {
    data class ExportResult(val exported: Int, val failed: Int, val skipped: Int) {
        val total: Int get() = exported + failed + skipped
    }

    private enum class Outcome { EXPORTED, SKIPPED, FAILED }

    /**
     * Copies every recording into [destTreeUri]. A recording whose audio
     * file already exists there at the same size is treated as already
     * backed up and skipped — re-running this after a partial prior run
     * (or on a schedule) doesn't redo work or duplicate files.
     */
    suspend fun exportAll(destTreeUri: Uri): Result<ExportResult> = withContext(Dispatchers.IO) {
        // Plain try/catch, not runCatching — runCatching's catch(Throwable)
        // would swallow a CancellationException (e.g. the app process
        // winding down mid-backup) and turn it into an ordinary
        // Result.failure, leaving this coroutine looking like it completed
        // normally instead of actually honoring the cancellation.
        try {
            val root = openRoot(destTreeUri)
            val recordings = repository.observeLibrary().first()
            var exported = 0
            var failed = 0
            var skipped = 0
            for (rec in recordings) {
                when (exportOneInternal(root, rec)) {
                    Outcome.EXPORTED -> exported++
                    Outcome.SKIPPED -> skipped++
                    Outcome.FAILED -> failed++
                }
            }
            Result.success(ExportResult(exported, failed, skipped))
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Exports a single recording — used right after a new recording
     * finishes when auto-backup is on, so newly recorded audio doesn't sit
     * unprotected until the next manual "back up now".
     */
    suspend fun exportOne(destTreeUri: Uri, recordingId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val root = openRoot(destTreeUri)
            val rec = repository.get(recordingId) ?: error("Recording not found")
            // exportOneInternal reports FAILED by swallowing its own
            // exception internally (see below) rather than throwing — it
            // has to, since exportAll needs to keep going past one bad
            // recording. That means the failure has to be re-raised here
            // explicitly, or this always-Unit body would report
            // Result.success even when the copy never happened.
            if (exportOneInternal(root, rec) == Outcome.FAILED) {
                error("Failed to back up recording ${rec.id}")
            }
            Result.success(Unit)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun openRoot(destTreeUri: Uri): DocumentFile {
        val root = DocumentFile.fromTreeUri(context, destTreeUri)
            ?: error("Can't open the backup folder — pick it again in Settings.")
        if (!root.isDirectory || !root.canWrite()) {
            error("No write access to the backup folder — pick it again in Settings.")
        }
        return root
    }

    private suspend fun exportOneInternal(root: DocumentFile, rec: Recording): Outcome {
        return try {
            val audioFile = File(rec.audioPath)
            if (!audioFile.exists()) return Outcome.FAILED

            val existingAudio = root.findFile(audioFile.name)
            val alreadyBackedUp = existingAudio != null && existingAudio.length() == audioFile.length()
            if (!alreadyBackedUp) {
                existingAudio?.delete()
                val newAudio = root.createFile("audio/wav", audioFile.name) ?: return Outcome.FAILED
                val out = context.contentResolver.openOutputStream(newAudio.uri) ?: return Outcome.FAILED
                out.use { sink -> audioFile.inputStream().use { it.copyTo(sink) } }
            }

            // Transcript is cheap to regenerate, so it's always rewritten
            // (unlike the audio) — it may have changed since the last
            // backup (re-run, edits, a later Run) even when the audio
            // hasn't.
            val segments = repository.observeSegments(rec.id).first()
            val transcriptName = "${audioFile.nameWithoutExtension}.txt"
            root.findFile(transcriptName)?.delete()
            val newTranscript = root.createFile("text/plain", transcriptName) ?: return Outcome.FAILED
            val text = TranscriptExporter.toTxt(rec, segments)
            val out = context.contentResolver.openOutputStream(newTranscript.uri) ?: return Outcome.FAILED
            out.use { it.write(text.toByteArray()) }

            if (alreadyBackedUp) Outcome.SKIPPED else Outcome.EXPORTED
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Outcome.FAILED
        }
    }
}
