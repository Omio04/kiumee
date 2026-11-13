package kr.kiosklens.voice

import kr.kiosklens.voice.barge.BargeInGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInGateTest {
    private fun frames(gate: BargeInGate, level: Float, n: Int): Int {
        var fired = 0
        repeat(n) { if (gate.onFrame(level)) fired++ }
        return fired
    }

    @Test fun `앱 자기 소리(에코)만 계속 들리면 절대 울리지 않는다`() {
        val g = BargeInGate()
        assertEquals(0, frames(g, 2000f, 400))                  // 8초 동안 일정한 에코
    }

    @Test fun `에코 위에 사람 목소리가 이어지면 한 번만 울린다`() {
        val g = BargeInGate()
        frames(g, 1500f, 30)                                    // 에코
        assertEquals(1, frames(g, 6000f, 40))                   // 사람이 폰 가까이서 말함
    }

    @Test fun `짧은 잡음 한 번은 사람 말이 아니다`() {
        val g = BargeInGate()
        frames(g, 1500f, 30)
        assertEquals(0, frames(g, 8000f, 5))                    // 0.1초 쾅
        assertEquals(0, frames(g, 1500f, 30))
    }

    @Test fun `말 사이의 짧은 끊김은 이어서 센다`() {
        val g = BargeInGate()
        frames(g, 1500f, 30)
        var fired = 0
        repeat(3) { fired += frames(g, 6000f, 4); fired += frames(g, 1500f, 2) }   // 4프레임 말 + 2프레임 쉼
        assertEquals(1, fired)
    }

    @Test fun `끊김이 길면 처음부터 센다`() {
        val g = BargeInGate()
        frames(g, 1500f, 30)
        assertEquals(0, frames(g, 6000f, 8))
        assertEquals(0, frames(g, 1500f, 10))                   // 길게 쉼 → 리셋
        assertEquals(0, frames(g, 6000f, 8))
    }

    @Test fun `아주 조용한 소리는 에코보다 커도 말이 아니다`() {
        val g = BargeInGate()
        frames(g, 100f, 30)                                      // 거의 무음
        assertEquals(0, frames(g, 700f, 100))                    // 문턱(900) 미만
    }

    @Test fun `기준은 에코 크기에 맞게 올라간다`() {
        val quiet = BargeInGate(); frames(quiet, 300f, 20)
        val loud = BargeInGate(); frames(loud, 4000f, 20)
        assertTrue(loud.threshold > quiet.threshold)
        assertEquals(900f, quiet.threshold, 0.01f)               // 문턱이 바닥
        assertEquals(8000f, loud.threshold, 0.01f)               // 에코 × 2
        assertEquals(0, frames(loud, 6000f, 100))                // 시끄러운 에코 속에서 6000은 사람 말이 아니다
    }

    @Test fun `사람이 읽기 시작하자마자 말해도 기준이 부풀지 않는다`() {
        val g = BargeInGate()
        repeat(15) { g.onFrame(if (it < 10) 1500f else 7000f) }  // 에코 10프레임 + 사람 5프레임이 섞인 보정 구간
        assertTrue(g.threshold < 4000f)                          // 가장 조용한 절반만 쓰므로 에코 수준
        assertEquals(1, frames(g, 7000f, 20))
    }

    @Test fun `울린 뒤에는 reset 전까지 다시 울리지 않고 reset하면 다시 센다`() {
        val g = BargeInGate()
        frames(g, 1500f, 30)
        assertEquals(1, frames(g, 6000f, 40))
        assertEquals(0, frames(g, 6000f, 40))
        g.reset()
        assertFalse(g.calibrated)
        frames(g, 1500f, 30)
        assertEquals(1, frames(g, 6000f, 40))
    }

    @Test fun `보정 구간에는 아무리 커도 울리지 않는다`() {
        val g = BargeInGate()
        assertEquals(0, frames(g, 20000f, 15))
    }
}

class EchoGuardInterruptTest {
    @Test fun `사람이 끊으면 울림 대기 없이 바로 마이크를 켠다`() {
        var now = 1_000L
        val g = EchoGuard({ now }, quietMs = 300)
        g.speakingStarted()
        g.speakingInterrupted()
        assertEquals(0L, g.waitMs())                             // 기다리면 사람이 말한 앞부분이 잘린다
        assertFalse(g.isSpeaking())
    }

    @Test fun `끝까지 읽은 뒤에는 예전처럼 울림 대기`() {
        var now = 1_000L
        val g = EchoGuard({ now }, quietMs = 300)
        g.speakingStarted()
        g.speakingEnded()
        assertEquals(300L, g.waitMs())
        now += 300
        assertEquals(0L, g.waitMs())
    }
}
