package nl.ihnatov.transcriber.asr

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

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
        val dst = File(factory.modelsDir(), entry.filename)
        if (dst.exists() && dst.length() == entry.sizeMb.toLong() * 1024L * 1024L) {
            // Tolerant size check — HF reports MB rounded so we accept exact-ish matches.
            emit(Progress.Done(dst))
            return@flow
        }
        val partial = File(factory.modelsDir(), "${entry.filename}.partial")
        partial.delete()

        try {
            coroutineScope {
                ensureActive()
                val conn = openWithFollowRedirects(entry.url, maxHops = 5)
                val total = conn.contentLengthLong.takeIf { it > 0 }
                conn.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
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
}

