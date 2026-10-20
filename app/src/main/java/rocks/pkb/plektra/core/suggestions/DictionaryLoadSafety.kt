package rocks.pkb.plektra.core.suggestions

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Reclaim a failed large index before trying one bundled dictionary, at most once. */
internal suspend fun loadDictionaryWithMemoryFallback(
    selected: suspend () -> Unit,
    clear: () -> Unit,
    bundled: suspend () -> Unit,
    onFallback: (Boolean) -> Unit
): Boolean {
    try {
        selected()
        return true
    } catch (_: OutOfMemoryError) {
        clear()
    }
    val recovered = try {
        bundled()
        true
    } catch (_: OutOfMemoryError) {
        clear()
        false
    }
    onFallback(recovered)
    return recovered
}

/** Reserve before launching: a repository waiting for the global load mutex is
 * already pending even though its own isLoadStarted flag has not yet changed.
 * A failed repository is not retried on every keystroke; a reload creates a new one.
 */
internal class DictionaryLoadGate(
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit
) {
    private val pending = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val jobs = IdentityHashMap<Any, Job>()
    private val failed = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    fun launchOnce(repository: Any, block: suspend () -> Unit): Job? {
        synchronized(this) {
            if (repository in failed || !pending.add(repository)) return null
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (oom: OutOfMemoryError) {
                synchronized(this@DictionaryLoadGate) { failed.add(repository) }
                onFailure(oom)
            } catch (error: Exception) {
                synchronized(this@DictionaryLoadGate) { failed.add(repository) }
                onFailure(error)
            }
        }
        synchronized(this) { jobs[repository] = job }
        job.invokeOnCompletion {
            synchronized(this) {
                if (jobs[repository] === job) jobs.remove(repository)
                pending.remove(repository)
            }
        }
        job.start()
        return job
    }

    fun cancelExcept(retained: Collection<Any>) {
        val obsolete = synchronized(this) {
            failed.retainAll(retained.toSet())
            jobs.filterKeys { key -> retained.none { it === key } }.values.toList()
        }
        obsolete.forEach { it.cancel() }
    }
}

/** Leave room for query temporaries and editor interaction after a large load.
 * A selected dictionary that fits only just is retried as bundled, without
 * changing its saved selection. Values are heap headroom, not total phone RAM.
 */
internal fun hasTypingMemoryReserve(maxHeapBytes: Long, headroomBytes: Long): Boolean {
    val reserve = (maxHeapBytes / 8).coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024)
    return headroomBytes >= reserve
}
