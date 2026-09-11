package nl.ihnatov.transcriber.asr

import kotlin.math.sqrt

/**
 * Global speaker clustering over per-window canonical embeddings.
 *
 * [DiarizationRunner.runChunked] used to stitch windows by temporal overlap
 * alone — cheap, but a speaker silent through a whole overlap zone gets a
 * duplicate id, and there was no way to reconcile identity across
 * non-adjacent windows at all. This does the "real" thing instead: one
 * embedding per (window, local speaker) — see [DiarizationRunner] — pooled
 * and clustered ONCE across the whole file. The clustering problem here is
 * small (a few dozen embeddings for an hour-long, several-speaker
 * recording) even though the audio itself is long, so a plain O(n²)
 * agglomerative pass is fast enough with no native/JNI dependency — which
 * also makes it trivially unit-testable on synthetic vectors.
 *
 * Mirrors sherpa-onnx's own `FastClusteringConfig` contract: numClusters>0
 * forces exactly that many clusters; numClusters<=0 auto-stops merging once
 * the closest remaining pair is farther apart than [distanceThreshold]
 * (which is `1 - cosineSimilarityThreshold`).
 */
object SpeakerClustering {

    /** 1 - cosine similarity. 0 = identical direction, 2 = opposite. */
    fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "embedding dimension mismatch: ${a.size} vs ${b.size}" }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 1e-12f) return 1f
        val cosine = (dot / denom).coerceIn(-1f, 1f)
        return 1f - cosine
    }

    /**
     * Average-linkage agglomerative clustering. Returns a label (0-based
     * cluster index, in no particular order — callers renumber downstream)
     * per input item, same order as [embeddings].
     *
     * @param numClusters >0 forces exactly this many clusters (merges the
     *   closest pairs until reaching it, ignoring [distanceThreshold]); <=0
     *   auto-stops merging as soon as the closest remaining pair exceeds
     *   [distanceThreshold].
     */
    fun cluster(
        embeddings: List<FloatArray>,
        numClusters: Int,
        distanceThreshold: Float,
    ): IntArray {
        val n = embeddings.size
        if (n == 0) return IntArray(0)
        if (n == 1) return intArrayOf(0)

        val members = MutableList(n) { mutableListOf(it) }
        val centroids = MutableList(n) { embeddings[it].copyOf() }

        fun recomputeCentroid(idxs: List<Int>): FloatArray {
            val dim = embeddings[0].size
            val sum = FloatArray(dim)
            for (m in idxs) {
                val e = embeddings[m]
                for (d in 0 until dim) sum[d] += e[d]
            }
            for (d in 0 until dim) sum[d] /= idxs.size
            return sum
        }

        val targetK = if (numClusters > 0) numClusters.coerceAtMost(n) else 1
        while (members.size > targetK) {
            var bestI = -1
            var bestJ = -1
            var bestDist = Float.MAX_VALUE
            for (i in centroids.indices) {
                for (j in i + 1 until centroids.size) {
                    val d = cosineDistance(centroids[i], centroids[j])
                    if (d < bestDist) {
                        bestDist = d
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (bestI < 0) break
            if (numClusters <= 0 && bestDist > distanceThreshold) break
            members[bestI].addAll(members[bestJ])
            members.removeAt(bestJ)
            centroids[bestI] = recomputeCentroid(members[bestI])
            centroids.removeAt(bestJ)
        }

        val labels = IntArray(n)
        for ((clusterIdx, idxs) in members.withIndex()) {
            for (m in idxs) labels[m] = clusterIdx
        }
        return labels
    }
}
