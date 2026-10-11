package rocks.pkb.plektra.inputmethod.speech

import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SettingLinkRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpeechDurationSettingsTest {
    @Test fun durationChoicesPersistAndRemainIndependentOfSilence() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit().clear().commit()
        assertEquals(180_000L, SpeechSettings.maxDurationMs(context))
        SpeechSettings.setSilenceMs(context, 5000)
        for (value in SpeechSettings.durationOptionsMs) {
            SpeechSettings.setMaxDurationMs(context, value)
            assertEquals(value, SpeechSettings.maxDurationMs(context))
            assertEquals(5000L, SpeechSettings.silenceMs(context))
        }
        prefs.edit().putLong("speech_max_duration_ms", -1).commit()
        assertEquals(180_000L, SpeechSettings.maxDurationMs(context))
        assertNotNull(SettingLinkRegistry.byId("speech.duration"))
        assertThrows(IllegalArgumentException::class.java) { SpeechSettings.setMaxDurationMs(context, 42) }
    }
}
