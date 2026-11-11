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

    @Test fun `온디바이스에서 한국어 모델이 없으면 재시도를 쓰지 않고 일반 인식기로 바꾼다`() {
        assertEquals(SttNext.FALL_BACK_TO_STANDARD, SttMapping.next(SttMapping.ERROR_LANGUAGE_UNAVAILABLE, onDevice = true, retried = false))
        assertEquals(SttNext.FALL_BACK_TO_STANDARD, SttMapping.next(SttMapping.ERROR_LANGUAGE_NOT_SUPPORTED, onDevice = true, retried = false))
        assertEquals(SttNext.FALL_BACK_TO_STANDARD, SttMapping.next(SttMapping.ERROR_LANGUAGE_UNAVAILABLE, onDevice = true, retried = true))
    }

    @Test fun `일반 인식기와 다른 오류는 기존 재시도 규칙을 따른다`() {
        assertEquals(SttNext.RETRY, SttMapping.next(SttMapping.ERROR_LANGUAGE_UNAVAILABLE, onDevice = false, retried = false))
        assertEquals(SttNext.FINISH, SttMapping.next(SttMapping.ERROR_LANGUAGE_UNAVAILABLE, onDevice = false, retried = true))
        assertEquals(SttNext.RETRY, SttMapping.next(SttMapping.ERROR_NETWORK, onDevice = true, retried = false))
        assertEquals(SttNext.FINISH, SttMapping.next(SttMapping.ERROR_NETWORK, onDevice = true, retried = true))
        assertEquals(SttNext.FINISH, SttMapping.next(SttMapping.ERROR_INSUFFICIENT_PERMISSIONS, onDevice = true, retried = false))
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

    @Test fun `긴 안내를 문장 단위로 나눈다`() {
        assertEquals(listOf("화면 왼쪽 위 깜빡이는 아메리카노 버튼을 누르세요"), SpeechSplit.split("화면 왼쪽 위 깜빡이는 아메리카노 버튼을 누르세요"))
        assertEquals(2, SpeechSplit.split("안내창이 떴어요. 확인 버튼을 눌러 주세요. 천천히 하셔도 돼요.").size)
        assertEquals(listOf("네. 좋아요"), SpeechSplit.split("네. 좋아요"))                 // 짧은 조각은 합친다
        assertEquals(listOf("가"), SpeechSplit.split("  가 "))
    }
}
