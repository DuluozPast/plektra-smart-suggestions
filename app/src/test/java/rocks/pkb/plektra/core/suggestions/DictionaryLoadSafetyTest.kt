package rocks.pkb.plektra.core.suggestions

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DictionaryLoadSafetyTest {
    @Test fun selectedDictionaryNeedsNoFallback() = runBlocking {
        assertTrue(loadDictionaryWithMemoryFallback({}, { fail("clear") }, { fail("fallback") }, { fail("notice") }))
    }
    @Test fun releasesPartialIndexBeforeBundledFallback() = runBlocking {
        val steps = mutableListOf<String>()
        assertTrue(loadDictionaryWithMemoryFallback(
            { steps.add("selected"); throw OutOfMemoryError("synthetic") },
            { steps.add("clear") }, { steps.add("bundled") }, { steps.add("notice:$it") }))
        assertEquals(listOf("selected", "clear", "bundled", "notice:true"), steps)
    }
    @Test fun failedFallbackIsClearedAndReportedWithoutAnotherAttempt() = runBlocking {
        var clears = 0
        var attempts = 0
        var notice: Boolean? = null
        assertFalse(loadDictionaryWithMemoryFallback(
            { attempts++; throw OutOfMemoryError("synthetic") }, { clears++ },
            { attempts++; throw OutOfMemoryError("synthetic") }, { notice = it }))
        assertEquals(2, attempts)
        assertEquals(2, clears)
        assertEquals(false, notice)
    }
    @Test fun cancellationDoesNotStartBundledFallback() = runBlocking {
        try {
            loadDictionaryWithMemoryFallback({ throw CancellationException() }, { fail("clear") },
                { fail("fallback") }, { fail("notice") })
            fail("cancellation was swallowed")
        } catch (_: CancellationException) { }
    }
    @Test fun queuedLoadIsReservedBeforeRepositoryStarts() = runBlocking {
        val gate = DictionaryLoadGate(this) { fail("unexpected failure") }
        val key = Any()
        var loads = 0
        val first = gate.launchOnce(key) { loads++ }!!
        repeat(30) { assertNull(gate.launchOnce(key) { loads++ }) }
        first.join()
        assertEquals(1, loads)
    }
    @Test fun memoryFailureDoesNotKillOtherLanguagesOrRetryEveryKeystroke() = runBlocking {
        var failures = 0
        val gate = DictionaryLoadGate(this) { assertTrue(it is OutOfMemoryError); failures++ }
        val key = Any()
        gate.launchOnce(key) { throw OutOfMemoryError("synthetic") }!!.join()
        repeat(30) { assertNull(gate.launchOnce(key) { fail("retry") }) }
        var otherLoaded = false
        gate.launchOnce(Any()) { otherLoaded = true }!!.join()
        assertEquals(1, failures)
        assertTrue(otherLoaded)
    }
    @Test fun cancellationReleasesReservationForLaterLoad() = runBlocking {
        val gate = DictionaryLoadGate(this) { fail("cancellation reported as error") }
        val key = Any()
        val job = gate.launchOnce(key) { awaitCancellation() }!!
        job.cancelAndJoin()
        assertNotNull(gate.launchOnce(key) {})
    }
    @Test fun ordinaryFailureDoesNotRetryUntilRepositoryIsRecreated() = runBlocking {
        var failures = 0
        val gate = DictionaryLoadGate(this) { failures++ }
        val key = Any()
        gate.launchOnce(key) { error("invalid dictionary") }!!.join()
        assertNull(gate.launchOnce(key) { fail("retry") })
        assertNotNull(gate.launchOnce(Any()) {})
        assertEquals(1, failures)
    }

    @Test fun changingLanguageCancelsObsoleteLoadsButKeepsTheActiveLoad() = runBlocking {
        val gate = DictionaryLoadGate(this) { fail("cancelled load must not fail") }
        val french = Any()
        val english = Any()
        val old = gate.launchOnce(french) { awaitCancellation() }!!
        val active = gate.launchOnce(english) { awaitCancellation() }!!
        gate.cancelExcept(listOf(english))
        old.join()
        assertTrue(old.isCancelled)
        assertTrue(active.isActive)
        active.cancelAndJoin()
    }
    @Test fun reclaimedInactiveRepositoryDoesNotKeepFailureReferences() = runBlocking {
        val gate = DictionaryLoadGate(this) { }
        val old = Any()
        gate.launchOnce(old) { error("synthetic") }!!.join()
        gate.cancelExcept(emptyList())
        assertNotNull(gate.launchOnce(old) {})
    }
    @Test fun typingReserveUsesHeapHeadroomAndAllowsAComfortableMargin() {
        val mb = 1024L * 1024L
        assertFalse(hasTypingMemoryReserve(256 * mb, 10 * mb))
        assertTrue(hasTypingMemoryReserve(256 * mb, 40 * mb))
        assertFalse(hasTypingMemoryReserve(1024 * mb, 63 * mb))
        assertTrue(hasTypingMemoryReserve(1024 * mb, 64 * mb))
    }
}
