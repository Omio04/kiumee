package kr.kiosklens.overlay.tracking

import java.nio.ByteBuffer

/**
 * 카메라 프레임의 밝기(Y) 평면을 **회전 보정된** 작은 격자로 줄인다 (흔들림 추정용).
 *
 * - 결과는 행 우선(위 → 아래, 왼쪽 → 오른쪽), 값은 0..255
 * - 칸마다 2×2 지점의 밝기 평균 (한 점만 보면 글자 가장자리에서 값이 튄다)
 * - [rotationDegrees]는 CameraX의 `imageInfo.rotationDegrees` (시계 방향으로 돌려야 똑바로 서는 각도).
 *   정규화 좌표(설계서 4.1)와 같은 방향이 되도록 돌려서 읽는다
 *
 * Android 타입을 쓰지 않아 JVM에서 테스트한다. ImageProxy라면 `planes[0]`의 buffer · rowStride · pixelStride를 넘긴다.
 */
object LumaGrid {
    fun sample(
        y: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int, rotationDegrees: Int,
        cols: Int, rows: Int, out: FloatArray = FloatArray(cols * rows),
    ): FloatArray {
        require(cols > 0 && rows > 0 && out.size >= cols * rows) { "격자 크기가 맞지 않음" }
        val rot = ((rotationDegrees % 360) + 360) % 360
        val maxX = width - 1
        val maxY = height - 1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var sum = 0
                for (sy in 0..1) {
                    val v = (r + 0.25f + 0.5f * sy) / rows                     // 똑바로 선 화면 기준 0..1
                    for (sx in 0..1) {
                        val u = (c + 0.25f + 0.5f * sx) / cols
                        val nx: Float
                        val ny: Float
                        when (rot) {                                            // 똑바로 선 (u, v) → 센서 (nx, ny)
                            90 -> { nx = v; ny = 1f - u }
                            180 -> { nx = 1f - u; ny = 1f - v }
                            270 -> { nx = 1f - v; ny = u }
                            else -> { nx = u; ny = v }
                        }
                        val px = (nx * width).toInt().coerceIn(0, maxX)
                        val py = (ny * height).toInt().coerceIn(0, maxY)
                        sum += y.get(py * rowStride + px * pixelStride).toInt() and 0xFF
                    }
                }
                out[r * cols + c] = sum / 4f
            }
        }
        return out
    }
}
