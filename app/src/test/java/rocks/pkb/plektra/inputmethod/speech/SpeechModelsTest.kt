package rocks.pkb.plektra.inputmethod.speech

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class SpeechModelsTest {
    private fun request(protocol: SpeechProtocol = SpeechProtocol.TRANSCRIPTION) = SpeechRequest(
        SpeechProvider.OPENROUTER, SpeechModel("test/model", "Test", protocol), "secret-key", "de-DE", 17)

    @Test fun transcriptionCarriesKeyboardLanguageAndWav() {
        val wav = RecordedSpeech(byteArrayOf(1, 2, 3, 4)).wav()
        val payload = SpeechModels.openRouterPayload(request(), Base64.getEncoder().encodeToString(wav))
        assertEquals("de", payload.getValue("language").jsonPrimitive.content)
        assertTrue(payload.getValue("provider").jsonObject.getValue("zdr").jsonPrimitive.boolean)
        assertEquals("wav", payload.getValue("input_audio").jsonObject.getValue("format").jsonPrimitive.content)
        val bytes = Base64.getDecoder().decode(payload.getValue("input_audio").jsonObject.getValue("data").jsonPrimitive.content)
        assertArrayEquals(wav, bytes)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(32000, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(28))
        assertEquals(4, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes.copyOfRange(44, 48))
        assertFalse(request().toString().contains("secret-key"))
    }

    @Test fun audioModelsReceiveRegionalLanguageAndTranscriptionInstruction() {
        val payload = SpeechModels.openRouterPayload(request(SpeechProtocol.AUDIO_CHAT), "wav")
        assertTrue(payload.getValue("provider").jsonObject.getValue("zdr").jsonPrimitive.boolean)
        val messages = payload.getValue("messages").jsonArray
        assertTrue(messages[0].jsonObject.getValue("content").jsonPrimitive.content.contains("Do not translate"))
        val content = messages[1].jsonObject.getValue("content").jsonArray
        assertTrue(content[0].jsonObject.getValue("text").jsonPrimitive.content.contains("de-DE"))
        assertEquals("input_audio", content[1].jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun catalogDistinguishesSttFromAudioChatAndExcludesTtsAndTextOnly() {
        val body = """{"data":[
          {"id":"stt","name":"STT","architecture":{"input_modalities":["audio"],"output_modalities":["transcription"]}},
          {"id":"gemini","architecture":{"input_modalities":["audio","text"],"output_modalities":["text"]}},
          {"id":"tts","architecture":{"input_modalities":["text"],"output_modalities":["audio"]}},
          {"id":"text","architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
          {"id":"incomplete"}
        ]}"""
        assertEquals(listOf("stt"), SpeechModels.parseCatalog(body, SpeechProtocol.TRANSCRIPTION).map { it.id })
        assertEquals(listOf("gemini"), SpeechModels.parseCatalog(body, SpeechProtocol.AUDIO_CHAT).map { it.id })
    }

    @Test fun resultUsesReportedCostsAndPreservesUnknownCosts() {
        val result = SpeechModels.parseResult("""{"text":" Hallo Welt ","usage":{"cost":0.000508,"input_tokens":83,"output_tokens":30}}""", SpeechProtocol.TRANSCRIPTION)
        assertEquals("Hallo Welt", result.text)
        assertEquals(0.000508, result.costUsd!!, 0.0)
        assertEquals(83L, result.inputTokens)
        assertNull(SpeechModels.parseResult("""{"text":"Hallo"}""", SpeechProtocol.TRANSCRIPTION).costUsd)
        val chat = SpeechModels.parseResult("""{"choices":[{"message":{"content":"Hello"}}],"usage":{"cost":0,"prompt_tokens":2,"completion_tokens":1}}""", SpeechProtocol.AUDIO_CHAT)
        assertEquals(0.0, chat.costUsd!!, 0.0)
        assertEquals(2L, chat.inputTokens)
    }

    @Test fun usageCountsWordsAndAudioDurationSeparatelyFromTokensAndCost() {
        val audio = RecordedSpeech(ByteArray(32000 * 30))
        val usage = SpeechUsage("OPENROUTER", "model").plus(audio, SpeechResult("one two\nthree", 0.1, 70, 4))
            .plus(audio, SpeechResult("four five", null, 80, 3))
        assertEquals(5L, usage.words)
        assertEquals(150L, usage.inputTokens)
        assertEquals(60000L, usage.durationMs)
        assertEquals(5.0, usage.wordsPerMinute, 0.0)
        assertEquals(1L, usage.pricedRecordings)
        assertEquals(0.1, usage.costUsd, 0.0)
    }

    @Test fun pauseKeepsRecordingForThreeSecondsAndResetsOnSpeech() {
        val detector = SpeechSilenceDetector()
        assertFalse(detector.accept(0.08, 300, 3000))
        assertFalse(detector.accept(0.0001, 800, 3000))
        assertEquals(2200L, detector.silenceRemainingMs)
        assertFalse(detector.accept(0.0001, 1200, 3000))
        assertEquals(1000L, detector.silenceRemainingMs)
        assertFalse(detector.accept(0.08, 300, 3000))
        assertNull(detector.silenceRemainingMs)
        assertFalse(detector.accept(0.0001, 2999, 3000))
        assertTrue(detector.accept(0.0001, 1, 3000))
    }

    @Test fun manualModeDoesNotStopOnPausesAndSilenceDoesNotBecomeSpeech() {
        val manual = SpeechSilenceDetector(60_000)
        assertFalse(manual.accept(0.08, 300, 0))
        assertFalse(manual.accept(0.0001, 5000, 0))
        assertNull(manual.silenceRemainingMs)
        assertTrue(manual.accept(0.0001, 55000, 0))
        val silence = SpeechSilenceDetector()
        assertTrue(silence.accept(0.0001, 8000, 3000))
        assertFalse(silence.heardSpeech)
    }

    @Test fun defaultPauseAllowsTwoSecondThinkingPauseAndStopsAfterThreeSecondsQuiet() {
        val pauseMs = request().silenceMs
        assertEquals(3000L, pauseMs)
        val detector = SpeechSilenceDetector()
        assertFalse(detector.accept(0.08, 300, pauseMs))
        assertFalse(detector.accept(0.0001, 2000, pauseMs))
        assertEquals(1000L, detector.silenceRemainingMs)
        assertFalse(detector.accept(0.08, 300, pauseMs))
        assertNull(detector.silenceRemainingMs)
        assertFalse(detector.accept(0.0001, 2999, pauseMs))
        assertTrue(detector.accept(0.0001, 1, pauseMs))
    }
}
