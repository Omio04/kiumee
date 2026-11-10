package kr.kiosklens.core.model

/**
 * 카메라 프레임 (설계서 5장 CameraFrame, 계약 v1에서 인터페이스로 변경).
 *
 * :core-model은 순수 Kotlin이라 Android 타입(ImageProxy, Bitmap)을 직접 쓸 수 없다.
 * 그래서 실제 이미지는 [image]에 Any로 담고, 쓰는 쪽(:vision, :overlay)이 꺼내서 캐스팅한다.
 *
 * 구현체
 * - 실제 카메라: :app/camera의 ImageProxyFrame → image는 androidx.camera.core.ImageProxy
 * - 사진 입력 디버그 모드: image는 android.graphics.Bitmap
 */
interface CameraFrame {
    val frameId: Long
    val timestampMs: Long

    /** 회전 보정에 필요한 각도. 이후 모든 좌표는 회전 보정된 기준 (4.1). */
    val rotationDegrees: Int
    val width: Int
    val height: Int

    /** 실제 이미지 객체. ImageProxy 또는 Bitmap. 예: `(frame.image as? ImageProxy)` */
    val image: Any

    /** 이미지 자원 반환. 소비 측(FrameSink 구현)이 다 쓰거나 버릴 때 정확히 한 번 호출한다. */
    fun close()
}
