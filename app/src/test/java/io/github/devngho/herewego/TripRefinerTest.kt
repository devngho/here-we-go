package io.github.devngho.herewego

import io.github.devngho.herewego.data.SamplePlaces
import io.github.devngho.herewego.domain.GapStop
import io.github.devngho.herewego.domain.GapZone
import io.github.devngho.herewego.navi.TripRefiner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalTime

class TripRefinerTest {

    private fun t(h: Int, m: Int) = LocalTime.of(h, m)

    @Test
    fun `detour uses the measured leg and calibrates the estimated one`() {
        // 직행: 추정 20분, 실제 30분 → 보정 계수 1.5. 들를 곳까지 실제 12분, 들를 곳 → 목적지 추정 16분(보정 24분).
        assertEquals(6, TripRefiner.detourMinutes(toStopSeconds = 720, directSeconds = 1800, estimatedStopToDestMinutes = 16, estimatedDirectMinutes = 20))
        // 경로 위에 있으면 0 아래로 내려가지 않는다.
        assertEquals(0, TripRefiner.detourMinutes(toStopSeconds = 300, directSeconds = 1800, estimatedStopToDestMinutes = 10, estimatedDirectMinutes = 20))
    }

    private val stop = GapStop(
        place = SamplePlaces.yangjaeRestaurant, zone = GapZone.ON_ROUTE,
        arriveAt = t(15, 10), stayMinutes = 40, leaveAt = t(15, 50), arriveBAt = t(16, 20),
        score = 0.0, reasons = listOf("40분 머물 수 있어요", "우회 +3분", "B 도착 16:20 (여유 40분)"),
    )

    @Test
    fun `a longer real drive shifts the whole stop later`() {
        val shifted = TripRefiner.shiftStop(stop, realToMinutes = 25, actualEnd = t(15, 0), bStart = t(17, 0))!!
        assertEquals(t(15, 25), shifted.arriveAt)
        assertEquals(t(16, 5), shifted.leaveAt)
        assertEquals(t(16, 35), shifted.arriveBAt)
        assertEquals(40, shifted.stayMinutes)
        assertEquals("B 도착 16:35 (여유 25분)", shifted.reasons.last())
    }

    @Test
    fun `when the shift would make B late the stay shrinks instead`() {
        val shifted = TripRefiner.shiftStop(stop, realToMinutes = 25, actualEnd = t(15, 0), bStart = t(16, 25))!!
        assertEquals(30, shifted.stayMinutes)
        assertEquals(t(15, 55), shifted.leaveAt)
        assertEquals(t(16, 25), shifted.arriveBAt)
        assertEquals("30분 머물 수 있어요", shifted.reasons.first())
    }

    @Test
    fun `a stop that no longer leaves 15 minutes is dropped, an unchanged one is kept as is`() {
        assertNull(TripRefiner.shiftStop(stop, realToMinutes = 45, actualEnd = t(15, 0), bStart = t(16, 25)))
        assertSame(stop, TripRefiner.shiftStop(stop, realToMinutes = 10, actualEnd = t(15, 0), bStart = t(17, 0)))
    }
}
