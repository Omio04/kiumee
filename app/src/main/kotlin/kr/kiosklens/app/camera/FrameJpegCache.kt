package kr.kiosklens.app.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * 질문할 때 AI에게 보여 줄 "지금 카메라 화면" 사진 한 장 (2026-11-13 실기기).
 *
 * 왜: 글자 읽기(OCR)만 넘기면 글자가 깨지거나 화면 아래 버튼이 빠져서 AI가 없는 버튼을 지어냈다. 사진을 보면 알아본다.
 * - 선명한 프레임(흔들림 거름을 통과한 것)만, 0.8초에 한 번 작게(긴 변 [MAX_EDGE]px · JPEG [QUALITY]) 줄여 **최신 한 장만** 들고 있다.
 * - 디스크에 쓰지 않는다. 오래된 사진([MAX_AGE_MS] 초과)은 보내지 않는다.
 * - 켜고 끄기: BuildConfig.SEND_IMAGE (local.properties kl.sendImage). 서버도 민감한 글자가 읽힌 요청에서는 사진을 버린다.
 */
class FrameJpegCache(
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** 사진 하나 만드는 데 걸린 시간(ms)과 크기(바이트)를 알린다. 내용은 담지 않는다. 폰에서 비용을 재서 줄일 곳을 정하려고 둔 장치 */
    private val onCost: (ms: Long, bytes: Int) -> Unit = { _, _ -> },
) {
    private class Shot(val base64: String, val atMs: Long)

    private val latest = AtomicReference<Shot?>(null)
    @Volatile private var lastTriedMs = 0L

    /** 분석 스레드에서, 프레임을 sink에 넘기기 **전에** 호출 (넘긴 뒤에는 닫혔을 수 있다). 실패해도 분석에 영향이 없게 삼킨다. */
    fun offer(proxy: ImageProxy) {
        val now = nowMs()
        if (now - lastTriedMs < MIN_GAP_MS) return
        lastTriedMs = now
        runCatching {
            val src = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val scale = MAX_EDGE.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1)
            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rot != 0) postRotate(rot.toFloat()) }
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val bytes = ByteArrayOutputStream(64 * 1024).also { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
            if (out !== src) src.recycle()
            out.recycle()
            latest.set(Shot(Base64.encodeToString(bytes, Base64.NO_WRAP), now))
            onCost(nowMs() - now, bytes.size)
        }
    }

    /** 가장 최근 사진(base64). 없거나 오래됐으면 null */
    fun latestBase64(): String? {
        val s = latest.get() ?: return null
        return s.base64.takeIf { nowMs() - s.atMs <= MAX_AGE_MS }
    }

    fun clear() { latest.set(null) }

    private companion object {
        const val MIN_GAP_MS = 800L
        const val MAX_EDGE = 960
        const val QUALITY = 70
        const val MAX_AGE_MS = 5_000L
    }
}
