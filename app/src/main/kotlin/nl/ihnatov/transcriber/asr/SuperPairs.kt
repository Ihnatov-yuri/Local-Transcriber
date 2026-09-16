package nl.ihnatov.transcriber.asr

/**
 * Which two engines Super mode offers to pair, given what is installed.
 *
 * The RUN sheet used to hard-code two presets from the plan's engine
 * matrix (Parakeet + Whisper, Omnilingual + Gemma 4). On a phone with
 * Parakeet and Gemma 4 installed but neither Whisper nor Omnilingual,
 * every preset failed to load with "No model installed for …", so Super
 * mode could never be tried. Presets are now the pairs whose engines are
 * both installed, in plan-preference order, falling back to the full
 * list (so the sheet still shows something and the runner's error says
 * which model is missing) when no pair is complete.
 */
object SuperPairs {
    /** All meaningful pairings, best first. Nemotron is streaming-only and never a member. */
    val PREFERRED: List<Pair<AsrBackendKind, AsrBackendKind>> = listOf(
        AsrBackendKind.Parakeet to AsrBackendKind.WhisperCpp,
        AsrBackendKind.Omnilingual to AsrBackendKind.Gemma4,
        AsrBackendKind.Parakeet to AsrBackendKind.Gemma4,
        AsrBackendKind.Omnilingual to AsrBackendKind.WhisperCpp,
        AsrBackendKind.Parakeet to AsrBackendKind.Omnilingual,
        AsrBackendKind.WhisperCpp to AsrBackendKind.Gemma4,
    )

    fun presets(installed: Set<AsrBackendKind>): List<Pair<AsrBackendKind, AsrBackendKind>> {
        val ready = PREFERRED.filter { it.first in installed && it.second in installed }
        return ready.ifEmpty { PREFERRED }
    }

    fun isReady(pair: Pair<AsrBackendKind, AsrBackendKind>, installed: Set<AsrBackendKind>): Boolean =
        pair.first in installed && pair.second in installed
}
