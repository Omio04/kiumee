package kr.kiosklens.core.model

/**
 * 카메라 프레임 (설계서 5장 CameraFrame).
 *
 * 설계서 원안은 `imageProxy: ImageProxy`를 직접 들고 있지만, :core-model은 순수 Kotlin이라
 * Android 타입을 참조할 수 없다. 그래서 공통 메타데이터만 인터페이스로 두고,
 * 실제 이미지(ImageProxy, 디버그용 Bitmap 등)는 구현 클래스가 들고 있는다.
 * → 12:00 확정 회의 안건 (docs/decisions.md 참고).
 */
interface CameraFrame {
    val frameId: Long
    val timestampMs: Long
    /** 회전 보정에 필요한 각도. 이후 모든 좌표는 회전 보정된 기준 (4.1). */
    val rotationDegrees: Int
    val width: Int
    val height: Int

    /** 이미지 자원 반환. 소비 측(FrameSink 구현)이 다 쓰거나 버릴 때 정확히 한 번 호출한다. */
    fun close()
}
