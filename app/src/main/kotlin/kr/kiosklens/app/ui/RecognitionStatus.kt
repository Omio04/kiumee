package kr.kiosklens.app.ui

import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.vision.ScanStatus

/**
 * 확인용(디버그 빌드) 위쪽 줄: 카메라가 지금 무엇을 인식하고 있는지 한 줄로.
 * 어르신용 문구가 아니다 (릴리스 빌드에는 나오지 않음).
 */
object RecognitionStatus {
    fun text(status: ScanStatus, snapshot: ScreenSnapshot?, visible: Boolean): String {
        if (!visible) return "[확인용] 인식 표시 꺼짐 · 누르면 켜짐"
        return when (status) {
            ScanStatus.REPOSITION -> "[확인용] 글자를 찾는 중 · 글자가 3개 이상 보이게 비춰 주세요"
            ScanStatus.SCANNING -> "[확인용] 읽는 중 · 잠시 가만히 비춰 주세요"
            ScanStatus.READY -> snapshot?.let { s ->
                val buttons = s.elements.count { it.kind == ElementKind.BUTTON_LIKE }
                "[확인용] 인식: 글자 ${s.elements.size}개 · 버튼 ${buttons}개 · 화면 ${s.screenId.take(12)} · 누르면 숨김"
            } ?: "[확인용] 읽는 중 · 잠시 가만히 비춰 주세요"
        }
    }
}
