package kr.kiosklens.vision

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Recorded Tesseract observations, not DOM labels or measured Android ML Kit output. */
class KioskOcrReplayTest {
    private fun observations(case: JsonObject) = case.getValue("lines").jsonArray.map { raw ->
        val item=raw.jsonObject
        fun number(key:String)=item.getValue(key).jsonPrimitive.float
        OcrLine(item.getValue("text").jsonPrimitive.content,
            NormRect(number("left"),number("top"),number("right"),number("bottom")),
            number("confidence")/100f)
    }

    @Test fun recordedScenesAreCorrectOrUnknownWithoutRewritingTheOcr() {
        val cases=Json.parseToJsonElement(File("testdata/kiosk/ocr-replay.json").readText()).jsonArray
        val interpreter=ScreenInterpreter()
        var recognized=0
        for(raw in cases) {
            val case=raw.jsonObject;val name=case.getValue("name").jsonPrimitive.content
            val expected=case.getValue("expected").jsonPrimitive.content
            val input=observations(case);val clean=Elements.filter(input)
            val scene=interpreter.classifyLines(clean)
            if(scene!=null){assertEquals(name,expected,scene);recognized++}
            assertTrue("$name must not replace labels with a menu dictionary",clean.all { line->
                input.any { Elements.key(it.text)==Elements.key(line.text) }
            })
        }
        assertTrue("Strongly observed review, payment, card stages and completion must be recognized",recognized>=5)
    }

    @Test fun partialForegroundWithApprovalOrDisposableEvidenceHasNoBackgroundAction() {
        val cases=Json.parseToJsonElement(File("testdata/kiosk/ocr-replay.json").readText()).jsonArray
        for(name in listOf("06-disposable.png","11-receipt.png")) {
            val case=cases.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content==name }
            val clean=Elements.filter(observations(case))
            assertNull(name,ScreenInterpreter().classifyLines(clean))
            assertTrue(name,ElementIds().assign(name,clean).none { it.kind==ElementKind.BUTTON_LIKE })
        }
    }
}
