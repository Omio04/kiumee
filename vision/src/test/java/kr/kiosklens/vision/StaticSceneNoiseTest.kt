package kr.kiosklens.vision

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 같은 화면을 오래 비추기만 해도 화면 번호가 계속 바뀌던 문제 (2026-11-14 실기기, 진단 로그 KL_TRACK).
 *
 * 실기기 기록에서 본 잡음을 그대로 흉내 낸다:
 *  1) 긴 영어 부제가 프레임마다 뒤쪽만 다른 쓰레기 글자로 읽힌다 ("pleaseselectyourlanguage표ruttt1iti금" / "…폐륙m표rutt61i" / "…미춘rutti")
 *  2) 화면과 같이 찍힌 PC 키보드의 키 글자(Backspace · Home · Insert · Del …)가 읽혔다 안 읽혔다 한다
 *  3) 진짜 글자도 가끔 한두 개 빠진다
 * 정지 화면에는 새 화면이 한 번도 없어야 하고(특이도), 진짜 다른 화면으로 바뀌면 놓치면 안 된다(민감도).
 * 실제 손떨림 녹화 재생(HandheldReplayTest)이 민감도의 다른 한 축을 지킨다.
 */
class StaticSceneNoiseTest {
    private val keycaps = listOf("Backspace", "Home", "Insert", "Del", "PrtSc", "Shift", "Alt", "Enter", "End", "PgUp")
    private val garble = "표춘폐륙미금품주급ruttt1iti".toList()

    private fun line(text: String, x: Float, y: Float) = OcrLine(text, NormRect(x, y, x + 0.18f, y + 0.05f), 0.9f)

    /** 한 프레임: 화면의 진짜 글자(가끔 빠짐) + 뒤가 매번 다르게 깨진 부제 + 읽히다 마는 키보드 글자 */
    private fun frame(random: Random, title: String, subtitle: String, stable: List<String>, keyP: Float = 0.35f, drop: Float = 0.15f): List<OcrLine> {
        val lines = mutableListOf(line(title, 0.1f, 0.05f))
        val tail = (1..3 + random.nextInt(6)).map { garble[random.nextInt(garble.size)] }.joinToString("")
        lines += line(subtitle + tail, 0.1f, 0.12f)
        stable.forEachIndexed { i, text -> if (random.nextFloat() > drop) lines += line(text, 0.05f + (i % 3) * 0.3f, 0.3f + (i / 3) * 0.15f) }
        keycaps.forEach { key -> if (random.nextFloat() < keyP) lines += line(key, 0.05f + random.nextFloat() * 0.8f, 0.9f) }
        return lines
    }

    private val languageScreen = listOf("한국어", "English", "일본어", "중국어", "영어")
    private val airlineScreen = listOf("대한항공", "아시아나항공", "제주항공", "진에어", "티웨이항공", "에어부산")

    private fun run(seed: Long, frames: Int, scene: (Int) -> Triple<String, String, List<String>>): List<ScreenUpdate> {
        val tracker = ScreenTracker()
        val random = Random(seed)
        return (0 until frames).mapNotNull { i ->
            val (title, subtitle, stable) = scene(i)
            tracker.update(frame(random, title, subtitle, stable), i.toLong(), i * 333L)
        }
    }

    @Test fun aStaticScreenNeverBecomesANewScreenFromNoiseAlone() {
        for (seed in 1L..12L) {
            val updates = run(seed, 240) { Triple("셀프 체크인 Self Check-in", "Please select your language", languageScreen) }
            val ids = updates.map { it.snapshot.screenId }.toSet()
            assertEquals("seed $seed: 정지 화면인데 화면 번호가 $ids 로 바뀜", setOf("s1"), ids)
            assertTrue("seed $seed: 새 화면 알림이 나감", updates.none { it.changed })
        }
    }

    @Test fun aRealScreenChangeIsStillCaughtQuicklyAndOnlyOnce() {
        for (seed in 1L..12L) {
            val switchAt = 80
            val updates = run(seed, 240) { i ->
                if (i < switchAt) Triple("셀프 체크인 Self Check-in", "Please select your language", languageScreen)
                else Triple("셀프 체크인 Self Check-in", "Please select an airline", airlineScreen)
            }
            val changes = updates.filter { it.changed }
            assertEquals("seed $seed: 진짜 전환은 정확히 한 번 알려야 함 (실제 ${changes.size}번)", 1, changes.size)
            val caughtAt = updates.indexOf(changes.single())
            assertEquals("s2", changes.single().snapshot.screenId)
            val delay = changes.single().snapshot.frameId - switchAt
            assertTrue("seed $seed: 전환 후 ${delay}프레임 만에 잡음 (8 이내여야 함)", delay in 0..8)
            assertTrue(caughtAt >= 0)
        }
    }

    /**
     * 한 지점이 아니라 잡음 강도를 넓게 바꿔도 성립하는지 (키보드 글자가 읽힐 확률 × 진짜 글자가 빠질 확률, 시드 40개씩).
     * 수정 전 알고리즘(가장 큰 프레임을 기준으로 키움)은 이 9개 설정 모두 정지 화면 오탐이 10~40/40이었다.
     */
    @Test fun holdsAcrossNoiseLevels() {
        for (keyP in listOf(0.2f, 0.35f, 0.5f)) for (drop in listOf(0.05f, 0.15f, 0.30f)) {
            var falseSeeds = 0
            var caught = 0
            for (seed in 1L..40L) {
                val still = ScreenTracker(); val r1 = Random(seed)
                val flips = (0 until 240).count { i ->
                    still.update(frame(r1, "셀프 체크인 Self Check-in", "Please select your language", languageScreen, keyP, drop), i.toLong(), 0)?.changed == true
                }
                if (flips > 0) falseSeeds++
                val moving = ScreenTracker(); val r2 = Random(seed + 1000)
                val changes = (0 until 240).mapNotNull { i ->
                    val u = if (i < 80) moving.update(frame(r2, "셀프 체크인 Self Check-in", "Please select your language", languageScreen, keyP, drop), i.toLong(), 0)
                    else moving.update(frame(r2, "셀프 체크인 Self Check-in", "Please select an airline", airlineScreen, keyP, drop), i.toLong(), 0)
                    u?.takeIf { it.changed }?.snapshot?.frameId
                }
                if (changes.size == 1 && changes[0] - 80 in 0..8) caught++
            }
            assertTrue("keyP=$keyP drop=$drop: 정지 화면 오탐 $falseSeeds/40 (5 이하여야 함)", falseSeeds <= 5)
            assertTrue("keyP=$keyP drop=$drop: 진짜 전환을 깔끔히 잡은 수 $caught/40 (20 이상이어야 함)", caught >= 20)
        }
    }
}
