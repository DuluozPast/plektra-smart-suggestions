package rocks.pkb.plektra.core.suggestions

/** *Cooperative cancellation of synchronous CPU searches, without changing ranking or result limits.* */
internal object SuggestionWorkCancellation {
    private val checkpoint = ThreadLocal<(() -> Unit)?>()

    fun check() { checkpoint.get()?.invoke() }

    // *The block must not suspend: its checkpoint belongs to the current worker thread only.*
    fun <T> withCheckpoint(check: () -> Unit, block: () -> T): T {
        val previous = checkpoint.get()
        checkpoint.set(check)
        try {
            check()
            return block()
        } finally {
            if (previous == null) checkpoint.remove() else checkpoint.set(previous)
        }
    }
}
