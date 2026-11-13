package kr.kiosklens.app

import kr.kiosklens.app.camera.ExposureTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 스스로 빛나는 키오스크 화면이 하얗게 날아가지 않게 노출을 낮추는 칸 계산 (2026-11-12) */
class ExposureTuningTest {
    @Test fun `가장 가까운 노출 보정 칸을 고른다`() {
        assertEquals("에뮬레이터: 한 칸 0.5EV → 한 칸", -1, ExposureTuning.index(-0.5f, 0.5f, -6, 6))
        assertEquals("한 칸 1/6EV → 세 칸", -3, ExposureTuning.index(-0.5f, 1f / 6, -24, 24))
        assertEquals("한 칸 1/3EV → 가까운 쪽 한 칸", -1, ExposureTuning.index(-0.5f, 1f / 3, -6, 6))
    }

    @Test fun `기기가 받는 범위 안에서만 고친다`() {
        assertEquals("노출 보정을 못 받는 기기", 0, ExposureTuning.index(-0.5f, 0.5f, 0, 0))
        assertEquals("칸 크기가 이상하면 그대로", 0, ExposureTuning.index(-0.5f, 0f, -6, 6))
        assertEquals("범위 밖이면 끝 칸", -2, ExposureTuning.index(-3f, 0.5f, -2, 2))
    }

    @Test fun `낮추기만 하고 조금만`() {
        assertTrue(ExposureTuning.TARGET_EV < 0f && ExposureTuning.TARGET_EV >= -1f)
        assertTrue(ExposureTuning.METERING_SIZE in 0.3f..0.9f)
    }
}
