package io.github.devngho.activities

import io.github.devngho.activities.data.SamplePlaces
import io.github.devngho.activities.data.model.Purpose
import io.github.devngho.activities.data.model.Schedule
import io.github.devngho.activities.data.UserProfileRepository
import io.github.devngho.activities.domain.RecommendationEngine
import io.github.devngho.activities.domain.SpeechScripts
import io.github.devngho.activities.domain.withCopula
import io.github.devngho.activities.domain.withEulReul
import io.github.devngho.activities.domain.withEuroRo
import io.github.devngho.activities.domain.withIGa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class SpeechScriptsTest {
    private val engine = RecommendationEngine(SamplePlaces.all)
    private val profile = UserProfileRepository().profile.value

    @Test
    fun josa() {
        assertEquals("규동이에요", "규동".withCopula())
        assertEquals("라테예요", "라테".withCopula())
        assertEquals("꽃집을", "꽃집".withEulReul())
        assertEquals("카페를", "카페".withEulReul())
        assertEquals("미팅이", "미팅".withIGa())
        assertEquals("집들이로", "집들이".withEuroRo())
        assertEquals("생일로", "생일".withEuroRo()) // ㄹ 받침은 '로'
        assertEquals("병원으로", "병원".withEuroRo())
        assertEquals("ABC(으)로", "ABC".withEuroRo())
    }

    @Test
    fun arrivalMentionsMenuAndNextCourse() {
        val place = SamplePlaces.yangjaeRestaurant
        val next = engine.nearby(place, place.location, profile, LocalTime.of(13, 10), personalWeight = 0.15, limit = 1)
        val text = SpeechScripts.arrival(place, next)
        assertTrue(text, text.startsWith("${place.name}에 도착했어요."))
        assertTrue(text, "대표 메뉴는" in text)
        assertTrue(text, "식사 후에는" in text && next.first().place.name in text)
    }

    @Test
    fun spokenNoteDropsParenthesesAndDuplicates() {
        assertEquals(
            "30분 머물고 16:20에 출발하면 양재 저녁 약속에 16:45 도착해요",
            SpeechScripts.spokenNote("30분 머물고 16:20에 출발하면 양재 저녁 약속(17:00)에 16:45 도착해요"),
        )
        assertNull(SpeechScripts.spokenNote("양재 저녁 약속(17:00) 장소에 도착했어요"))
    }

    @Test
    fun purposeAndGapAreShort() {
        val dest = SamplePlaces.friendHouse
        val suggestions = engine.forPurpose(Purpose.HOUSEWARMING, SamplePlaces.office42dot.location, dest, profile, LocalTime.of(17, 0))
        val purpose = SpeechScripts.purpose(Purpose.HOUSEWARMING, dest, suggestions, emptyList())
        assertTrue(purpose, purpose.startsWith("${dest.name}에 집들이로 가는 길이네요."))
        assertTrue(purpose.length < 200)

        val a = Schedule("판교 미팅", SamplePlaces.softwareDreamCenter, LocalTime.of(14, 0), LocalTime.of(16, 0))
        val b = Schedule("양재 저녁 약속", SamplePlaces.office42dot, LocalTime.of(17, 0), LocalTime.of(19, 0))
        val gap = SpeechScripts.gap(a, b, engine.forGap(a, b, LocalTime.of(15, 0), profile))
        assertTrue(gap, gap.startsWith("판교 미팅이 일찍 끝나서"))
        assertFalse(gap, "(" in gap)
    }
}
