package kr.kiosklens.vision

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kr.kiosklens.core.model.GuideAction
import kr.kiosklens.core.model.GuideResponse
import kr.kiosklens.domain.session.*

/** Actual Korean ML Kit observations from the original kiosk, including low-confidence mistakes. */
class HandheldReplayTest {
    private val frames = Json.parseToJsonElement(File("testdata/kiosk/handheld-mlkit.json").readText())
        .jsonObject.getValue("frames").jsonArray.map { it.jsonObject }

    private fun lines(frame: JsonObject) = frame.getValue("lines").jsonArray.map { item ->
        val line = item.jsonObject
        fun num(key: String) = line.getValue(key).jsonPrimitive.float
        OcrLine(line.getValue("text").jsonPrimitive.content,
            NormRect(num("left"),num("top"),num("right"),num("bottom")), num("confidence"),
            blockId = line["blockId"]?.jsonPrimitive?.intOrNull)
    }

    @Test fun handheldSequenceDoesNotInventScreenChanges() {
        val tracker = ScreenTracker()
        var lastStage: String? = null
        val reached = mutableSetOf<String>()
        var falseChanges = 0
        val report = buildJsonArray {
            frames.forEachIndexed { index, frame ->
                val clean = Elements.filter(lines(frame))
                val update = tracker.update(clean, index.toLong(), index * 333L)
                val stage = frame.getValue("stage").jsonPrimitive.content.removeSuffix("-selected")
                if (update != null) {
                    if (update.changed && lastStage == stage) falseChanges++
                    reached += stage
                    lastStage = stage
                }
                add(buildJsonObject {
                    frame.forEach { (key,value) -> if (key != "lines") put(key,value) }
                    put("scene", ScreenInterpreter().classifyLines(clean)?.let(::JsonPrimitive) ?: JsonNull)
                    put("changed", update?.changed ?: false)
                    put("snapshot", update?.snapshot?.let { Json.parseToJsonElement(SnapshotJson.encode(it)) } ?: JsonNull)
                })
            }
        }
        File("build/handheld-replay.json").writeText(buildJsonObject { put("frames",report) }.toString())
        assertEquals("Camera jitter must not advance the order", 0, falseChanges)
        assertEquals(11, reached.size)
    }

    @Test fun blurNeverClassifiesAnotherOrderStage() {
        val interpreter = ScreenInterpreter()
        frames.forEach { frame ->
            val scene = interpreter.classifyLines(Elements.filter(lines(frame)))
            if (scene != null) assertEquals(frame.getValue("file").jsonPrimitive.content,
                frame.getValue("expected").jsonPrimitive.content, scene)
        }
    }

    @Test fun actualOcrDrivesWholeOrderWithoutAnotherVoiceQuestion() =
        verifyOrder(frames, "handheld-guidance.json")

    @Test fun unseenShakesAndMotionBlurAlsoDriveTheWholeOrder() {
        val holdout = Json.parseToJsonElement(File("testdata/kiosk/handheld-holdout.json").readText())
            .jsonObject.getValue("frames").jsonArray.map { it.jsonObject }
        verifyOrder(holdout, "handheld-holdout-guidance.json")
    }

    private fun verifyOrder(observations: List<JsonObject>, guidance: String) {
        val steps = Json.parseToJsonElement(File("testdata/kiosk/$guidance").readText()).jsonArray
            .map { it.jsonObject }
        for (snapshotFirst in listOf(true, false)) {
            val tracker = ScreenTracker()
            val machine = SessionStateMachine()
            var state = SessionState()
            var count = 0
            var spoken = 0
            fun send(event: SessionEvent): List<SessionEffect> = machine.reduce(state,event)
                .also { state = it.next }.effects
            for ((index, frame) in observations.withIndex()) {
                val update = tracker.update(Elements.filter(lines(frame)),index.toLong(),index * 333L) ?: continue
                val snapshot = update.snapshot
                val events = mutableListOf<SessionEvent>(SessionEvent.SnapshotReceived(snapshot))
                if (update.changed) events += SessionEvent.ScreenChanged(snapshot.screenId)
                val effects = (if(snapshotFirst) events else events.reversed()).flatMap(::send).toMutableList()
                if (count == 0 && state.phase == Phase.IDLE) {
                    send(SessionEvent.StartPressed("handheld-order"))
                    effects += send(SessionEvent.SpeechRecognized(state.ticket,"아이스 아메리카노 한 잔 주세요"))
                }
                for (effect in effects.filterIsInstance<SessionEffect.RequestGuide>()) {
                    assertTrue("Unexpected repeated guidance", count < steps.size)
                    val step = steps[count]
                    val expected = step.getValue("request").jsonObject
                    assertEquals(step.getValue("stage").jsonPrimitive.content,
                        expected.getValue("snapshot").jsonObject.getValue("screenId").jsonPrimitive.content,
                        effect.request.snapshot.screenId)
                    assertEquals(if(count==0) "아이스 아메리카노 한 잔 주세요" else null,effect.request.utterance)
                    if (count>0) assertEquals("아이스 아메리카노 한 잔 주세요",effect.request.goal)
                    val response = step.getValue("response").jsonObject
                    val a = response.getValue("action").jsonObject
                    fun text(key: String) = a.getValue(key).jsonPrimitive.content
                    val action = when(text("type")) {
                        "TAP" -> GuideAction.Tap(text("targetId"),text("guideText"))
                        "WAIT" -> GuideAction.Wait(text("guideText"), a["targetIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
                        "DONE" -> GuideAction.Done(text("guideText"))
                        else -> error("Unverified action")
                    }
                    spoken += send(SessionEvent.GuideReceived(state.ticket,
                        GuideResponse(response.getValue("goal").jsonPrimitive.content,action)))
                        .filterIsInstance<SessionEffect.Speak>().size
                    send(SessionEvent.SpeechFinished(state.ticket))
                    count++
                }
            }
            assertEquals(11,count)
            assertEquals(11,spoken)
            assertEquals(Phase.IDLE,state.phase)
        }
    }

    private fun stage(name: String) = Elements.filter(lines(frames.first {
        it.getValue("stage").jsonPrimitive.content == name && it.getValue("name").jsonPrimitive.content == "clear"
    }))

    @Test fun closingOptionsGoingBackAndCancellingPaymentRemainRealTransitions() {
        val tracker = ScreenTracker()
        val route = listOf("menu","options","menu","options","cart","review","disposable","review",
            "payment","loyalty","payment","loyalty","card","payment","card","processing","receipt","done")
        route.forEachIndexed { index, name ->
            val clean = stage(name)
            assertNull("$name needs confirmation",tracker.update(clean))
            val update = checkNotNull(tracker.update(clean)) { name }
            assertEquals(name,index>0,update.changed)
            assertEquals("s${index+1}",update.snapshot.screenId)
        }
    }

    @Test fun coveredCameraAndSingleTransientDialogDoNotRestartTheOrder() {
        val tracker = ScreenTracker()
        val current = stage("review")
        tracker.update(current)
        val before = tracker.update(current)!!.snapshot.screenId
        repeat(5) {
            assertNull(tracker.update(emptyList()))
            assertNull(tracker.update(current))
            assertEquals(before,tracker.update(current)!!.snapshot.screenId)
        }
        assertNull(tracker.update(stage("disposable")))
        assertNull(tracker.update(current))
        val returned = tracker.update(current)!!
        assertFalse(returned.changed)
        assertEquals(before,returned.snapshot.screenId)
    }
}
