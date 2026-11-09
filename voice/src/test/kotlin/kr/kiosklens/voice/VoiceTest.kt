package kr.kiosklens.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.kiosklens.core.model.SttError
import kr.kiosklens.core.model.SttEvent
import kr.kiosklens.core.port.SpeechInput
import kr.kiosklens.core.port.SpeechOutput
import kr.kiosklens.voice.fake.FakeSpeechInput
import kr.kiosklens.voice.fake.FakeSpeechOutput
import kr.kiosklens.voice.stt.SttMapping
import kr.kiosklens.voice.tts.SpeechSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SpeechInput 계약 (Fake 와 실구현이 둘 다 상속). 실구현은 기기에서만 돌릴 수 있어 여기서는 Fake 로 확인한다. */
abstract class SpeechInputContractSpec {
    protected abstract fun ok(): SpeechInput
    protected abstract fun failing(): SpeechInput

    @Test fun `정상이면 Final 하나가 나오고 끝난다`() = runBlocking {
        val ev = ok().start().toList()
        assertEquals(1, ev.count { it is SttEvent.Final })
        assertTrue(ev.last() is SttEvent.Final)
        assertTrue(ev.dropLast(1).all { it is SttEvent.Partial })
    }

    @Test fun `실패는 예외 없이 Error 하나로 끝난다`() = runBlocking {
        val ev = failing().start().toList()
        assertEquals(1, ev.size)
        assertTrue(ev.single() is SttEvent.Error)
    }

    @Test fun `취소한 뒤 다시 start 할 수 있다`() = runBlocking {
        val inp = ok()
        val job = launch(Dispatchers.Default) { inp.start().collect { delay(10_000) } }
        delay(30); job.cancelAndJoin(); inp.stop()
        assertTrue(inp.start().toList().last() is SttEvent.Final)
    }
}

class FakeSpeechInputContractTest : SpeechInputContractSpec() {
    override fun ok(): SpeechInput = FakeSpeechInput(List(5) { "아메리카노 주세요" }, delayMs = 5)
    override fun failing(): SpeechInput = FakeSpeechInput(emptyList())
}

abstract class SpeechOutputContractSpec {
    protected abstract fun make(): SpeechOutput

    @Test fun `speak는 재생이 끝난 뒤 돌아온다`() = runBlocking {
        val t0 = System.currentTimeMillis()
        make().speak("안녕하세요")
        assertTrue(System.currentTimeMillis() - t0 >= 80)
    }

    @Test fun `취소하면 즉시 멈춘다`() = runBlocking {
        val out = make()
        val job = launch(Dispatchers.Default) { out.speak("아주 긴 안내 문장입니다") }
        delay(20)
        val t0 = System.currentTimeMillis()
        job.cancelAndJoin()
        assertTrue(System.currentTimeMillis() - t0 < 70)
    }
}

class FakeSpeechOutputContractTest : SpeechOutputContractSpec() {
    override fun make(): SpeechOutput = FakeSpeechOutput(readMs = 100)
}

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
