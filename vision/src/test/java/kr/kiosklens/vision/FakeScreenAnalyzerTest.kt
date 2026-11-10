package kr.kiosklens.vision

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeScreenAnalyzerTest {
    @Test fun publishesSnapshotAndAnnouncesOnlyNewScreenIds() = runBlocking {
        val fake = FakeScreenAnalyzer()
        val menu = ScreenSnapshot("menu", 1L, 1000L, emptyList(), 2)
        val cart = ScreenSnapshot("cart", 2L, 2000L, emptyList(), 2)
        assertNull(fake.snapshots.value)
        val nextChange = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { fake.screenChanges.first() }
        }
        fake.show(menu)
        assertEquals(menu, fake.snapshots.value)
        fake.show(menu.copy())
        fake.show(cart)
        assertEquals(cart, fake.snapshots.value)
        assertEquals("cart", nextChange.await())
    }
}
