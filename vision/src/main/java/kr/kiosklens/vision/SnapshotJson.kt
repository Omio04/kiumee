package kr.kiosklens.vision

import kotlinx.serialization.json.*

/** Fixture/debug JSON only; production HTTP serialization remains the :agent responsibility. */
object SnapshotJson {
    private val json = Json { prettyPrint = true }

    fun encode(snapshot: ScreenSnapshot): String {
        validate(snapshot)
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("screenId", snapshot.screenId)
            put("frameId", snapshot.frameId)
            put("timestampMs", snapshot.timestampMs)
            put("stableFrames", snapshot.stableFrames)
            putJsonArray("elements") {
                snapshot.elements.forEach { element ->
                    add(buildJsonObject {
                        put("id", element.id)
                        put("text", element.text)
                        put("kind", element.kind.name)
                        put("confidence", element.confidence)
                        putJsonObject("rect") {
                            put("left", element.rect.left); put("top", element.rect.top)
                            put("right", element.rect.right); put("bottom", element.rect.bottom)
                        }
                    })
                }
            }
        })
    }

    /** Missing metadata is supported ONLY for old hand-authored fixtures. */
    fun decode(value: String): ScreenSnapshot {
        val root = json.parseToJsonElement(value).jsonObject
        val elements = root.getValue("elements").jsonArray.map { item ->
            val element = item.jsonObject
            val rect = element.getValue("rect").jsonObject
            ScreenElement(
                element.getValue("id").jsonPrimitive.content,
                element.getValue("text").jsonPrimitive.content,
                NormRect(rect.getValue("left").jsonPrimitive.float, rect.getValue("top").jsonPrimitive.float,
                    rect.getValue("right").jsonPrimitive.float, rect.getValue("bottom").jsonPrimitive.float),
                element["confidence"]?.jsonPrimitive?.float ?: 1f,
                ElementKind.valueOf(element.getValue("kind").jsonPrimitive.content),
            )
        }
        return ScreenSnapshot(
            root.getValue("screenId").jsonPrimitive.content,
            root["frameId"]?.jsonPrimitive?.long ?: 0L,
            root["timestampMs"]?.jsonPrimitive?.long ?: 0L,
            elements, root["stableFrames"]?.jsonPrimitive?.int ?: 2,
        ).also(::validate)
    }

    internal fun validate(snapshot: ScreenSnapshot) {
        require(snapshot.screenId.isNotBlank())
        require(snapshot.stableFrames >= 2)
        require(snapshot.elements.size <= ScreenSnapshot.MAX_ELEMENTS)
        require(snapshot.elements.map { it.id }.toSet().size == snapshot.elements.size)
        snapshot.elements.forEach {
            require(it.id.isNotBlank() && it.id.length <= ScreenElement.MAX_ID_LENGTH)
            require(it.text.isNotBlank() && it.text.length <= ScreenElement.MAX_TEXT_LENGTH)
            require(it.rect.isValid && it.confidence in 0f..1f)
        }
    }
}
