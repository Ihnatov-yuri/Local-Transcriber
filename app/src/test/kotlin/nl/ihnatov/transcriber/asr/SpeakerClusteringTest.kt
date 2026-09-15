package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerClusteringTest {

    /** A unit vector along dimension [dim] out of [size], nudged slightly for realism. */
    private fun basisVector(size: Int, dim: Int, noise: Float = 0f): FloatArray {
        val v = FloatArray(size)
        v[dim] = 1f
        if (noise != 0f) v[(dim + 1) % size] = noise
        return v
    }

    @Test
    fun `cosineDistance is zero for identical vectors`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(0f, SpeakerClustering.cosineDistance(a, a), 1e-5f)
    }

    @Test
    fun `cosineDistance is one for orthogonal vectors`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(1f, SpeakerClustering.cosineDistance(a, b), 1e-5f)
    }

    @Test
    fun `cosineDistance is two for opposite vectors`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        assertEquals(2f, SpeakerClustering.cosineDistance(a, b), 1e-5f)
    }

    @Test
    fun `auto mode groups well-separated embeddings into their natural clusters`() {
        // Two speakers, three window-samples each — mirrors what runChunked
        // pools: one canonical embedding per (window, local speaker).
        val embeddings = listOf(
            basisVector(8, 0, 0.02f), basisVector(8, 0, -0.01f), basisVector(8, 0, 0.01f),
            basisVector(8, 4, 0.02f), basisVector(8, 4, -0.02f), basisVector(8, 4, 0.0f),
        )
        val labels = SpeakerClustering.cluster(embeddings, numClusters = -1, distanceThreshold = 0.3f)
        assertEquals(6, labels.size)
        assertEquals(labels[0], labels[1])
        assertEquals(labels[1], labels[2])
        assertEquals(labels[3], labels[4])
        assertEquals(labels[4], labels[5])
        assertTrue("the two speakers must land in different clusters", labels[0] != labels[3])
    }

    @Test
    fun `forced numClusters merges down to exactly that many`() {
        val embeddings = listOf(
            basisVector(8, 0), basisVector(8, 2), basisVector(8, 4), basisVector(8, 6),
        )
        val labels = SpeakerClustering.cluster(embeddings, numClusters = 2, distanceThreshold = 999f)
        assertEquals(2, labels.toSet().size)
    }

    @Test
    fun `single embedding returns a single cluster`() {
        val labels = SpeakerClustering.cluster(listOf(basisVector(4, 0)), numClusters = -1, distanceThreshold = 0.3f)
        assertEquals(intArrayOf(0).toList(), labels.toList())
    }

    @Test
    fun `empty input returns empty output`() {
        val labels = SpeakerClustering.cluster(emptyList(), numClusters = -1, distanceThreshold = 0.3f)
        assertEquals(0, labels.size)
    }

    @Test
    fun `tight auto threshold keeps distinct-but-close embeddings separate`() {
        // Two clusters close enough together that a loose threshold would
        // merge them, but not with a tight one.
        val a = floatArrayOf(1f, 0.1f)
        val b = floatArrayOf(1f, -0.1f)
        val labels = SpeakerClustering.cluster(listOf(a, b), numClusters = -1, distanceThreshold = 0.001f)
        assertTrue(labels[0] != labels[1])
    }
}
