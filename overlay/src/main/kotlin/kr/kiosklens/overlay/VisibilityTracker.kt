package kr.kiosklens.overlay

/**
 * "하이라이트가 보이는가"가 **바뀔 때만** 알리도록 거르는 장치 (P5).
 *
 * 판단 결과는 손 떨림 때문에 1초에 여러 번 나오는데, 그때마다 알리면 "휴대폰을 뒤로 빼 주세요"가 계속 반복된다.
 * 그래서 이렇게만 알린다.
 * - 새 버튼을 가리키기 시작했는데 처음부터 안 보이면 → false 한 번 (앞 버튼이 안 보였어도 새 버튼이니 다시 알림)
 * - 같은 버튼이 보이다가 안 보이게 되면 → false
 * - 같은 상태가 이어지면 → 알리지 않음 (null)
 * - **false를 알린 뒤** "안 보임"이 끝나면 → true 한 번. 끝나는 경우는 셋이다:
 *   같은 버튼이 다시 보임 · 보이는 다른 버튼으로 바뀜 · 하이라이트가 없어짐(대상 null)
 *
 * 그래서 받는 쪽은 마지막으로 받은 값을 그대로 "지금 안 보이는 하이라이트가 있는가"로 써도 된다.
 * (예전에는 안 보이던 버튼이 보이는 다른 버튼으로 바뀌거나 하이라이트가 없어지면 true를 알리지 않아서
 * 받는 쪽이 계속 "안 보임"으로 알고 있었다 — 2026-11-11 P1 지적, docs/decisions.md 7장)
 */
class VisibilityTracker {
    private var lastId: String? = null
    private var lastVisible: Boolean? = null
    private var reportedHidden = false                  // false를 알렸고 아직 true로 풀지 않았음

    /** 지금 판단 결과를 넣는다. 알려야 하면 true/false, 알릴 필요가 없으면 null. */
    fun update(targetId: String?, visible: Boolean): Boolean? {
        if (targetId == null) {                         // 하이라이트가 없어짐 → 기록을 지우고, 안 보임을 알렸으면 풀어 준다
            lastId = null; lastVisible = null
            return clearHidden()
        }
        if (targetId == lastId && visible == lastVisible) return null
        lastId = targetId                               // 다른 버튼으로 바뀌었거나 같은 버튼의 상태가 바뀜
        lastVisible = visible
        if (visible) return clearHidden()
        reportedHidden = true
        return false
    }

    private fun clearHidden(): Boolean? {
        if (!reportedHidden) return null
        reportedHidden = false
        return true
    }
}
