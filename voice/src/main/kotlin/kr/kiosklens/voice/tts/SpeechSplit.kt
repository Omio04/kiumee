package kr.kiosklens.voice.tts

/** 긴 안내를 문장 단위로 나눈다. 문장 끝(. ! ? 줄바꿈)에서 자르되 너무 짧은 조각은 앞과 합친다. */
object SpeechSplit {
    fun split(text: String, min: Int = 12): List<String> {
        val raw = Regex("(?<=[.!?。\\n])\\s+").split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        for (s in raw) if (out.isNotEmpty() && out.last().length < min) out[out.lastIndex] = out.last() + " " + s else out += s
        return out.ifEmpty { listOf(text.trim()) }
    }
}
