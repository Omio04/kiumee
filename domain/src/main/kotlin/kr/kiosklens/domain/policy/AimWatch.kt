package kr.kiosklens.domain.policy

import kr.kiosklens.domain.session.AimIssue

/**
 * 비추는 데 문제가 **몇 초째 이어지는지** 지켜보다가, 알릴 때가 되면 [AimIssue]를 하나 낸다 (2026-11-13 P5).
 * 앱이 카메라 · 오버레이 신호를 넣고, 0.5초쯤마다 [due]를 불러 나온 것을 `SessionEvent.AimTrouble`로 보낸다.
 * 시간은 밖에서 넣는다 (기기 없이 테스트).
 *
 * - 잠깐의 흔들림에는 말하지 않는다: 문제가 [noTextMs] · [hiddenMs] 동안 끊김 없이 이어져야 한다
 * - 같은 말을 되풀이해 귀찮게 하지 않는다: 한 번 낸 뒤 [gapMs] 동안은 내지 않는다 (팀 결정: 5~10초 간격)
 * - 둘 다면 글자를 못 읽는 쪽을 먼저: 키오스크가 안 보이면 버튼 위치도 믿을 수 없다
 *
 * 메인 스레드 등 한 스레드에서만 쓴다.
 */
class AimWatch(
    /** 글자를 거의 못 읽는 상태가 이만큼 이어지면 알린다 */
    private val noTextMs: Long = 3_000,
    /** 안내 중인 버튼이 안 보이는 상태가 이만큼 이어지면 알린다 */
    private val hiddenMs: Long = 2_000,
    /** 한 번 알린 뒤 다시 알리기까지 */
    private val gapMs: Long = 8_000,
) {
    private var noTextSince: Long? = null
    private var hiddenSince: Long? = null
    private var lastSent: Long? = null

    /** 카메라 글자 읽기 결과: [found] = 키오스크 글자를 3개 이상 읽음 (vision `ScanStatus`가 REPOSITION이 아님) */
    fun textFound(found: Boolean, nowMs: Long) {
        noTextSince = if (found) null else noTextSince ?: nowMs
    }

    /** 오버레이가 알린 안내 버튼 보임 여부 (`ViewOverlay.onTargetVisibilityChanged`) */
    fun targetVisible(visible: Boolean, nowMs: Long) {
        hiddenSince = if (visible) null else hiddenSince ?: nowMs
    }

    /** 지금 알릴 문제. 알리면 [gapMs] 동안은 null */
    fun due(nowMs: Long): AimIssue? {
        val last = lastSent
        if (last != null && nowMs - last in 0 until gapMs) return null
        val issue = when {
            lasted(noTextSince, nowMs, noTextMs) -> AimIssue.NO_TEXT
            lasted(hiddenSince, nowMs, hiddenMs) -> AimIssue.TARGET_HIDDEN
            else -> return null
        }
        lastSent = nowMs
        return issue
    }

    private fun lasted(since: Long?, nowMs: Long, needMs: Long) = since != null && nowMs - since >= needMs
}
