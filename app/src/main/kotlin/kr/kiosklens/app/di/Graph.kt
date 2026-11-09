package kr.kiosklens.app.di

import kr.kiosklens.core.port.FrameSink
import kr.kiosklens.domain.runner.SessionRunner

/** 조립 결과. 플레이버(real / mock)마다 [GraphFactory]가 다르게 만든다. 여기에는 로직이 없다. */
interface Graph {
    val runner: SessionRunner
    /** 카메라 프레임을 받는 쪽 */
    val frameSink: FrameSink
    /** 마지막으로 읽어 준 안내 문장을 화면에 보여 주기 위한 알림 */
    fun setCaptionListener(listener: (String) -> Unit)
    /** mock 에서만 의미 있음: 다음 샘플 화면으로 */
    fun debugNextScreen() {}
    fun close()
}
