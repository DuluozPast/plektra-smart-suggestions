package rocks.pkb.plektra.inputmethod.speech

import kotlinx.coroutines.*

interface SpeechAudioRecorder {
    fun setMaxDurationMs(ms: Long) {}
    fun release() {}
    suspend fun record(silenceMs: Long, onLevel: (Float, Long?) -> Unit): RecordedSpeech
    suspend fun recordStreaming(silenceMs: Long, onLevel: (Float, Long?) -> Unit,
        onPcm: suspend (ByteArray) -> Unit): RecordedSpeech = error("Recorder does not support streaming")
    fun finish()
}

/** Accessed on the IME main thread; each coroutine owns its recorder and request. */
class SpeechSessionController(
    private val scope: CoroutineScope,
    private val recorderFactory: () -> SpeechAudioRecorder,
    private val transcribe: suspend (SpeechRequest, RecordedSpeech) -> SpeechResult,
    private val onState: (SpeechState) -> Unit,
    private val onLevel: (Float, Long?) -> Unit,
    private val onResult: (SpeechRequest, RecordedSpeech, SpeechResult) -> Unit,
    private val onError: (Exception) -> Unit,
    private val prepareRequest: suspend (SpeechRequest) -> SpeechRequest = { it },
    private val liveTranscriber: LiveSpeechTranscriber? = null,
    private val liveRecorderFactory: () -> SpeechAudioRecorder = { PcmSpeechRecorder(24000) },
    private val onPartial: (SpeechRequest, String) -> Unit = { _, _ -> }
) {
    private class Session(val request: SpeechRequest, val recorder: SpeechAudioRecorder) {
        lateinit var job: Job
        var state = SpeechState.RECORDING
    }
    private var current: Session? = null
    val state: SpeechState get() = current?.state ?: SpeechState.IDLE

    fun start(request: SpeechRequest) {
        val recordingJob = current?.takeIf { it.state == SpeechState.RECORDING }?.job
        cancel()
        val live = request.provider == SpeechProvider.OPENAI && request.model.protocol == SpeechProtocol.LIVE_TRANSCRIPTION
        val session = Session(request, if (live) liveRecorderFactory() else recorderFactory())
        session.recorder.setMaxDurationMs(request.maxDurationMs)
        current = session
        session.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Release the previous AudioRecord before opening another microphone session.
                recordingJob?.join()
                val levels: (Float, Long?) -> Unit = { level, remaining ->
                    scope.launch { if (current === session && session.state == SpeechState.RECORDING) onLevel(level, remaining) }
                }
                if (live) {
                    val result = checkNotNull(liveTranscriber).transcribe(request, session.recorder, levels,
                        { text -> if (current === session) onPartial(request, text) },
                        {
                            if (current === session) {
                                session.state = SpeechState.TRANSCRIBING
                                onState(SpeechState.TRANSCRIBING)
                            }
                        })
                    result.audio.use {
                        ensureActive()
                        if (current === session) onResult(request, it, result.result)
                    }
                    return@launch
                }
                val audio = session.recorder.record(request.silenceMs, levels)
                audio.use {
                    ensureActive()
                    if (current !== session) return@launch
                    session.state = SpeechState.TRANSCRIBING
                    onState(SpeechState.TRANSCRIBING)
                    val preparedRequest = prepareRequest(request)
                    ensureActive()
                    val result = transcribe(preparedRequest, audio)
                    ensureActive()
                    if (current === session) onResult(preparedRequest, audio, result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current === session) onError(e)
            } finally {
                session.recorder.release()
                if (current === session) {
                    current = null
                    onState(SpeechState.IDLE)
                }
            }
        }
        onState(SpeechState.RECORDING)
        session.job.start()
    }

    fun toggleStop() {
        current?.let { if (it.state == SpeechState.RECORDING) it.recorder.finish() else cancel() }
    }

    fun cancel() {
        val previous = current ?: return
        current = null
        previous.job.cancel()
        previous.recorder.finish()
        onState(SpeechState.IDLE)
    }
}
