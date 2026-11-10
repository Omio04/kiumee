package kr.kiosklens.vision

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SnapshotJsonTest {
    @Test fun upstreamContractSnapshotsRoundTripWithAllMetadata() {
        listOf("request_first.json", "request_after_screen_change.json", "request_answer.json").forEach { name ->
            val value = checkNotNull(javaClass.getResource("/contract/$name")).readText()
            val snapshot = Json.parseToJsonElement(value).jsonObject.getValue("snapshot")
            val decoded = SnapshotJson.decode(snapshot.toString())
            assertEquals(decoded, SnapshotJson.decode(SnapshotJson.encode(decoded)))
            val actual = Json.parseToJsonElement(SnapshotJson.encode(decoded)).jsonObject
            assertEquals(snapshot.jsonObject, actual)
        }
    }

    @Test fun oldFixtureMetadataDefaultsAreExplicit() {
        val snapshot = SnapshotJson.decode("""{"screenId":"old","elements":[]}""")
        assertEquals(0L, snapshot.frameId)
        assertEquals(0L, snapshot.timestampMs)
        assertEquals(2, snapshot.stableFrames)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnstableExport() {
        SnapshotJson.encode(ScreenSnapshot("s", 1, 1, emptyList(), 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateIds() {
        val element = ScreenElement("e1", "담기", menuLines[0].rect, 0.9f, ElementKind.BUTTON_LIKE)
        SnapshotJson.encode(ScreenSnapshot("s", 1, 1, listOf(element, element), 2))
    }
}
