package kr.kiosklens.app.ui

import kr.kiosklens.domain.session.Phase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 마이크 버튼(하늘색 구슬)이 보여 주는 상태. 2026-11-12 화면 개편으로 자막을 없앴으니,
 * 지금 무슨 상태인지는 글자 대신 버튼의 색과 움직임으로 보여 준다.
 */
enum class OrbState {
    /** 대기 · 누르기를 기다림: 마이크 + 천천히 숨 쉬는 빛 (눌러 달라는 신호) */
    IDLE,
    /** 듣는 중: 마이크 + 목소리 크기를 따르는 파동 */
    LISTENING,
    /** 생각 중 (서버 판단 · 화면 찾기): 둘레를 도는 하얀 호 */
    THINKING,
    /** 말하는 중 (안내 · 질문 · 마무리 읽기): 마이크 대신 출렁이는 소리 막대 */
    SPEAKING,
    /** 문제 (화면을 놓침 · 오류): 주황빛으로 숨 쉬기 */
    PROBLEM,
    /** 주문 끝: 하얀 체크 표시가 그려지며 나타난다 (잘 마쳤다는 확인) */
    DONE;

    companion object {
        fun of(phase: Phase): OrbState = when (phase) {
            Phase.IDLE, Phase.WAIT_CHANGE -> IDLE
            Phase.LISTENING -> LISTENING
            Phase.PLANNING, Phase.SCANNING -> THINKING
            Phase.GUIDING, Phase.ASKING -> SPEAKING
            Phase.DONE -> DONE
            Phase.LOST, Phase.ERROR -> PROBLEM
        }
    }
}

/** 카메라 화면 조준 모서리의 모습 ([ViewfinderView]) */
enum class ViewfinderLook {
    /** 키오스크 화면을 찾는 중: 하얀 모서리가 숨 쉰다 */
    SEARCHING,
    /** 키오스크 글자를 읽음: 맑은 하늘색으로 바뀌며 살짝 조여든다 */
    FOUND,
    /** 노란 안내 테두리가 있는 동안: 옅게 (테두리가 돋보이게) */
    QUIET,
    /** 화면을 놓침 · 오류: 주황빛 */
    PROBLEM;

    companion object {
        fun of(scanReady: Boolean, orb: OrbState): ViewfinderLook = when (orb) {
            OrbState.PROBLEM -> PROBLEM
            OrbState.SPEAKING, OrbState.DONE -> if (scanReady) QUIET else SEARCHING
            else -> if (scanReady) FOUND else SEARCHING
        }
    }
}

/** 구슬의 움직임 계산 (그리는 쪽과 떼어 기기 없이 테스트). 모두 경과 시간(ms)만으로 정해진다. */
object OrbMotion {
    /** 숨 쉬기 한 번 */
    const val BREATH_MS = 2400L
    /** 생각 중 호가 한 바퀴 */
    const val SPIN_MS = 1100L
    /** 소리 막대 출렁임 한 번 */
    const val WAVE_MS = 900L
    /** 소리 막대 수 */
    const val BARS = 5

    /** 로딩 화면: 노란 안내 테두리가 그려지기 시작하는 때 · 다 그려지는 데 걸리는 시간 */
    const val TRACE_DELAY_MS = 250L
    const val TRACE_MS = 900L

    /** 노란 안내 테두리가 그려진 정도 0~1 (처음엔 빠르게, 끝에서 천천히 — 버튼을 짚어 멈추는 느낌) */
    fun trace(elapsedMs: Long): Float {
        val x = ((elapsedMs - TRACE_DELAY_MS).toFloat() / TRACE_MS).coerceIn(0f, 1f)
        val inv = 1f - x
        return 1f - inv * inv * inv
    }

    /** 주문 끝 체크 표시가 그려지는 시간 */
    const val CHECK_MS = 450L

    /** 체크 표시가 그려진 정도 0~1 (처음엔 빠르게, 끝에서 천천히) */
    fun check(sinceMs: Long): Float {
        val x = (sinceMs.toFloat() / CHECK_MS).coerceIn(0f, 1f)
        val inv = 1f - x
        return 1f - inv * inv * inv
    }

    /** 로딩 화면 캐릭터 눈 깜빡임: [BLINK_EVERY_MS]마다 [BLINK_MS] 동안. 첫 깜빡임은 글자가 떠오른 뒤(약 1.6초) */
    const val BLINK_EVERY_MS = 2800L
    const val BLINK_MS = 170L

    /** 눈 감은 정도 0(뜸)~1(감음) */
    fun blink(elapsedMs: Long): Float {
        val k = (elapsedMs + BLINK_EVERY_MS - 1600L).mod(BLINK_EVERY_MS)
        return if (k < BLINK_MS) sin(PI * k / BLINK_MS).toFloat() else 0f
    }

    /** 숨 쉬기 0~1 (부드럽게 들어갔다 나옴) */
    fun breath(elapsedMs: Long): Float =
        ((sin(2 * PI * (elapsedMs % BREATH_MS) / BREATH_MS - PI / 2) + 1) / 2).toFloat()

    /** 생각 중 호의 시작 각도 0~360 */
    fun spin(elapsedMs: Long): Float = (elapsedMs % SPIN_MS) * 360f / SPIN_MS

    /**
     * i번째 소리 막대 높이 (버튼 반지름 대비 0.2~0.62). 가운데 막대가 가장 크게 출렁인다 (두 번째 시안 그림 모양).
     */
    fun bar(i: Int, elapsedMs: Long): Float {
        val profile = floatArrayOf(0.55f, 0.8f, 1f, 0.8f, 0.55f)[i.coerceIn(0, BARS - 1)]
        val wave = abs(sin(2 * PI * (elapsedMs % WAVE_MS) / WAVE_MS + i * 0.9)).toFloat()
        return 0.2f + 0.42f * profile * (0.35f + 0.65f * wave)
    }
}
