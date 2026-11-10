package kr.kiosklens.vision

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kr.kiosklens.vision.ocr.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

class CloseIconDetectorTest {
    private fun synthetic(symbol: String) = object : LumaImage {
        override val width = 400
        override val height = 600
        override fun at(x: Int, y: Int): Int {
            val dx = x - 335
            val dy = y - 150
            if (dx * dx + dy * dy > 25 * 25) return 220
            val bright = when (symbol) {
                "x" -> abs(abs(dx) - abs(dy)) <= 3 && abs(dx) <= 14
                "+" -> (abs(dx) <= 3 || abs(dy) <= 3) && abs(dx) + abs(dy) < 20
                "glare" -> dx * dx + dy * dy < 14 * 14
                else -> false
            }
            return if (bright) 220 else 30
        }
    }
    private val title = NormRect(0.4f, 0.23f, 0.64f, 0.27f)

    @Test fun verifiesAnActualDiagonalCross() {
        val close = CloseIconDetector().detect(synthetic("x"), title)
        assertNotNull(close)
        assertEquals("×", close!!.text)
        assertEquals(ObservationSource.VERIFIED_CLOSE_ICON, close.source)
        assertTrue(close.rect.centerX in 0.79f..0.88f)
        assertTrue(close.rect.centerY in 0.23f..0.27f)
    }

    @Test fun rejectsPlusCircleAndGlare() {
        listOf("+", "glare", "circle").forEach {
            assertNull(it, CloseIconDetector().detect(synthetic(it), title))
        }
    }

    @Test fun detectsCloseInProvidedStampScreenshotFromPixelsWithHandLabelledTitle() {
        val file = File(".local/mega/10-loyalty.luma")
        assumeTrue("Local user screenshot is not committed to the repository", file.isFile)
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val imageWidth = bytes.int
        val imageHeight = bytes.int
        val image = object : LumaImage {
            override val width = imageWidth
            override val height = imageHeight
            override fun at(x: Int, y: Int) = bytes.get(8 + y * width + x).toInt() and 255
        }
        val close = CloseIconDetector().detect(image, NormRect(0.42f, 0.244f, 0.64f, 0.265f))
        assertNotNull("Visible X must be detected from pixel contrast", close)
        assertTrue(close!!.rect.centerX in 0.82f..0.85f)
        assertTrue(close.rect.centerY in 0.245f..0.27f)
        val expected = NormRect(0.80f, 0.238f, 0.872f, 0.274f)
        val overlapWidth = (minOf(expected.right, close.rect.right) - maxOf(expected.left, close.rect.left)).coerceAtLeast(0f)
        val overlapHeight = (minOf(expected.bottom, close.rect.bottom) - maxOf(expected.top, close.rect.top)).coerceAtLeast(0f)
        val intersection = overlapWidth * overlapHeight
        val union = expected.width * expected.height + close.rect.width * close.rect.height - intersection
        assertTrue("Detected box should overlap the actual X disk, not a bright edge", intersection / union >= 0.55f)
        println("Provided stamp screenshot: verified close icon at ${close.rect}")
    }
}
