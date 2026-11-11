package kr.kiosklens.app

import kr.kiosklens.app.net.LocalNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test fun `같은 와이파이 · 에뮬레이터의 Mac 주소는 로컬 네트워크다`() {
        assertTrue(LocalNetwork.isLocal("http://10.0.2.2:8080"))
        assertTrue(LocalNetwork.isLocal("http://192.168.0.12:8080/"))
        assertTrue(LocalNetwork.isLocal("http://172.20.1.5:8080"))
        assertTrue(LocalNetwork.isLocal("http://kiosk-server.local:8080"))
    }

    @Test fun `루프백 · 인터넷 주소 · 잘못된 주소는 로컬 네트워크가 아니다`() {
        assertFalse(LocalNetwork.isLocal("http://127.0.0.1:8080"))
        assertFalse(LocalNetwork.isLocal("https://kiosk-lens.onrender.com"))
        assertFalse(LocalNetwork.isLocal("http://172.32.0.1:8080"))
        assertFalse(LocalNetwork.isLocal(""))
        assertFalse(LocalNetwork.isLocal("서버 주소 아님"))
    }
}
