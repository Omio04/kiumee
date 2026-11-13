package kr.kiosklens.vision

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.vision.ocr.MlKitOcrEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Korean ML Kit on rasterized browser screenshots, not DOM-derived OCR. */
@RunWith(AndroidJUnit4::class)
class HandheldKioskTest {
    @Test fun measureHandheldOrderSequence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        assumeTrue("Run vision/tools/capture-kiosk.cjs first", context.assets.list("")!!.contains("manifest.json"))
        val manifest = JSONObject(context.assets.open("manifest.json").bufferedReader().use { it.readText() })
        val frames = manifest.getJSONArray("frames")
        val tracker = ScreenTracker()
        val interpreter = ScreenInterpreter()
        val results = JSONArray()
        val engine = MlKitOcrEngine()
        try {
            for (index in 0 until frames.length()) {
                val frameInfo = frames.getJSONObject(index)
                val bitmap = context.assets.open(frameInfo.getString("file")).use { BitmapFactory.decodeStream(it) }
                val frame = object : CameraFrame {
                    override val frameId = index.toLong()
                    override val timestampMs = frameId * 333
                    override val rotationDegrees = 0
                    override val width = bitmap.width
                    override val height = bitmap.height
                    override val image = bitmap
                    override fun close() = bitmap.recycle()
                }
                val started = System.nanoTime()
                val lines = try { engine.read(frame) } finally { frame.close() }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val clean = Elements.filter(lines)
                val update = tracker.update(clean, index.toLong(), index * 333L)
                val result = JSONObject(frameInfo.toString())
                    .put("ocrMs", elapsed)
                    .put("scene", interpreter.classifyLines(clean) ?: JSONObject.NULL)
                    .put("changed", update?.changed ?: false)
                    .put("snapshot", update?.snapshot?.let { JSONObject(SnapshotJson.encode(it)) } ?: JSONObject.NULL)
                // These are offline, synthetic kiosk screens only. Production never logs OCR text.
                result.put("lines", JSONArray().also { array -> lines.forEach { line ->
                    array.put(JSONObject().put("text", line.text).put("confidence", line.confidence)
                        .put("blockId", line.blockId)
                        .put("left", line.rect.left).put("top", line.rect.top)
                        .put("right", line.rect.right).put("bottom", line.rect.bottom))
                } })
                results.put(result)
            }
        } finally { engine.close() }
        val report = File(context.getExternalFilesDir(null), "handheld-results.json")
        report.writeText(JSONObject().put("sourceSha256", manifest.getString("sourceSha256"))
            .put("frames", results).toString(2))
        println("HANDHELD_REPORT=${report.absolutePath}")
        assertEquals(frames.length(), results.length())
    }
}
