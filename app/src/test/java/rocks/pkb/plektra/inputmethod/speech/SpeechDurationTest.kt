package rocks.pkb.plektra.inputmethod.speech

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeechDurationTest {
    @Test fun eachFiniteLimitStopsAtItsBoundaryWithSilenceDisabled() {
        for (limit in SpeechSettings.durationOptionsMs.filter { it > 0 }) {
            val detector = SpeechSilenceDetector(limit)
            assertFalse(detector.accept(0.08, limit - 1, 0))
            assertTrue(detector.accept(0.08, 1, 0))
        }
    }

    @Test fun unlimitedContinuesBeyondFifteenMinutesAndStillStopsForSilence() {
        val detector = SpeechSilenceDetector(0)
        assertFalse(detector.accept(0.08, 3_600_000, 3000))
        assertFalse(detector.accept(0.0001, 2999, 3000))
        assertTrue(detector.accept(0.0001, 1, 3000))
        assertTrue(SpeechSilenceDetector(0).accept(0.0001, 8000, 0))
    }

    @Test fun fileBackedAudioPreservesSamplesAndDurationInBoundedChunksAndDeletesOnClose() {
        val file = File.createTempFile("speech-test-", ".pcm")
        val samples = ByteArray(18) { it.toByte() }
        file.writeBytes(samples)
        val audio = RecordedSpeech.fromFile(file, 2)
        assertEquals(4500L, audio.durationMs)
        assertEquals(0, audio.pcm.size)
        val chunks = audio.chunks(8).toList()
        assertEquals(listOf(8, 8, 2), chunks.map { it.pcm.size })
        assertArrayEquals(samples, chunks.flatMap { it.pcm.toList() }.toByteArray())
        audio.close()
        assertFalse(file.exists())
        val live = RecordedSpeech.streamed(24_000L * 2 * 3600, 24000)
        assertEquals(3_600_000L, live.durationMs)
        assertTrue(live.pcm.isEmpty())
    }

    @Test fun sessionPropagatesLimitAndDeletesSpoolOnSuccessFailureAndCancellation() = runBlocking {
        for (outcome in listOf("success", "failure", "cancel")) {
            val file = File.createTempFile("speech-session-test-", ".pcm").apply { writeBytes(ByteArray(32)) }
            var configured: Long? = null
            var released = false
            val recorder = object : SpeechAudioRecorder {
                override fun setMaxDurationMs(ms: Long) { configured = ms }
                override suspend fun record(silenceMs: Long, onLevel: (Float, Long?) -> Unit) = RecordedSpeech.fromFile(file, 16000)
                override fun finish() {}
                override fun release() { released = true }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val uploading = CompletableDeferred<Unit>()
            val controller = SpeechSessionController(scope, { recorder }, { _, _ ->
                uploading.complete(Unit)
                when (outcome) {
                    "failure" -> throw java.io.IOException("test")
                    "cancel" -> awaitCancellation()
                    else -> SpeechResult("test")
                }
            }, {}, { _, _ -> }, { _, _, _ -> }, {})
            controller.start(SpeechRequest(SpeechProvider.OPENAI, SpeechModel("test", "test"), "key", "de", 1, maxDurationMs = 900_000))
            uploading.await()
            if (outcome == "cancel") controller.cancel()
            yield()
            assertEquals(900_000L, configured)
            assertFalse("Spool left after $outcome", file.exists())
            assertTrue(released)
            scope.cancel()
        }
    }
}
