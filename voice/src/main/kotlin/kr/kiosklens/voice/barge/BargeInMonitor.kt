package kr.kiosklens.voice.barge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.math.sqrt
import kr.kiosklens.core.port.Logger

/**
 * 앱이 안내를 읽는 동안 마이크를 열어 사람이 말을 시작했는지 듣는다 ([BargeInGate]가 판단).
 *
 * - 입력은 `VOICE_COMMUNICATION`: 기기가 지원하면 스피커 소리를 마이크에서 빼 주는 에코 제거(AEC)가 켜진다. 그래도 남는 에코는 [BargeInGate]가 처음 0.3초로 재서 거른다.
 * - **읽는 동안에만** 켠다. 음성 인식([kr.kiosklens.voice.stt.AndroidSpeechInput])이 마이크를 쓰기 전에 반드시 반납한다
 *   (마이크는 한 번에 한 곳만 쓸 수 있는 기기가 많다): 감지 → 마이크 반납 → 그 다음에 [onSpeech] 호출.
 * - 권한이 없거나 마이크를 못 열면 조용히 아무것도 하지 않는다 (말 끊기만 안 될 뿐 앱은 그대로 동작).
 * - 소리 크기만 보고 녹음 내용은 저장 · 전송하지 않는다 (버퍼는 프레임마다 덮어쓴다).
 * ⚠️ 기기마다 에코 크기가 달라 [BargeInGate]의 상수는 현장에서 조정한다. 이 클래스는 실기기에서만 확인할 수 있다.
 */
class BargeInMonitor(
    private val context: Context,
    private val logger: Logger,
    private val gateFactory: () -> BargeInGate = { BargeInGate() },
) {
    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    fun start(onSpeech: () -> Unit) {
        stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        running = true
        thread = Thread({ listen(onSpeech) }, "kl-barge-in").apply { isDaemon = true; start() }
    }

    /** 듣기를 멈추고 마이크를 반납한다 (이미 멈췄으면 아무 일도 없다). 감지 콜백 안에서 불러도 안전하다. */
    fun stop() {
        running = false
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(400) }
    }

    private fun listen(onSpeech: () -> Unit) {
        val frame = SAMPLE_RATE * FRAME_MS / 1000
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, frame * 2 * 8))
        } catch (t: Throwable) {
            logger.w(TAG, "barge-in mic unavailable: ${t.javaClass.simpleName}"); return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return }
        val aec = runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true } else null }.getOrNull()
        val ns = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true } else null }.getOrNull()
        val gate = gateFactory()
        val buffer = ShortArray(frame)
        var heard = false
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buffer, 0, frame)
                if (n < 0) break
                if (n == 0) continue
                if (gate.onFrame(rms(buffer, n))) { heard = true; break }
            }
        } catch (t: Throwable) {
            logger.w(TAG, "barge-in listen failed: ${t.javaClass.simpleName}")
        } finally {
            runCatching { record.stop() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            record.release()                                                    // 음성 인식이 마이크를 쓰기 전에 반납
        }
        if (heard && running) {
            running = false
            logger.d("KL_PERF", "barge-in detected")
            onSpeech()
        }
    }

    private fun rms(samples: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) { val s = samples[i].toDouble(); sum += s * s }
        return sqrt(sum / n).toFloat()
    }

    private companion object {
        const val TAG = "BargeInMonitor"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
    }
}
