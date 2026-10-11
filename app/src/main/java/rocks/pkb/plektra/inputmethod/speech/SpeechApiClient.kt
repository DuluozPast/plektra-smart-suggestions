package rocks.pkb.plektra.inputmethod.speech

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SpeechApiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    private val openRouterBase: String = "https://openrouter.ai/api/v1/",
    private val openAiBase: String = "https://api.openai.com/v1/"
) {
    suspend fun transcribe(request: SpeechRequest, audio: RecordedSpeech): SpeechResult = withContext(Dispatchers.IO) {
        require(request.provider.isCloud)
        require(request.model.protocol != SpeechProtocol.LIVE_TRANSCRIPTION) { "Use the live transcription client" }
        val parts = mutableListOf<String>()
        var cost: Double? = 0.0
        var inputTokens: Long? = 0L
        var outputTokens: Long? = 0L
        for (chunk in audio.chunks()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val result = transcribeChunk(request, chunk)
            if (result.text.isNotBlank()) parts += result.text
            cost = if (cost != null && result.costUsd != null) cost + result.costUsd else null
            inputTokens = if (inputTokens != null && result.inputTokens != null) inputTokens + result.inputTokens else null
            outputTokens = if (outputTokens != null && result.outputTokens != null) outputTokens + result.outputTokens else null
        }
        SpeechResult(parts.joinToString(" "), cost, inputTokens, outputTokens)
    }

    private suspend fun transcribeChunk(request: SpeechRequest, audio: RecordedSpeech): SpeechResult {
        val base = if (request.provider == SpeechProvider.OPENAI) openAiBase else openRouterBase
        val protocol = if (request.provider == SpeechProvider.OPENAI) SpeechProtocol.TRANSCRIPTION else request.model.protocol
        val path = if (protocol == SpeechProtocol.AUDIO_CHAT) "chat/completions" else "audio/transcriptions"
        val body = if (request.provider == SpeechProvider.OPENAI) {
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", request.model.id)
                .addFormDataPart("response_format", "json")
                .apply { if (request.language.isNotBlank()) addFormDataPart("language", request.language) }
                .addFormDataPart("file", "dictation.wav", audio.wav().toRequestBody("audio/wav".toMediaType()))
                .build()
        } else {
            SpeechModels.openRouterPayload(request, Base64.getEncoder().encodeToString(audio.wav()))
                .toString().toRequestBody("application/json".toMediaType())
        }
        val response = execute(Request.Builder().url(base + path)
            .header("Authorization", "Bearer ${request.apiKey}").post(body).build())
        return SpeechModels.parseResult(response, protocol)
    }

    suspend fun validateKey(provider: SpeechProvider, key: String) {
        require(provider.isCloud && key.isNotBlank())
        val url = if (provider == SpeechProvider.OPENROUTER) openRouterBase + "key" else openAiBase + "models"
        execute(Request.Builder().url(url).header("Authorization", "Bearer $key").build())
    }

    suspend fun models(): List<SpeechModel> {
        val transcription = execute(Request.Builder().url(openRouterBase + "models?output_modalities=transcription").build())
        val chat = execute(Request.Builder().url(openRouterBase + "models?input_modalities=audio&output_modalities=text").build())
        val endpoints = execute(Request.Builder().url(openRouterBase + "endpoints/zdr").build())
        return SpeechModels.filterZdrModels(
            SpeechModels.parseCatalog(transcription, SpeechProtocol.TRANSCRIPTION) +
                SpeechModels.parseCatalog(chat, SpeechProtocol.AUDIO_CHAT), endpoints)
    }

    suspend fun prepareRequest(request: SpeechRequest): SpeechRequest =
        if (request.provider == SpeechProvider.OPENROUTER) {
            request.copy(model = SpeechModels.selectZdrModel(models(), request.model))
        } else request

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try {
                        if (!response.isSuccessful) throw SpeechHttpException(response.code)
                        val body = response.body?.string() ?: throw IOException("Empty speech response")
                        continuation.resume(body)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }
}
