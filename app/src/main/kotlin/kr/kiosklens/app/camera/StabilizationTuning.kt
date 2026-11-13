package kr.kiosklens.app.camera

/**
 * 흔들림에 강한 촬영 설정을 고르는 계산 (2026-11-13). 카메라에 적용은 [CameraController].
 *
 * - 광학식 손떨림 보정(OIS)이 있는 기기는 켠다. 영상 보정(디지털)은 화면을 잘라 옮겨서
 *   하이라이트 좌표와 어긋날 수 있어 쓰지 않는다.
 * - 프레임 속도 범위를 30fps에 가깝게 고정한다. 범위가 15~30이면 어두울 때 노출 시간이 길어져(1/15초)
 *   조금만 흔들려도 글자가 번진다. 30~30이면 노출 시간이 1/30초를 넘지 않는다.
 */
object StabilizationTuning {
    /** 목표 프레임 속도 */
    const val TARGET_FPS = 30

    /**
     * 기기가 지원하는 (최소, 최대) fps 범위 중 [TARGET_FPS] 이상까지 되면서 노출 시간이 가장 짧은 것.
     * 최소가 높을수록 노출이 짧다. 30을 못 넘으면 가장 높은 것. 하나도 없으면 null (건드리지 않음).
     */
    fun pickFpsRange(ranges: List<Pair<Int, Int>>): Pair<Int, Int>? {
        val valid = ranges.filter { it.first in 1..it.second }
        if (valid.isEmpty()) return null
        val reachable = valid.filter { it.second >= TARGET_FPS }
        return if (reachable.isNotEmpty()) reachable.maxWith(compareBy({ it.first }, { -it.second }))
        else valid.maxWith(compareBy({ it.second }, { it.first }))
    }

    /** OIS 켜기가 가능한 기기인가 ([modes] = LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, [on] = 켜기 값) */
    fun canUseOis(modes: IntArray?, on: Int): Boolean = modes?.contains(on) == true
}
