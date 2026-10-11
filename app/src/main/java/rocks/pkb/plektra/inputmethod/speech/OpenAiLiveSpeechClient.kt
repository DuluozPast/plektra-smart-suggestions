package rocks.pkb.plektra.inputmethod.speech

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

data class LiveSpeechResult(val audio: RecordedSpeech, val result: SpeechResult)

interface LiveSpeechTranscriber {
    suspend fun transcribe(request: SpeechRequest, recorder: SpeechAudioRecorder,
        onLevel: (Float, Long?) -> Unit, onPartial: (String) -> Unit,
        onRecordingFinished: () -> Unit): LiveSpeechResult
}

/** A connection belongs to one dictation and one editor; reconnecting never replays audio. */
class OpenAiLiveSpeechClient(
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    private val url: String = "wss://api.openai.com/v1/realtime?intent=transcription"
) : LiveSpeechTranscriber {
    override suspend fun transcribe(request: SpeechRequest, recorder: SpeechAudioRecorder,
        onLevel: (Float, Long?) -> Unit, onPartial: (String) -> Unit,
        onRecordingFinished: () -> Unit): LiveSpeechResult = coroutineScope {
        require(request.provider == SpeechProvider.OPENAI && request.model.id == "gpt-live-transcribe")
        val events = Channel<JsonObject>(64)
        val socket = http.newWebSocket(Request.Builder().url(url)
            .header("Authorization", "Bearer ${request.apiKey}").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(sessionUpdate(request).toString())) events.close(IOException("Live connection closed"))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (!events.trySend(Json.parseToJsonElement(text).jsonObject).isSuccess) {
                        events.close(IOException("Live event buffer full")); webSocket.cancel()
                    }
                } catch (_: Exception) {
                    events.close(IOException("Invalid live transcription event")); webSocket.cancel()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.close(response?.let { SpeechHttpException(it.code) } ?: IOException("Live connection failed"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.close(IOException("Live connection closed before completion"))
            }
        })
        try {
            // Capture immediately while the socket connects. Keep a bounded, ordered
            // buffer and send it only after the server accepts the configuration.
            val chunks = Channel<ByteArray>(512)
            val capture = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    recorder.recordStreaming(request.silenceMs, onLevel) { pcm ->
                        check(chunks.trySend(pcm).isSuccess) { "Live audio buffer full" }
                    }
                } finally {
                    chunks.close()
                    onRecordingFinished()
                }
            }
            waitForServer(15000) {
                while (true) {
                    val event = events.receive()
                    checkError(event)
                    if (event["type"]?.jsonPrimitive?.content == "session.updated") break
                }
            }
            val transcript = async {
                var itemId: String? = null
                val partial = StringBuilder()
                while (true) {
                    val event = events.receive()
                    checkError(event)
                    when (event["type"]?.jsonPrimitive?.content) {
                        "conversation.item.input_audio_transcription.delta" -> {
                            val id = event.getValue("item_id").jsonPrimitive.content
                            if (itemId == null) itemId = id
                            check(itemId == id) { "Unexpected live transcription turn" }
                            partial.append(event.getValue("delta").jsonPrimitive.content)
                            onPartial(partial.toString())
                        }
                        "conversation.item.input_audio_transcription.completed" -> {
                            val id = event.getValue("item_id").jsonPrimitive.content
                            check(itemId == null || itemId == id) { "Unexpected live transcription completion" }
                            val usage = event["usage"]?.takeUnless { it is JsonNull }?.jsonObject
                            return@async SpeechResult(event.getValue("transcript").jsonPrimitive.content.trim(),
                                inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull,
                                outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull)
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("No live transcript")
            }
            for (pcm in chunks) {
                currentCoroutineContext().ensureActive()
                check(socket.queueSize() <= 1_048_576) { "Live audio connection too slow" }
                check(socket.send(buildJsonObject {
                    put("type", "input_audio_buffer.append")
                    put("audio", Base64.getEncoder().encodeToString(pcm))
                }.toString())) { "Live audio connection closed" }
            }
            val audio = capture.await()
            ensureActive()
            check(socket.send(buildJsonObject { put("type", "input_audio_buffer.commit") }.toString())) {
                "Live audio connection closed"
            }
            LiveSpeechResult(audio, waitForServer(30000) { transcript.await() })
        } finally {
            socket.cancel()
            events.cancel()
        }
    }

    private suspend fun <T> waitForServer(timeoutMs: Long, block: suspend CoroutineScope.() -> T): T =
        try { withTimeout(timeoutMs, block) }
        catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Live transcription timed out")
        }

    private fun checkError(event: JsonObject) {
        when (event["type"]?.jsonPrimitive?.content) {
            "error", "conversation.item.input_audio_transcription.failed" -> {
                val code = event["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull ?: "transcription_failed"
                throw SpeechRealtimeException(code)
            }
        }
    }

    internal fun sessionUpdate(request: SpeechRequest): JsonObject = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "transcription")
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", 24000) }
                    putJsonObject("transcription") {
                        put("model", "gpt-live-transcribe")
                        put("delay", "low")
                        if (request.language.isNotBlank()) putJsonArray("languages") { add(request.language) }
                    }
                    put("turn_detection", JsonNull)
                }
            }
        }
    }
}
