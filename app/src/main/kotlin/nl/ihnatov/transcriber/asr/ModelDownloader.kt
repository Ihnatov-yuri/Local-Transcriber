package nl.ihnatov.transcriber.asr

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Streaming downloader for catalog models. Emits [Progress] events the UI can
 * map to a progress bar. Cancellation-safe — the destination is written to
 * `<name>.partial` and renamed only on full success, so a cancelled or failed
 * download never leaves a half-baked model file that would later be picked up
 * by [AsrFactory.resolveModel].
 *
 * We use plain `HttpsURLConnection` rather than OkHttp to keep the dep footprint
 * small. The HuggingFace CDN follows redirects (huggingface.co -> cdn-lfs.huggingface.co)
 * so we handle one hop manually.
 */
class ModelDownloader(private val factory: AsrFactory) {

    sealed interface Progress {
        data object Starting : Progress
        data class Streaming(val bytesRead: Long, val totalBytes: Long?) : Progress
        /** Archive entries only: the .tar.bz2 finished downloading and is now being decompressed — no byte-level progress available mid-decode. */
        data object Extracting : Progress
        data class Done(val file: File) : Progress
        data class Failed(val reason: String) : Progress
    }

    /**
     * Stream the catalog entry's file into the models directory.
     *
     * Caller chooses lifecycle: pass a `viewModelScope.launch { downloader.download(e).collect { ... } }`.
     */
    fun download(entry: CatalogEntry): Flow<Progress> = flow {
        emit(Progress.Starting)
        if (entry.isArchive) {
            downloadArchiveAndExtract(entry)
            return@flow
        }
        val dst = File(factory.modelsDir(), entry.filename)
        if (dst.exists() && dst.length() == entry.sizeMb.toLong() * 1024L * 1024L) {
            // Tolerant size check — HF reports MB rounded so we accept exact-ish matches.
            emit(Progress.Done(dst))
            return@flow
        }
        val partial = File(factory.modelsDir(), "${entry.filename}.partial")
        partial.delete()

        try {
            streamToFile(entry.url, partial)

            if (!partial.renameTo(dst)) {
                partial.delete()
                emit(Progress.Failed("Could not move ${partial.name} -> ${dst.name}"))
                return@flow
            }
            emit(Progress.Done(dst))
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            partial.delete()
            throw cancel
        } catch (t: Throwable) {
            partial.delete()
            emit(Progress.Failed(t.message ?: t.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Download [entry]'s `.tar.bz2` and extract it into
     * `modelsDir()/<entry.filename>/`. Extraction targets a sibling
     * `<filename>.extracting/` directory first and only renames it over
     * the final directory name on full success — same atomic-rename
     * discipline as the flat-file path, extended to a directory: a
     * cancelled or failed extraction never leaves a half-populated
     * directory that [AsrFactory.isCompleteModelDir] could mistake for
     * ready. Archive entries commonly wrap their files in a version-
     * named subdirectory (e.g. `sherpa-onnx-.../encoder.onnx`); we flatten
     * that away and keep only each entry's base filename.
     */
    private suspend fun FlowCollector<Progress>.downloadArchiveAndExtract(entry: CatalogEntry) {
        val finalDir = File(factory.modelsDir(), entry.filename)
        if (finalDir.isDirectory && File(finalDir, COMPLETE_MARKER).exists()) {
            emit(Progress.Done(finalDir))
            return
        }
        val archivePartial = File(factory.modelsDir(), "${entry.filename}.tar.bz2.partial")
        val extractingDir = File(factory.modelsDir(), "${entry.filename}.extracting")
        archivePartial.delete()
        try {
            streamToFile(entry.url, archivePartial)
            emit(Progress.Extracting)

            extractingDir.deleteRecursively()
            extractingDir.mkdirs()
            extractTarBz2(archivePartial, extractingDir)
            archivePartial.delete()
            File(extractingDir, COMPLETE_MARKER).createNewFile()

            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!extractingDir.renameTo(finalDir)) {
                throw IOException("Could not move ${extractingDir.name} -> ${finalDir.name}")
            }
            emit(Progress.Done(finalDir))
        } catch (cancel: CancellationException) {
            archivePartial.delete()
            extractingDir.deleteRecursively()
            throw cancel
        } catch (t: Throwable) {
            archivePartial.delete()
            extractingDir.deleteRecursively()
            emit(Progress.Failed(t.message ?: t.javaClass.simpleName))
        }
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        BZip2CompressorInputStream(archive.inputStream().buffered()).use { bz2 ->
            TarArchiveInputStream(bz2).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // Flatten: keep only the leaf filename, dropping any
                    // wrapping directory the archive bundles files under.
                    val name = File(entry.name).name
                    if (name.isBlank()) continue
                    // sherpa-onnx's asr-models archives also carry
                    // test_wavs/, README.md, LICENSE — only the actual
                    // model + tokenizer files matter to us.
                    if (name != "tokens.txt" && !name.endsWith(".onnx")) continue
                    File(destDir, name).outputStream().use { out -> tar.copyTo(out) }
                }
            }
        }
    }

    /** Streams [url] into [dst], emitting throttled [Progress.Streaming] events. Caller owns cleanup on failure. */
    private suspend fun FlowCollector<Progress>.streamToFile(url: String, dst: File) {
        coroutineScope {
            ensureActive()
            val conn = openWithFollowRedirects(url, maxHops = 5)
            val total = conn.contentLengthLong.takeIf { it > 0 }
            conn.inputStream.use { input ->
                FileOutputStream(dst).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var totalRead = 0L
                    var lastEmittedAt = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        totalRead += n
                        // Throttle: emit at most ~10 progress events per second
                        val now = System.currentTimeMillis()
                        if (now - lastEmittedAt >= 100) {
                            emit(Progress.Streaming(totalRead, total))
                            lastEmittedAt = now
                        }
                    }
                    output.flush()
                }
            }
            conn.disconnect()
        }
    }

    private suspend fun openWithFollowRedirects(initialUrl: String, maxHops: Int): HttpURLConnection =
        withContext(Dispatchers.IO) {
            var url = URL(initialUrl)
            var hops = 0
            while (true) {
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false   // we follow manually so SSL cert chain validates each hop
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "Transcriber-Android/0.1.0")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                val code = conn.responseCode
                if (code in 300..399 && hops < maxHops) {
                    val loc = conn.getHeaderField("Location") ?: error("Redirect without Location header")
                    conn.disconnect()
                    url = URL(url, loc)
                    hops++
                    continue
                }
                if (code !in 200..299) {
                    val msg = "HTTP $code from $url"
                    conn.disconnect()
                    error(msg)
                }
                if (conn is HttpsURLConnection) {
                    // No-op: trust the system trust store. We don't pin anything here.
                }
                return@withContext conn
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private companion object {
        /** Marks an extracted archive directory as fully written — see [downloadArchiveAndExtract]. */
        const val COMPLETE_MARKER = ".complete"
    }
}

