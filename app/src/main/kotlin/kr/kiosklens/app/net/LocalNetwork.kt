package kr.kiosklens.app.net

import java.net.URI

/**
 * 서버 주소가 **로컬 네트워크**(같은 와이파이의 노트북 · 에뮬레이터에서 본 Mac `10.0.2.2`)인가.
 *
 * Android 17+는 앱이 사설 IP에 붙으려면 `ACCESS_LOCAL_NETWORK` 권한이 있어야 하고, 없으면 요청이
 * 조용히 막혀 6초 뒤 시간 초과로 끝난다 ("연결이 잠시 불안정해요"). 루프백(127.x)과 인터넷 주소는 해당 없음.
 */
object LocalNetwork {
    fun isLocal(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase() ?: return false
        if (host.endsWith(".local")) return true
        val p = host.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254)
    }
}
