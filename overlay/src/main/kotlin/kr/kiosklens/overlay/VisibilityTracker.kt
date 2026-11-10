package kr.kiosklens.overlay

/**
 * "하이라이트가 보이는가"가 **바뀔 때만** 알리도록 거르는 장치 (P5).
 *
 * 판단 결과는 손 떨림 때문에 1초에 여러 번 나오는데, 그때마다 알리면 "휴대폰을 뒤로 빼 주세요"가 계속 반복된다.
 * 그래서 이렇게만 알린다.
 * - 새 버튼을 가리키기 시작했는데 처음부터 안 보이면 → false 한 번
 * - 같은 버튼이 보이다가 안 보이게 되면 → false, 안 보이다가 다시 보이면 → true
 * - 같은 상태가 이어지면 → 알리지 않음 (null)
 * - 하이라이트가 없어지면(대상 null) 기록을 지운다
 */
class VisibilityTracker {
    private var lastId: String? = null
    private var lastVisible: Boolean? = null

    /** 지금 판단 결과를 넣는다. 알려야 하면 true/false, 알릴 필요가 없으면 null. */
    fun update(targetId: String?, visible: Boolean): Boolean? {
        if (targetId == null) {
            lastId = null; lastVisible = null
            return null
        }
        if (targetId != lastId) {                       // 다른 버튼으로 바뀜 → 처음부터 다시 판단
            lastId = targetId
            lastVisible = visible
            return if (visible) null else false
        }
        if (visible == lastVisible) return null
        lastVisible = visible
        return visible
    }
}
