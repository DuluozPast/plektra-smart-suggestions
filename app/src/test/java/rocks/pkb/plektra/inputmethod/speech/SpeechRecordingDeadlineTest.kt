package rocks.pkb.plektra.inputmethod.speech

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class SpeechRecordingDeadlineTest {
    @Test fun finiteDeadlineStopsOnceAndCancelOrUnlimitedRemovesOldDeadline() {
        var stops = 0
        val deadline = SpeechRecordingDeadline { stops++ }
        val looper = shadowOf(Looper.getMainLooper())
        deadline.start(180_000)
        looper.idleFor(Duration.ofMillis(179_999))
        assertEquals(0, stops)
        looper.idleFor(Duration.ofMillis(1))
        assertEquals(1, stops)
        deadline.start(60_000)
        deadline.cancel()
        looper.idleFor(Duration.ofMinutes(2))
        assertEquals(1, stops)
        deadline.start(60_000)
        deadline.start(0)
        looper.idleFor(Duration.ofHours(1))
        assertEquals(1, stops)
    }
}
