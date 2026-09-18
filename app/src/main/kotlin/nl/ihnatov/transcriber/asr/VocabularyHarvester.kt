package nl.ihnatov.transcriber.asr

import kotlinx.serialization.Serializable

/**
 * Names learned from the user's own transcripts — verbatim port of the Mac
 * app's `Dictation/VocabularyHarvester.swift`.
 *
 * The library already holds hours of the user's meetings — the people,
 * companies, products and places they actually say. A capitalized word
 * that is not a sentence start, appears in more than one recording, and
 * isn't an ordinary word is almost always a name worth spelling right.
 * Harvested terms are offered in Settings ("Learned") for one-tap
 * promotion into the permanent vocabulary.
 */
object VocabularyHarvester {

    @Serializable
    data class Term(
        /** Normalized key (letters/digits, lowercased). */
        val key: String,
        /** The spelling seen most often. */
        val spelling: String,
        /** Distinct recordings the term appeared in. */
        val recordings: Int,
        val occurrences: Int,
    )

    data class HarvestItem(val recordingId: Long, val text: String)

    /** Normalized key: lowercase, letters/digits only. */
    fun key(s: String): String = s.lowercase().filter { it.isLetter() || it.isDigit() }

    /**
     * One text's worth of tokens → candidate names (single words and runs
     * of consecutive capitalized words such as "Blits Insurance"), with
     * whether each was the first word of its sentence (where a capital is
     * just grammar). Sentence-initial mentions still count towards
     * frequency once a term has mid-sentence evidence.
     */
    fun candidatesDetailed(text: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        for (sentence in text.split('.', '!', '?', '\n')) {
            if (sentence.isEmpty()) continue
            val words = sentence.split(' ')
                .map(::trimPunctuation)
                .filter { it.isNotEmpty() }
            val run = mutableListOf<String>()
            var runInitial = false
            fun flush() {
                if (run.isNotEmpty()) out += run.joinToString(" ") to (runInitial && run.size == 1)
                run.clear()
                runInitial = false
            }
            for ((i, w) in words.withIndex()) {
                if (isAcronym(w) || looksLikeName(w)) {
                    // "Ask KimKim", "Then Kaiko": a capitalized sentence
                    // opener must not glue itself onto the name that follows.
                    if (i == 0 && !isAcronym(w) && openers.contains(w.lowercase())) {
                        out += w to true
                        continue
                    }
                    if (run.isEmpty()) runInitial = (i == 0) && !isAcronym(w)
                    run += w
                } else {
                    flush()
                }
            }
            flush()
        }
        return out
    }

    /**
     * Names that are NOT merely sentence-initial capitals — what a single
     * passage can vouch for on its own (used for one-tap suggestions).
     */
    fun candidates(text: String): List<String> =
        candidatesDetailed(text).filter { !it.second }.map { it.first }

    /**
     * Harvest from (recordingId, text) pairs. [minRecordings] = how many
     * distinct recordings a term must appear in.
     */
    fun harvest(
        items: List<HarvestItem>,
        existingVocabulary: List<String>,
        minRecordings: Int = 2,
        limit: Int = 200,
    ): List<Term> {
        val existing = existingVocabulary.map(::key).toSet()
        val recordingsByKey = HashMap<String, MutableSet<Long>>()
        val spellings = HashMap<String, HashMap<String, Int>>()
        val lowercaseSeen = HashMap<String, Int>()
        val occurrences = HashMap<String, Int>()
        val midSentence = HashMap<String, Int>()

        for (item in items) {
            // Lowercase frequency of every word, to reject ordinary words
            // that merely got capitalized somewhere ("Meeting", "Project").
            for (w in item.text.split(WORD_SPLIT_REGEX)) {
                if (w.isEmpty()) continue
                if (w.first().isLowerCase()) {
                    val k = key(w)
                    lowercaseSeen[k] = (lowercaseSeen[k] ?: 0) + 1
                }
            }
            for ((cand, initial) in candidatesDetailed(item.text)) {
                val k = key(cand)
                if (k.length < 3 || k in stop || k in existing) continue
                // Contractions ("That's", "I'm"), runs with a filler or a
                // repeated word ("Uh I'm", "Kim KimKim"), and runs longer
                // than three words are not names.
                if (cand.contains('\'') || cand.contains('’')) continue
                val parts = cand.split(' ').map(::key)
                if (parts.size > 3 || parts.any { it in stop }) continue
                if (parts.size > 1 &&
                    parts.zipWithNext().any { (a, b) -> a == b || b.startsWith(a) || a.startsWith(b) }
                ) continue
                if (!initial) midSentence[k] = (midSentence[k] ?: 0) + 1
                recordingsByKey.getOrPut(k) { mutableSetOf() }.add(item.recordingId)
                val spellingCounts = spellings.getOrPut(k) { HashMap() }
                spellingCounts[cand] = (spellingCounts[cand] ?: 0) + 1
                occurrences[k] = (occurrences[k] ?: 0) + 1
            }
        }

        val terms = mutableListOf<Term>()
        for ((k, recs) in recordingsByKey) {
            if (recs.size < minRecordings) continue
            // Mostly seen at sentence starts → grammar, not a name.
            val occ = occurrences[k] ?: 0
            val mid = midSentence[k] ?: 0
            if (mid < maxOf(1, occ / 5)) continue
            // Seen lowercase at least as often as capitalized → ordinary word.
            val low = lowercaseSeen[k] ?: 0
            if (low >= occ) continue
            // Multi-word runs: every word must not be a common lowercase word.
            val best = spellings[k]?.maxByOrNull { it.value }?.key ?: continue
            val parts = best.split(' ').map(::key)
            if (parts.size > 1 && parts.all { (lowercaseSeen[it] ?: 0) > 3 }) continue
            terms += Term(key = k, spelling = best, recordings = recs.size, occurrences = occ)
        }
        return terms
            .sortedWith(
                compareByDescending<Term> { it.recordings }
                    .thenByDescending { it.occurrences }
                    .thenByDescending { it.spelling }
            )
            .take(limit)
    }

    /**
     * One-tap suggestions from a SINGLE transcript — what the Detail screen
     * offers right after a run. Mirrors the Mac's
     * `DictationController.suggestNames(from:)` (candidates not already
     * known / learned / dismissed, de-duplicated by key), plus the same
     * sanity filters [harvest] applies, since a whole recording is far
     * noisier than one dictated utterance. Most-mentioned first; returns
     * [Term]s (recordings = 1) so callers can hand them straight to
     * [addLearnedTerm].
     */
    fun suggest(
        text: String,
        knownTerms: Collection<String>,
        dismissedKeys: Set<String>,
        limit: Int = 6,
    ): List<Term> {
        val known = knownTerms.mapTo(HashSet(), ::key)
        val lowercaseSeen = HashMap<String, Int>()
        for (w in text.split(WORD_SPLIT_REGEX)) {
            if (w.isNotEmpty() && w.first().isLowerCase()) {
                val k = key(w)
                lowercaseSeen[k] = (lowercaseSeen[k] ?: 0) + 1
            }
        }
        val counts = LinkedHashMap<String, Int>()
        val spellings = HashMap<String, HashMap<String, Int>>()
        for (cand in candidates(text)) {
            val k = key(cand)
            if (k.length < 3 || k in stop || k in known || k in dismissedKeys) continue
            if (cand.contains('\'') || cand.contains('’')) continue
            val parts = cand.split(' ').map(::key)
            if (parts.size > 3 || parts.any { it in stop }) continue
            if (parts.size > 1 &&
                parts.zipWithNext().any { (a, b) -> a == b || b.startsWith(a) || a.startsWith(b) }
            ) continue
            counts[k] = (counts[k] ?: 0) + 1
            val sc = spellings.getOrPut(k) { HashMap() }
            sc[cand] = (sc[cand] ?: 0) + 1
        }
        return counts.entries
            // Seen lowercase at least as often as capitalized → ordinary word.
            .filter { (k, occ) -> (lowercaseSeen[k] ?: 0) < occ }
            .sortedByDescending { it.value } // stable: ties keep first-mention order
            .take(limit)
            .mapNotNull { (k, occ) ->
                val best = spellings[k]?.maxByOrNull { it.value }?.key ?: return@mapNotNull null
                Term(key = k, spelling = best, recordings = 1, occurrences = occ)
            }
    }

    private fun looksLikeName(w: String): Boolean {
        val first = w.firstOrNull() ?: return false
        if (!first.isUpperCase() || w.length < 2) return false
        return w.drop(1).any { it.isLowerCase() || it.isDigit() }
    }

    private fun isAcronym(w: String): Boolean =
        w.length in 3..6 && w.any { it.isLetter() } && w.all { it.isUpperCase() || it.isDigit() }

    private fun trimPunctuation(w: String): String = w.trim(::isPunctuation)

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        -> true
        else -> false
    }

    private val WORD_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Ordinary words that happen to be capitalized mid-sentence in ASR
     * output (days, months, "I", pronouns after a dropped period…). The
     * lowercase-frequency rule below catches the rest.
     */
    private val stop: Set<String> = setOf(
        "i", "im", "ive", "id", "ill", "ok", "okay", "yes", "no", "yeah", "the", "a", "an", "and",
        "but", "so", "or", "if", "then", "when", "what", "why", "how", "who", "where", "this", "that",
        "these", "those", "it", "its", "we", "you", "he", "she", "they", "them", "our", "your", "my",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december", "speaker", "hello", "hi", "hey", "thanks", "thank",
        "bye", "good", "morning", "afternoon", "evening", "today", "tomorrow", "yesterday",
        "english", "dutch", "ukrainian", "german", "french", "spanish", "europe", "european",
        "ja", "nee", "de", "het", "een", "en", "maar", "dus", "ik", "je", "we", "wij", "zij",
        "так", "ні", "і", "а", "але", "я", "ми", "ви", "вони", "це", "що", "як",
        // fillers / discourse words the recognizer capitalizes
        "uh", "um", "hmm", "mhm", "mmhmm", "oops", "however", "anyway", "right", "well",
        "тобто", "дякую", "добре", "навірно", "напевно", "угу", "ага", "гаразд", "ну",
        "привіт", "зрозуміло", "будь", "ласка", "давай", "давайте", "тепер",
        "oké", "hoor", "nou", "eigenlijk", "gewoon", "dankjewel", "bedankt", "prima",
    )

    /**
     * Ordinary words that open sentences with a capital and would otherwise
     * be glued onto a following name.
     */
    private val openers: Set<String> = setOf(
        "then", "ask", "so", "and", "but", "also", "now", "well", "okay", "ok", "please", "let",
        "maybe", "actually", "yes", "no", "just", "send", "call", "tell", "check", "make", "get",
        "put", "look", "see", "thanks", "thank", "hi", "hello", "dear", "hey", "great", "good",
        "sure", "right", "wait", "sorry", "today", "tomorrow", "yesterday", "after", "before",
        "during", "since", "with", "without", "for", "from", "to", "in", "on", "at", "by", "of",
        "the", "a", "an", "this", "that", "these", "those", "our", "your", "my", "his", "her",
        "their", "its", "we", "you", "they", "he", "she", "it", "i", "if", "when", "while",
        "because", "about", "over", "under", "into", "onto", "next", "last", "first", "second",
        "meanwhile", "however", "still", "yet", "even", "only", "later", "earlier", "here", "there",
        "dan", "dus", "maar", "en", "ook", "nu", "oké", "ja", "nee", "vraag", "bel", "stuur",
        "тоді", "також", "але", "і", "зараз", "так", "ні", "потім", "спитай", "надішли",
    )
}
