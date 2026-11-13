package kr.kiosklens.agent.remote

import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.GuideAction
import kr.kiosklens.core.model.GuideRequest
import kr.kiosklens.core.model.GuideResponse
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.ScrollDir
import kr.kiosklens.core.model.UiElement

/** 계약 v1 (docs/contract.md 3장) 의 JSON ↔ :core-model 변환. 사진은 선택 필드(image)로만 간다 — 앱 설정으로 끄면 없다. */
object DtoMapper {

    /** 서버가 400으로 거절하는 기준에 맞춰 보내기 전에 정리한다 (3.6). */
    fun sanitize(r: GuideRequest): GuideRequest {
        val seen = HashSet<String>()
        val els = r.snapshot.elements.asSequence()
            .filter { it.text.isNotBlank() && it.id.isNotEmpty() && it.rect.isValid }
            .map { it.copy(id = it.id.take(UiElement.MAX_ID_LENGTH), text = it.text.trim().take(UiElement.MAX_TEXT_LENGTH)) }
            .filter { seen.add(it.id) }
            .take(ScreenSnapshot.MAX_ELEMENTS)
            .toList()
        return r.copy(
            utterance = r.utterance?.trim()?.take(GuideRequest.MAX_UTTERANCE_LENGTH)?.ifEmpty { null },
            goal = r.goal?.trim()?.takeLast(GuideRequest.MAX_GOAL_LENGTH)?.ifEmpty { null },
            snapshot = r.snapshot.copy(elements = els),
            history = r.history.takeLast(GuideRequest.MAX_HISTORY),
        )
    }

    fun requestJson(raw: GuideRequest, imageBase64: String? = null): String {
        val r = sanitize(raw)
        val sb = StringBuilder(512)
        sb.append('{')
        sb.append("\"sessionId\":").append(Json.quote(r.sessionId))
        sb.append(",\"utterance\":").append(r.utterance?.let { Json.quote(it) } ?: "null")
        sb.append(",\"goal\":").append(r.goal?.let { Json.quote(it) } ?: "null")
        val s = r.snapshot
        sb.append(",\"snapshot\":{")
        sb.append("\"screenId\":").append(Json.quote(s.screenId))
        sb.append(",\"frameId\":").append(s.frameId)
        sb.append(",\"timestampMs\":").append(s.timestampMs)
        sb.append(",\"stableFrames\":").append(s.stableFrames)
        sb.append(",\"elements\":[")
        s.elements.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(Json.quote(e.id))
            sb.append(",\"text\":").append(Json.quote(e.text))
            sb.append(",\"rect\":{\"left\":").append(num(e.rect.left)).append(",\"top\":").append(num(e.rect.top))
                .append(",\"right\":").append(num(e.rect.right)).append(",\"bottom\":").append(num(e.rect.bottom)).append('}')
            sb.append(",\"confidence\":").append(num(e.confidence.coerceIn(0f, 1f)))
            sb.append(",\"kind\":").append(Json.quote(e.kind.name)).append('}')
        }
        sb.append("]}")
        sb.append(",\"history\":[")
        r.history.forEachIndexed { i, h ->
            if (i > 0) sb.append(',')
            sb.append("{\"screenId\":").append(Json.quote(h.screenId))
            sb.append(",\"action\":").append(Json.quote(h.action.name))
            sb.append(",\"targetText\":").append(h.targetText?.let { Json.quote(it) } ?: "null").append('}')
        }
        sb.append("]")
        if (!imageBase64.isNullOrEmpty()) sb.append(",\"image\":").append(Json.quote(imageBase64))      // 선택: 지금 카메라 사진 (계약 v1 확장)
        sb.append("}")
        return sb.toString()
    }

    private fun num(f: Float): String = if (f.isNaN() || f.isInfinite()) "0" else f.toString()

    class BadResponse(message: String) : Exception(message)

    /** 모르는 필드(source 등)는 무시한다. 형식이 틀리면 [BadResponse]. */
    fun parseResponse(body: String): GuideResponse {
        val root = Json.parseOrNull(body) as? Map<*, *> ?: throw BadResponse("JSON 객체가 아님")
        val a = root["action"] as? Map<*, *> ?: throw BadResponse("action 없음")
        fun str(k: String): String = (a[k] as? String)?.takeIf { it.isNotBlank() } ?: throw BadResponse("$k 없음")
        val action: GuideAction = when (val type = a["type"] as? String) {
            "TAP" -> GuideAction.Tap(str("targetId"), str("guideText"))
            "SCROLL" -> GuideAction.Scroll(
                runCatching { ScrollDir.valueOf(str("direction")) }.getOrElse { throw BadResponse("direction 오류") },
                str("guideText"),
            )
            "WAIT" -> GuideAction.Wait(
                str("guideText"),
                (a["targetIds"] as? List<*>)?.filterIsInstance<String>()?.distinct()?.take(12).orEmpty(),
            )
            "ASK_USER" -> GuideAction.AskUser(str("question"))
            "DONE" -> GuideAction.Done(str("guideText"))
            "UNKNOWN" -> GuideAction.Unknown(str("reason"))
            else -> throw BadResponse("모르는 type: $type")
        }
        val say = (root["say"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
        val box = (root["box"] as? Map<*, *>)?.let { b ->
            fun f(k: String): Float? = (b[k] as? Number)?.toFloat()
            val l = f("left"); val t = f("top"); val r = f("right"); val bt = f("bottom")
            if (l == null || t == null || r == null || bt == null) null else NormRect(l, t, r, bt).takeIf { it.isValid }
        }
        return GuideResponse(goal = (root["goal"] as? String).orEmpty(), action = action, say = say, box = box)
    }

    /** 오류 본문에서 code만 꺼낸다 (message는 개발자용이라 어르신께 읽지 않는다). */
    fun errorCode(body: String): String? =
        (((Json.parseOrNull(body) as? Map<*, *>)?.get("error")) as? Map<*, *>)?.get("code") as? String

    fun hasKind(e: UiElement, k: ElementKind) = e.kind == k
}
