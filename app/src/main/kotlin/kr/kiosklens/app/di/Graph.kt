package kr.kiosklens.app.di

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kr.kiosklens.app.camera.FrameJpegCache
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.port.FrameSink
import kr.kiosklens.domain.runner.SessionRunner
import kr.kiosklens.vision.ScanStatus

/** 조립 결과. [GraphFactory]가 실구현으로 만든다. 여기에는 로직이 없다. */
interface Graph {
    val runner: SessionRunner
    /** 앱을 켠 뒤 한 번 인사를 읽는다 */
    fun greet()
    /** 질문 때 AI에게 보여 줄 최신 카메라 사진 보관함 (카메라가 채운다) */
    val frameJpeg: FrameJpegCache
    /** 카메라 프레임을 받는 쪽 */
    val frameSink: FrameSink
    /** 확인용(디버그 빌드): 카메라가 인식한 화면. `SessionRunner`가 받는 것과 같은 흐름이다 (여럿이 구독해도 서로 빼앗지 않음) */
    val snapshots: Flow<ScreenSnapshot>
    /** 확인용(디버그 빌드): 인식 상태 (찾는 중 · 다시 비추기 · 인식됨) */
    val scanStatus: StateFlow<ScanStatus>
    /** 듣는 동안의 목소리 크기 0~1 (마이크 버튼 둘레 파동). 듣지 않을 때는 0 */
    val voiceLevel: StateFlow<Float>
    /** 마지막으로 읽어 준 안내 문장을 화면에 보여 주기 위한 알림 */
    fun setCaptionListener(listener: (String) -> Unit)
    fun close()
}
