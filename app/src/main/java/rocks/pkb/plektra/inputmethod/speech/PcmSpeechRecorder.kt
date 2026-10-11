package rocks.pkb.plektra.inputmethod.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

internal class SpeechSilenceDetector(private val maxDurationMs: Long = SpeechSettings.DEFAULT_MAX_DURATION_MS) {
    private var elapsedMs = 0L
    private var lastSoundMs = 0L
    private var noiseFloor = 0.002
    var heardSpeech = false
        private set
    var silenceRemainingMs: Long? = null
        private set

    fun accept(rms: Double, durationMs: Long, silenceMs: Long): Boolean {
        elapsedMs += durationMs
        val threshold = maxOf(0.006, noiseFloor * 3)
        if (rms >= threshold) {
            heardSpeech = true
            lastSoundMs = elapsedMs
        } else {
            noiseFloor = minOf(noiseFloor * 1.002 + 0.000001, rms)
        }
        val quietMs = elapsedMs - lastSoundMs
        silenceRemainingMs = if (silenceMs > 0 && heardSpeech && quietMs >= 300) {
            (silenceMs - quietMs).coerceAtLeast(0)
        } else null
        return (maxDurationMs > 0 && elapsedMs >= maxDurationMs) ||
            (!heardSpeech && elapsedMs >= 8000) ||
            (silenceMs > 0 && heardSpeech && quietMs >= silenceMs)
    }
}

class PcmSpeechRecorder(
    private val sampleRate: Int = 16000,
    private val cacheDir: File = File(checkNotNull(System.getProperty("java.io.tmpdir")))
) : SpeechAudioRecorder {
    private var retainedFile: File? = null
    override fun release() { retainedFile?.delete(); retainedFile = null }
    private var maxDurationMs = SpeechSettings.DEFAULT_MAX_DURATION_MS
    override fun setMaxDurationMs(ms: Long) {
        require(ms >= 0)
        maxDurationMs = ms
    }
    private val finishing = AtomicBoolean(false)

    override suspend fun record(silenceMs: Long, onLevel: (Float, Long?) -> Unit): RecordedSpeech =
        capture(silenceMs, onLevel, retainAudio = true) {}

    override suspend fun recordStreaming(silenceMs: Long, onLevel: (Float, Long?) -> Unit,
        onPcm: suspend (ByteArray) -> Unit): RecordedSpeech =
        capture(silenceMs, onLevel, retainAudio = false, onPcm)

    private suspend fun capture(silenceMs: Long, onLevel: (Float, Long?) -> Unit,
        retainAudio: Boolean, onPcm: suspend (ByteArray) -> Unit): RecordedSpeech = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Audio recording unavailable" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
        var file: File? = null
        var transferred = false
        var output: java.io.OutputStream? = null
        try {
            if (retainAudio) {
                file = File.createTempFile("plektra-dictation-", ".pcm", cacheDir)
                retainedFile = file
                output = file.outputStream().buffered()
            }
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Audio recorder initialization failed" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone unavailable" }
            val samples = ShortArray(sampleRate * 30 / 1000)
            var bytes = 0L
            val detector = SpeechSilenceDetector(maxDurationMs)
            while (!finishing.get()) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                check(count > 0) { "Audio recording failed" }
                var energy = 0.0
                val chunk = ByteArray(count * 2)
                repeat(count) { i ->
                    val value = samples[i].toInt()
                    chunk[i * 2] = value.toByte(); chunk[i * 2 + 1] = (value shr 8).toByte()
                    val normalized = value / 32768.0
                    energy += normalized * normalized
                }
                output?.write(chunk)
                bytes += chunk.size
                onPcm(chunk)
                val rms = sqrt(energy / count)
                val stop = detector.accept(rms, count * 1000L / sampleRate, silenceMs)
                onLevel((20 * log10(maxOf(rms, 0.00001)) + 60).toFloat().coerceIn(-10f, 10f), detector.silenceRemainingMs)
                if (stop) break
            }
            currentCoroutineContext().ensureActive()
            if (!detector.heardSpeech || bytes < sampleRate * 2 / 5) throw NoSpeechException()
            output?.close()
            output = null
            val audio = file?.let { RecordedSpeech.fromFile(it, sampleRate) }
                ?: RecordedSpeech.streamed(bytes, sampleRate)
            transferred = true
            audio
        } finally {
            try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            finally {
                try { recorder.release() } finally {
                    try { output?.close() } finally { if (!transferred) file?.delete() }
                }
            }
        }
    }

    override fun finish() { finishing.set(true) }
}
