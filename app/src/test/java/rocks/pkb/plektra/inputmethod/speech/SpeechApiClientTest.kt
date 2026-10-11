package rocks.pkb.plektra.inputmethod.speech

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SpeechApiClientTest {
    @Test fun longFileRecordingUploadsBoundedOrderedSegmentsAndCombinesResults() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"erster Teil","usage":{"input_tokens":2,"output_tokens":3}}"""))
            server.enqueue(MockResponse().setBody("""{"text":"zweiter Teil","usage":{"input_tokens":5,"output_tokens":7}}"""))
            val file = java.io.File.createTempFile("speech-upload-test-", ".pcm")
            file.writeBytes(ByteArray(500) { (it % 127).toByte() })
            RecordedSpeech.fromFile(file, 2).use { audio ->
                val request = SpeechRequest(SpeechProvider.OPENAI, SpeechModel("test", "test"), "test-key", "de-DE", 1)
                val result = SpeechApiClient(openAiBase = server.url("/").toString()).transcribe(request, audio)
                assertEquals("erster Teil zweiter Teil", result.text)
                assertEquals(7L, result.inputTokens)
                assertEquals(10L, result.outputTokens)
                assertNull(result.costUsd)
                for (expected in listOf(480, 20)) {
                    val received = server.takeRequest(3, TimeUnit.SECONDS)!!
                    val body = received.body.readByteArray()
                    val riff = body.toList().windowed(4).indexOfFirst { it == "RIFF".toByteArray().toList() }
                    assertTrue(riff >= 0)
                    val pcmSize = java.nio.ByteBuffer.wrap(body, riff + 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    assertEquals(expected, pcmSize)
                    assertEquals("Bearer test-key", received.getHeader("Authorization"))
                }
                assertEquals(2, server.requestCount)
            }
            assertFalse(file.exists())
        }
    }

    @Test fun openRouterUsesSttEndpointAndParsesRealResponseShape() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"Hallo","usage":{"cost":0.001,"input_tokens":3,"output_tokens":1}}"""))
            val client = SpeechApiClient(openRouterBase = server.url("/").toString())
            val request = SpeechRequest(SpeechProvider.OPENROUTER, SpeechModels.openRouterDefaults.first(), "test-key", "de-DE", 1)
            val result = client.transcribe(request, RecordedSpeech(ByteArray(6400)))
            val received = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("/audio/transcriptions", received.path)
            assertEquals("Bearer test-key", received.getHeader("Authorization"))
            val body = received.body.readUtf8()
            assertTrue(body.contains("\"language\":\"de\""))
            assertTrue(body.contains("\"provider\":{\"zdr\":true}"))
            assertEquals("Hallo", result.text)
            assertEquals(0.001, result.costUsd!!, 0.0)
        }
    }

    @Test fun openAiUsesMultipartWithLanguageAndWavWithoutTemporaryFiles() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"Olá","usage":{"input_tokens":5,"output_tokens":1}}"""))
            val client = SpeechApiClient(openAiBase = server.url("/").toString())
            val request = SpeechRequest(SpeechProvider.OPENAI,
                SpeechModels.openAiDefaults.first { it.protocol == SpeechProtocol.TRANSCRIPTION }, "test-key", "pt-BR", 1)
            val result = client.transcribe(request, RecordedSpeech(byteArrayOf(1, 2)))
            val received = server.takeRequest(3, TimeUnit.SECONDS)!!
            val body = received.body.readUtf8()
            assertTrue(received.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            assertTrue(body.contains("name=\"language\"\r\nContent-Length: 2\r\n\r\npt"))
            assertTrue(body.contains("filename=\"dictation.wav\""))
            assertTrue(body.contains("RIFF"))
            assertNull(result.costUsd)
        }
    }

    @Test fun authenticationFailureIsNotReportedAsValidAndUsesPrivateKeyEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"unauthorized\"}"))
            val client = SpeechApiClient(openRouterBase = server.url("/").toString())
            try { client.validateKey(SpeechProvider.OPENROUTER, "invalid"); fail("Expected auth failure") }
            catch (e: SpeechHttpException) { assertEquals(401, e.status) }
            assertEquals("/key", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
        }
    }
    private fun enqueueCatalog(server: MockWebServer, endpoints: String) {
        server.enqueue(MockResponse().setBody("""{"data":[
            {"id":"openai/whisper-large-v3-turbo","name":"Whisper","architecture":{"input_modalities":["audio"],"output_modalities":["transcription"]}},
            {"id":"unverified","architecture":{"input_modalities":["audio"],"output_modalities":["transcription"]}},
            {"id":"down","architecture":{"input_modalities":["audio"],"output_modalities":["transcription"]}},
            {"id":"unknown","architecture":{"input_modalities":["audio"],"output_modalities":["transcription"]}}
        ]}"""))
        server.enqueue(MockResponse().setBody("""{"data":[
            {"id":"chat","architecture":{"input_modalities":["audio","text"],"output_modalities":["text"]}},
            {"id":"text-only","architecture":{"input_modalities":["text"],"output_modalities":["text"]}}
        ]}"""))
        server.enqueue(MockResponse().setBody(endpoints))
    }

    @Test fun freshDiscoveryExcludesNonZdrDownAndUnknownEndpointsAndKeepsAudioProtocols() = runBlocking {
        MockWebServer().use { server ->
            val client = SpeechApiClient(openRouterBase = server.url("/").toString())
            enqueueCatalog(server, """{"data":[
                {"model_id":"openai/whisper-large-v3-turbo","status":0},
                {"model_id":"openai/whisper-large-v3-turbo","status":0},
                {"model_id":"down","status":-5}, {"model_id":"unknown"},
                {"model_id":"chat","status":0}, {"model_id":"text-only","status":0}
            ]}""")
            val models = client.models()
            assertEquals(listOf("openai/whisper-large-v3-turbo", "chat"), models.map { it.id })
            assertEquals(SpeechProtocol.AUDIO_CHAT, models.last().protocol)
            repeat(2) { server.takeRequest(3, TimeUnit.SECONDS)!! }
            assertEquals("/endpoints/zdr", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
            // A second lookup must use new endpoint status, not the previous successful list.
            enqueueCatalog(server, """{"data":[{"model_id":"chat","status":0}]}""")
            val selected = SpeechRequest(SpeechProvider.OPENROUTER, models.first(), "key", "de-DE", 1)
            val prepared = client.prepareRequest(selected)
            assertEquals("chat", prepared.model.id)
            assertEquals("de-DE", prepared.languageTag)
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun unavailableZdrDiscoveryBlocksPreparationWithoutUploadingAudio() = runBlocking {
        MockWebServer().use { server ->
            enqueueCatalog(server, """{"data":[]}""")
            val client = SpeechApiClient(openRouterBase = server.url("/").toString())
            val request = SpeechRequest(SpeechProvider.OPENROUTER, SpeechModels.openRouterDefaults.first(), "key", "en", 1)
            try { client.prepareRequest(request); fail("Expected missing ZDR model") }
            catch (_: NoZdrModelException) { }
            assertEquals(3, server.requestCount)
            repeat(3) { assertEquals("GET", server.takeRequest(3, TimeUnit.SECONDS)!!.method) }
        }
    }

    @Test fun routingRejectionNeverRetriesWithoutZdr() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val client = SpeechApiClient(openRouterBase = server.url("/").toString())
            val request = SpeechRequest(SpeechProvider.OPENROUTER, SpeechModels.openRouterDefaults.first(), "key", "en", 1)
            try { client.transcribe(request, RecordedSpeech(ByteArray(6400))); fail("Expected rejection") }
            catch (e: SpeechHttpException) { assertEquals(503, e.status) }
            assertEquals(1, server.requestCount)
            assertTrue(server.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8().contains("\"zdr\":true"))
        }
    }

}
