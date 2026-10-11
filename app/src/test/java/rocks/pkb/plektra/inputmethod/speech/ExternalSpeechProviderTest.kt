package rocks.pkb.plektra.inputmethod.speech

import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodSubtype
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExternalSpeechProviderTest {
    private fun method(pkg: String, id: String, subtypes: List<InputMethodSubtype>) =
        mock(InputMethodInfo::class.java).also {
            `when`(it.packageName).thenReturn(pkg)
            `when`(it.id).thenReturn(id)
            `when`(it.subtypeCount).thenReturn(subtypes.size)
            subtypes.forEachIndexed { index, subtype -> `when`(it.getSubtypeAt(index)).thenReturn(subtype) }
        }

    private fun subtype(mode: String, auxiliary: Boolean) = InputMethodSubtype.InputMethodSubtypeBuilder()
        .setSubtypeMode(mode).setIsAuxiliary(auxiliary).build()

    @Test fun selectsFutoAuxiliaryVoiceSubtypeInsteadOfOpeningRecognitionActivity() {
        val keyboard = subtype("keyboard", false)
        val voice = subtype("voice", true)
        val futo = method(ExternalSpeechProvider.FUTO_PACKAGE, "futo/VoiceInputMethodService", listOf(keyboard, voice))
        val google = method("com.google.android.tts", "google/VoiceInputMethodService", listOf(voice))
        val selected = ExternalSpeechProvider.resolveVoiceInput(listOf(google, futo))!!
        assertEquals("futo/VoiceInputMethodService", selected.id)
        assertSame(voice, selected.subtype)
        assertFalse(SpeechProvider.FUTO.isCloud)
    }

    @Test fun doesNotFallBackToAnotherProviderOrRegularKeyboardSubtype() {
        val other = method("another.app", "another/VoiceInput", listOf(subtype("voice", true)))
        val keyboardOnly = method(ExternalSpeechProvider.FUTO_PACKAGE, "futo/Keyboard", listOf(subtype("keyboard", false)))
        assertNull(ExternalSpeechProvider.resolveVoiceInput(emptyList()))
        assertNull(ExternalSpeechProvider.resolveVoiceInput(listOf(other, keyboardOnly)))
    }

    @Test fun requiresAuxiliaryVoiceSubtypeForReturnToOriginalKeyboard() {
        val regular = method(ExternalSpeechProvider.FUTO_PACKAGE, "futo/Voice", listOf(subtype("voice", false)))
        assertNull(ExternalSpeechProvider.resolveVoiceInput(listOf(regular)))
    }

    @Test fun handOffKeepsEditorFocusAndCallsPlatformImeSwitchWithoutLaunchingActivity() {
        val service = mock(InputMethodService::class.java)
        val connection = mock(InputConnection::class.java)
        `when`(service.currentInputConnection).thenReturn(connection)
        val target = ExternalSpeechProvider.VoiceInput("futo/VoiceInputMethodService", subtype("voice", true))
        assertTrue(ExternalSpeechProvider.handOff(service, target))
        val order = inOrder(connection, service)
        order.verify(connection).finishComposingText()
        order.verify(service).requestShowSelf(InputMethodManager.SHOW_IMPLICIT)
        order.verify(service).switchInputMethod(target.id, target.subtype)
        verify(service, never()).startActivity(any(android.content.Intent::class.java))
    }

    @Test fun handOffWithoutAnEditorDoesNotSwitchKeyboardOrStartRecording() {
        val service = mock(InputMethodService::class.java)
        val target = ExternalSpeechProvider.VoiceInput("futo/VoiceInputMethodService", subtype("voice", true))
        assertFalse(ExternalSpeechProvider.handOff(service, target))
        verify(service, never()).switchInputMethod(anyString(), any(InputMethodSubtype::class.java))
        verify(service, never()).requestShowSelf(anyInt())
    }
}
