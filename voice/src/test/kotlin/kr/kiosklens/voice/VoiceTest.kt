package kr.kiosklens.voice

import kr.kiosklens.core.model.SttError
import kr.kiosklens.voice.stt.SttMapping
import kr.kiosklens.voice.stt.SttNext
import kr.kiosklens.voice.tts.SpeechSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 2일차 결정(Fake 없이 실구현으로): FakeSpeechInput · FakeSpeechOutput과 그 계약 테스트는 지웠다.
 * AndroidSpeechInput · AndroidSpeechOutput은 SpeechRecognizer · TextToSpeech가 필요해 JVM에서 돌릴 수 없으므로,
 * 포트 계약(Final/Error 하나 · 끝까지 읽고 복귀 · 취소 즉시 중단)은 기기에서 확인한다 (voice/README.md).
 */
class VoiceMappingTest {
    @Test fun `소리 크기는 0~1로 (마이크 버튼 파동용)`() {
        assertEquals(0f, SttMapping.level(-10f), 0f)                       // 조용함
        assertEquals(1f, SttMapping.level(20f), 0f)                        // 아주 큼
        assertTrue(SttMapping.level(2f) in 0.2f..0.5f)                     // 보통 말소리
        assertTrue(SttMapping.level(8f) > SttMapping.level(2f))
    }

    @Test fun `오류 번호 매핑`() {
        assertEquals(SttError.NO_SPEECH, SttMapping.toSttError(SttMapping.ERROR_NO_MATCH))
        assertEquals(SttError.NO_SPEECH, SttMapping.toSttError(SttMapping.ERROR_SPEECH_TIMEOUT))
        assertEquals(SttError.NETWORK, SttMapping.toSttError(SttMapping.ERROR_NETWORK))
        assertEquals(SttError.NETWORK, SttMapping.toSttError(SttMapping.ERROR_SERVER))
        assertEquals(SttError.BUSY, SttMapping.toSttError(SttMapping.ERROR_RECOGNIZER_BUSY))
        assertEquals(SttError.PERMISSION, SttMapping.toSttError(SttMapping.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(SttError.NO_SPEECH, SttMapping.toSttError(SttMapping.ERROR_AUDIO))
        assertEquals(SttError.NO_SPEECH, SttMapping.toSttError(9999))
        // 12 · 13은 SttError에 맞는 값이 없어 NETWORK로 둔다 (NO_SPEECH로 바꾸면 더 틀린 안내가 됨)
        assertEquals(SttError.NETWORK, SttMapping.toSttError(SttMapping.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(SttError.NETWORK, SttMapping.toSttError(SttMapping.ERROR_LANGUAGE_UNAVAILABLE))
    }

    @Test fun `오프라인으로 듣다가 한국어 모델이 없으면 재시도를 쓰지 않고 온라인 일반 인식기로 바꾼다 (온디바이스든 일반 인식기 오프라인이든)`() {
        assertEquals(SttNext.FALL_BACK_TO_STANDARD, SttMapping.next(SttMapping.ERROR_LANGUAGE_UNAVAILABLE, offline = true, retried = false))
        assertEquals(SttNext.FALL_BACK_TO_STANDARD, SttMapping.next(SttMapping.ERROR_LANGUAGE_NOT_SUPPORTED, offline = true, retried = false))
        assertEquals(SttNext.FALL_BACK_TO_STANDARD, SttMapping.next(SttMapping.ERROR_LANGUAGE_UNAVAILABLE, offline = true, retried = true))
    }

    @Test fun `온라인 일반 인식기와 다른 오류는 기존 재시도 규칙을 따른다`() {
        assertEquals(SttNext.RETRY, SttMapping.next(SttMapping.ERROR_LANGUAGE_UNAVAILABLE, offline = false, retried = false))
        assertEquals(SttNext.FINISH, SttMapping.next(SttMapping.ERROR_LANGUAGE_UNAVAILABLE, offline = false, retried = true))
        assertEquals(SttNext.RETRY, SttMapping.next(SttMapping.ERROR_NETWORK, offline = true, retried = false))
        assertEquals(SttNext.FINISH, SttMapping.next(SttMapping.ERROR_NETWORK, offline = true, retried = true))
        assertEquals(SttNext.FINISH, SttMapping.next(SttMapping.ERROR_INSUFFICIENT_PERMISSIONS, offline = true, retried = false))
    }

    @Test fun `오프라인 한국어 모델이 없다는 기억은 정해진 기간만 쓴다 (첫 말 앞부분이 잘리지 않게)`() {
        val now = 1_000_000L
        assertTrue(!SttMapping.offlineMissingRemembered(0, now))                                  // 기록 없음
        assertTrue(SttMapping.offlineMissingRemembered(now + SttMapping.OFFLINE_MISSING_REMEMBER_MS, now))
        assertTrue(!SttMapping.offlineMissingRemembered(now - 1, now))                            // 기간 지남 → 다시 오프라인 시도
        assertEquals(7L * 24 * 60 * 60 * 1000, SttMapping.OFFLINE_MISSING_REMEMBER_MS)
    }

    @Test fun `인식기 언어 목록에서 한국어를 알아본다`() {
        assertTrue(SttMapping.hasKorean(listOf("en-US", "ko-KR")))
        assertTrue(SttMapping.hasKorean(listOf("ko_KR")))
        assertTrue(SttMapping.hasKorean(listOf(" KO ")))
        assertTrue(!SttMapping.hasKorean(listOf("en-US", "ja-JP", "kok-IN")))                     // 콘칸어(kok)는 한국어가 아님
        assertTrue(!SttMapping.hasKorean(emptyList()))
        assertTrue(!SttMapping.hasKorean(null))
    }

    @Test fun `재시도는 1회만 하고 권한 문제는 하지 않는다`() {
        assertTrue(SttMapping.shouldRetry(SttError.NETWORK, false))
        assertTrue(!SttMapping.shouldRetry(SttError.NETWORK, true))
        assertTrue(!SttMapping.shouldRetry(SttError.PERMISSION, false))
    }

    @Test fun `인식 후보 중 신뢰도 높은 것을 고른다`() {
        assertEquals("아메리카노", SttMapping.best(listOf("아메리카 노", "아메리카노"), floatArrayOf(0.4f, 0.9f)))
        assertEquals("첫째", SttMapping.best(listOf("첫째", "둘째"), null))
        assertEquals("둘째", SttMapping.best(listOf(" ", "둘째"), floatArrayOf(0.9f, 0.1f)))
        assertNull(SttMapping.best(listOf("", " "), null))
        assertNull(SttMapping.best(emptyList(), null))
    }

    @Test fun `에코 방지는 소리가 끝난 뒤 300ms 기다리게 한다`() {
        var now = 1000L
        val g = EchoGuard({ now }, 300)
        assertEquals(0, g.waitMs())
        g.speakingStarted(); assertTrue(g.waitMs() > 0)
        now = 2000; g.speakingEnded()
        now = 2100; assertEquals(200, g.waitMs())
        now = 2300; assertEquals(0, g.waitMs())
    }

    @Test fun `읽고 있지 않을 때 소리를 멈추면 마이크를 기다리게 하지 않는다 (버튼 누르자마자 한 말이 잘리지 않게)`() {
        var now = 1000L
        val g = EchoGuard({ now }, 300)
        g.speakingStopped(); assertEquals(0, g.waitMs())                  // 조용할 때 멈춤 → 바로 듣기
        g.speakingStarted(); now = 1500; g.speakingStopped()              // 읽다가 멈춤 → 울림이 사라질 때까지
        now = 1600; assertEquals(200, g.waitMs())
        now = 1800; assertEquals(0, g.waitMs())
    }

    @Test fun `긴 안내를 문장 단위로 나눈다`() {
        assertEquals(listOf("화면 왼쪽 위 깜빡이는 아메리카노 버튼을 누르세요"), SpeechSplit.split("화면 왼쪽 위 깜빡이는 아메리카노 버튼을 누르세요"))
        assertEquals(2, SpeechSplit.split("안내창이 떴어요. 확인 버튼을 눌러 주세요. 천천히 하셔도 돼요.").size)
        assertEquals(listOf("네. 좋아요"), SpeechSplit.split("네. 좋아요"))                 // 짧은 조각은 합친다
        assertEquals(listOf("가"), SpeechSplit.split("  가 "))
    }
}
