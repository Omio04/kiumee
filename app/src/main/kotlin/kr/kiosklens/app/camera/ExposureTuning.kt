package kr.kiosklens.app.camera

import kotlin.math.roundToInt

/**
 * 키오스크 화면을 찍을 때의 노출 맞춤값 (2026-11-12).
 *
 * 키오스크 화면은 스스로 빛나서, 둘레(매장 · 방)까지 보고 맞춘 자동 노출이면 하얗게 날아가 글자 획이 번진다
 * (실측: 웹캠에 비친 키오스크가 뿌옇게 나오고 메뉴 이름은 못 읽고 가격 숫자만 읽음).
 * 계산만 따로 두어 JVM 테스트로 확인한다. 카메라에 적용은 [CameraController].
 */
object ExposureTuning {
    /** 낮출 노출 (EV). 한 칸이 0.5EV인 기기(에뮬레이터)에서 한 칸 */
    const val TARGET_EV = -0.5f

    /**
     * 노출을 맞출 가운데 영역의 크기 (센서 가로 · 세로에 대한 비율).
     * 조준 모서리(화면 가로 90% · 세로 74%)가 분석 프레임에서 차지하는 가운데 55~75%쯤과 비슷하게
     */
    const val METERING_SIZE = 0.6f

    /**
     * [ev]에 가장 가까운 노출 보정 칸 번호. 기기가 받는 범위([min]~[max]) 안으로 자른다.
     * 칸 크기가 이상하면(0 이하) 0 = 그대로.
     */
    fun index(ev: Float, step: Float, min: Int, max: Int): Int {
        if (step <= 0f || min > max) return 0
        return (ev / step).roundToInt().coerceIn(min, max)
    }
}
