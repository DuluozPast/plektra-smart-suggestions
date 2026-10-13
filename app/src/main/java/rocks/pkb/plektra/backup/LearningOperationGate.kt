
package rocks.pkb.plektra.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

object LearningOperationGate {

    private val mutex = Mutex()
    private val learningSuspended = AtomicBoolean(false)

    val isLearningSuspended: Boolean
        get() = learningSuspended.get()

    suspend fun <T> withExclusiveOperation(
        block: suspend () -> T
    ): T = mutex.withLock {
        learningSuspended.set(true)
        try {
            block()
        } finally {
            learningSuspended.set(false)
        }
    }


    @Volatile
    var prepareForRestore: (suspend () -> Unit)? = null


    suspend fun awaitPendingLearning() {
        val preparation = prepareForRestore
        if (preparation != null) {
            preparation.invoke()
        } else {
            android.util.Log.i(
                "PastieraLearning",
                "No active suggestion controller to drain"
            )
        }
    }


}
