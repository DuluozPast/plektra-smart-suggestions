package rocks.pkb.plektra.core.suggestions

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** *One live model per app data directory, including across keyboard-service restarts.* */
internal object SharedLearningStores {
    data class Stores(
        val words: WordUsageStore,
        val corrections: CorrectionUsageStore,
        val phrases: AsyncPhraseUsageRepository,
        val multiwords: MultiWordStore,
        val nextWord: NextWordPredictor
    )
    private val stores = ConcurrentHashMap<String, Stores>()
    fun get(context: Context): Stores = stores.computeIfAbsent(context.filesDir.canonicalPath) {
        Stores(WordUsageStore(context, true), CorrectionUsageStore(context, true),
            AsyncPhraseUsageRepository(PhraseUsageStore(context)), MultiWordStore(context, true),
            NextWordPredictor(UserNGramStore(context)))
    }
    /** *Restoration must replace even a model belonging to an inactive keyboard session.* */
    fun invalidate(context: Context) {
        stores[context.filesDir.canonicalPath]?.let {
            it.words.invalidateCache()
            it.corrections.invalidateCache()
            it.phrases.invalidateCache()
            it.multiwords.invalidateCache()
        }
    }
}
