package rocks.pkb.plektra.core.suggestions

import java.util.Locale

class FakePhraseUsageRepository : PhraseUsageRepository {

    data class LearnedPhrase(
        val locale: Locale,
        val words: List<String>
    )

    val learnedPhrases = mutableListOf<LearnedPhrase>()

    override fun learn(
        locale: Locale,
        words: List<String>,
        nowMs: Long
    ) {
        learnedPhrases += LearnedPhrase(
            locale = locale,
            words = words.toList()
        )
    }

    override fun getUsage(
        locale: Locale,
        words: List<String>
    ): PhraseUsageStore.Usage? = null

    var predictions: List<PhraseUsageStore.Prediction> = emptyList()

    val predictionRequests =
        mutableListOf<Pair<Locale, List<String>>>()

    override fun getPromotionCandidates(
        locale: Locale,
        minimumCount: Int,
        limit: Int
    ): List<PhraseUsageStore.Usage> {
        if (minimumCount <= 0 || limit <= 0) return emptyList()

        return learnedPhrases
            .asSequence()
            .filter { it.locale.language == locale.language }
            .groupBy { learned ->
                learned.words.joinToString("\u001F") { word ->
                    WordNormalization.normalizeForSuggestion(word, locale)
                }
            }
            .values
            .mapNotNull { occurrences ->
                if (occurrences.size < minimumCount) {
                    null
                } else {
                    PhraseUsageStore.Usage(
                        words = occurrences.last().words,
                        count = occurrences.size,
                        lastUsed = 0L
                    )
                }
            }
            .sortedByDescending { it.count }
            .take(limit)
            .toList()
    }

    override fun predictNext(
        locale: Locale,
        contextWords: List<String>,
        limit: Int
    ): List<PhraseUsageStore.Prediction> {
        predictionRequests += locale to contextWords.toList()

        return predictions.take(limit)
    }

    override fun prune(
        nowMs: Long,
        staleAfterMs: Long,
        weakCountThreshold: Int,
        maxEntriesPerLocale: Int
    ): Int = 0

    override fun clearAll() {
        learnedPhrases.clear()
    }
}