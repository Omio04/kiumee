package kr.kiosklens.vision.ocr

import kotlin.math.*
import kr.kiosklens.vision.*

/** Luminance in the same rotation-corrected coordinate system as OCR. */
interface LumaImage {
    val width: Int
    val height: Int
    fun at(x: Int, y: Int): Int
}

/** Conservative bright X-on-dark-circle detector; never invents a control from text alone. */
class CloseIconDetector {
    fun detect(image: LumaImage, title: NormRect): OcrLine? {
        if (!title.isValid || image.width < 32 || image.height < 32) return null
        val h = title.height * image.height
        if (h < 5) return null
        val startX = (title.right * image.width + h * 0.35f).toInt()
        val endX = (image.width * 0.97f).toInt()
        val startY = ((title.centerY * image.height) - h * 1.6f).toInt().coerceAtLeast(0)
        val endY = ((title.centerY * image.height) + h * 1.6f).toInt().coerceAtMost(image.height - 1)
        var best: NormRect? = null
        var bestScore = 45f
        for (disk in darkDisks(image, startX, endX, startY, endY, h)) {
            // The box uses an exclusive right/bottom edge; samples refer to pixel centres.
            val pixelOffset = max(1, (h / 20).toInt()) / 2f
            val x = disk.centerX * image.width - pixelOffset
            val y = disk.centerY * image.height - pixelOffset
            for (scale in listOf(0.90f, 1.00f, 1.10f)) {
                val radius = (disk.width * image.width + disk.height * image.height) / 4 * scale
                if (x - radius * 1.3f < 0 || x + radius * 1.3f >= image.width ||
                    y - radius * 1.3f < 0 || y + radius * 1.3f >= image.height) continue
                for (tilt in listOf(-15, 0, 15)) for (armScale in listOf(1f, 0.65f, 0.5f)) {
                    val score = score(image, x, y, radius, tilt, armScale)
                    if (score > bestScore) {
                        bestScore = score
                        best = disk
                    }
                }
            }
        }
        return best?.let { OcrLine("×", it, (bestScore / 140f).coerceIn(0.65f, 0.95f),
            kindHint = ElementKind.BUTTON_LIKE, source = ObservationSource.VERIFIED_CLOSE_ICON) }
    }

    private fun darkDisks(image: LumaImage, left: Int, right: Int, top: Int, bottom: Int,
        titleHeight: Float): List<NormRect> {
        if (right <= left || bottom <= top) return emptyList()
        val stride = max(1, (titleHeight / 20).toInt())
        val columns = (right - left) / stride + 1
        val rows = (bottom - top) / stride + 1
        val dark = BooleanArray(columns * rows) { index ->
            image.at(left + index % columns * stride, top + index / columns * stride) < 105
        }
        val visited = BooleanArray(dark.size)
        val queue = IntArray(dark.size)
        val disks = mutableListOf<NormRect>()
        dark.indices.forEach { seed ->
            if (!dark[seed] || visited[seed]) return@forEach
            var head = 0
            var tail = 1
            queue[0] = seed
            visited[seed] = true
            var minX = seed % columns
            var maxX = minX
            var minY = seed / columns
            var maxY = minY
            while (head < tail) {
                val index = queue[head++]
                val x = index % columns
                val y = index / columns
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until columns || ny !in 0 until rows) continue
                    val neighbor = ny * columns + nx
                    if (dark[neighbor] && !visited[neighbor]) {
                        visited[neighbor] = true
                        queue[tail++] = neighbor
                    }
                }
            }
            val width = (maxX - minX + 1) * stride.toFloat()
            val height = (maxY - minY + 1) * stride.toFloat()
            val fill = tail.toFloat() / ((maxX - minX + 1) * (maxY - minY + 1))
            if (width / height in 0.65f..1.5f && width / titleHeight in 0.9f..3.5f &&
                height / titleHeight in 0.9f..3.5f && fill in 0.35f..0.95f) {
                disks += NormRect((left + minX * stride).toFloat() / image.width,
                    (top + minY * stride).toFloat() / image.height,
                    (left + (maxX + 1) * stride).toFloat() / image.width,
                    (top + (maxY + 1) * stride).toFloat() / image.height)
            }
        }
        return disks
    }

    private fun score(image: LumaImage, x: Float, y: Float, r: Float, tilt: Int, armScale: Float): Float {
        fun sample(angle: Double, distance: Float): Float {
            val px = (x + cos(angle) * r * distance).roundToInt()
            val py = (y + sin(angle) * r * distance).roundToInt()
            return image.at(px, py).toFloat()
        }
        val rotation = tilt * PI / 180
        val darkSamples = (0..3).map { sample(rotation + it * PI / 2, 0.55f) }
        val dark = darkSamples.average().toFloat()
        if (darkSamples.any { it > 145f }) return 0f
        // A short X needs dark gaps close to its centre too; otherwise a small bright glare could match.
        if (armScale < 1f && (0..3).any { sample(rotation + it * PI / 2, 0.35f * armScale / 0.65f) > 145f }) return 0f
        if (image.at(x.roundToInt(), y.roundToInt()) - dark < 35f) return 0f
        val arms = (0..3).map { arm ->
            val angle = rotation + PI / 4 + arm * PI / 2
            listOf(0.25f, 0.40f, 0.55f, 0.70f).map { sample(angle, it * armScale) }.average().toFloat()
        }
        // Every arm must have positive contrast: plus signs, rings and glare must not pass.
        if (arms.any { it - dark < 30f }) return 0f
        val outerArms = (0..3).map { arm ->
            val angle = rotation + PI / 4 + arm * PI / 2
            sample(angle, 0.70f * armScale)
        }
        if (outerArms.any { it - dark < 25f }) return 0f
        val outside = (0..3).map { sample(rotation + it * PI / 2, 1.25f) }.average().toFloat()
        val ringSamples = (0..3).map { sample(rotation + it * PI / 2, 0.88f) }
        // All four cardinal points must still be inside the dark disk. This rejects
        // off-centre matches whose "arms" are actually the bright area outside a + button.
        if (ringSamples.any { it > 155f || it - dark > 55f }) return 0f
        if (outside - ringSamples.average() < 20f) return 0f
        return min(arms.average().toFloat() - dark, arms.min() - dark + 20f)
    }
}
