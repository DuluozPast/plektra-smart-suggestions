package rocks.pkb.plektra.core.suggestions

import java.util.Locale

class MultiWordPromotionEngine(
    private val phraseUsageRepository: PhraseUsageRepository,
    private val nextWordPredictor: NextWordPredictor,
    private val multiWordStore: MultiWordStore,
    private val minimumPhraseCount: Int = DEFAULT_MINIMUM_PHRASE_COUNT,
    private val minimumTransitionCount: Int = DEFAULT_MINIMUM_TRANSITION_COUNT,
    private val minimumTransitionConfidence: Double =
        DEFAULT_MINIMUM_TRANSITION_CONFIDENCE
) {

    data class PromotionResult(
        val examined: Int,
        val promoted: Int
    )

    data class DemotionResult(
        val examined: Int,
        val demoted: Int
    )

    fun promoteEligible(
        locale: Locale,
        limit: Int = DEFAULT_CANDIDATE_LIMIT,
        nowMs: Long = System.currentTimeMillis()
    ): PromotionResult {
        if (limit <= 0) {
            return PromotionResult(
                examined = 0,
                promoted = 0
            )
        }

        val candidates = phraseUsageRepository.getPromotionCandidates(
            locale = locale,
            minimumCount = minimumPhraseCount,
            limit = limit
        )

        var promotedCount = 0

        candidates.forEach { candidate ->
            if (!hasStrongNGramEvidence(locale, candidate.words)) {
                return@forEach
            }

            val promoted = multiWordStore.promote(
                locale = locale,
                words = candidate.words,
                phraseCount = candidate.count,
                nowMs = nowMs
            )

            if (promoted != null) {
                promotedCount++
            }
        }

        return PromotionResult(
            examined = candidates.size,
            promoted = promotedCount
        )
    }

    fun promoteIfEligible(
        locale: Locale,
        words: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val usage = phraseUsageRepository.getUsage(
            locale = locale,
            words = words
        ) ?: return false

        if (usage.count < minimumPhraseCount) {
            return false
        }

        if (!hasStrongNGramEvidence(locale, usage.words)) {
            return false
        }

        return multiWordStore.promote(
            locale = locale,
            words = usage.words,
            phraseCount = usage.count,
            nowMs = nowMs
        ) != null
    }

    fun demoteIneligible(
        locale: Locale
    ): DemotionResult {
        val promotedEntries =
            multiWordStore.getForLocale(locale)

        var demotedCount = 0

        promotedEntries.forEach { entry ->
            val phraseUsage =
                phraseUsageRepository.getUsage(
                    locale = locale,
                    words = entry.words
                )

            val phraseStillEligible =
                phraseUsage != null &&
                        phraseUsage.count >= minimumPhraseCount

            val nGramStillEligible =
                hasStrongNGramEvidence(
                    locale = locale,
                    words = entry.words
                )

            val lastUsedAt = entry.lastUsedAt
                .takeIf { it > 0L }
                ?: entry.updatedAt

            val inactiveForAtLeast180Days =
                System.currentTimeMillis() - lastUsedAt >=
                        180L * 24L * 60L * 60L * 1000L

            if (
                inactiveForAtLeast180Days &&
                (!phraseStillEligible || !nGramStillEligible)
            ) {
                val removed = multiWordStore.remove(
                    locale,
                    entry.words
                )

                if (removed) {
                    demotedCount++
                }
            }
        }

        return DemotionResult(
            examined = promotedEntries.size,
            demoted = demotedCount
        )
    }

    fun demoteIneligibleAll(): DemotionResult {
        val locales = multiWordStore.getAll()
            .map { entry ->
                Locale.forLanguageTag(entry.locale)
            }
            .distinctBy { locale ->
                locale.toLanguageTag().lowercase(Locale.ROOT)
            }

        var examinedCount = 0
        var demotedCount = 0

        locales.forEach { locale ->
            val result = demoteIneligible(locale)

            examinedCount += result.examined
            demotedCount += result.demoted
        }

        return DemotionResult(
            examined = examinedCount,
            demoted = demotedCount
        )
    }

    private fun hasStrongNGramEvidence(
        locale: Locale,
        words: List<String>
    ): Boolean {
        if (words.size !in PhraseUsageStore.MIN_SEQUENCE_LENGTH..
            PhraseUsageStore.MAX_SEQUENCE_LENGTH
        ) {
            return false
        }

        val sequence = words.joinToString(" ")

        return words.zipWithNext().all { (previousWord, nextWord) ->
            val usage = nextWordPredictor.getUsage(
                locale = locale,
                previousWord = PunctuatedPhrase.lexical(previousWord),
                nextWord = PunctuatedPhrase.lexical(nextWord)
            )

            if (usage == null) {
                return@all false
            }

            val totalCount = nextWordPredictor.getTotalCount(
                locale = locale,
                previousWord = previousWord
            )

            val confidence =
                if (totalCount > 0) {
                    usage.count.toDouble() / totalCount.toDouble()
                } else {
                    0.0
                }

            usage.count >= minimumTransitionCount &&
                    totalCount > 0 &&
                    (
                            confidence >= minimumTransitionConfidence ||
                                    usage.count >= STRONG_PERSONAL_TRANSITION_COUNT
                            )
        }
    }

    companion object {
        const val DEFAULT_MINIMUM_PHRASE_COUNT = 8
        const val DEFAULT_MINIMUM_TRANSITION_COUNT = 5
        const val DEFAULT_MINIMUM_TRANSITION_CONFIDENCE = 0.60
        const val DEFAULT_CANDIDATE_LIMIT = 50
        private const val STRONG_PERSONAL_TRANSITION_COUNT = 8
    }
}