package io.github.devngho.herewego.ui.profile

import ai.pleos.playground.navi.data.RecentDestinationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devngho.herewego.AppContainer
import io.github.devngho.herewego.data.model.PlaceCategory
import io.github.devngho.herewego.data.model.UserProfile
import io.github.devngho.herewego.navi.NaviConnection
import io.github.devngho.herewego.ui.appViewModelFactory
import io.github.devngho.herewego.voice.VoiceState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalTime

data class ProfileUiState(
    val profile: UserProfile,
    val connection: NaviConnection,
    val naviAddress: String?,
    val eventLog: List<String>,
    val clockOverride: LocalTime?,
)

/** 사용자 분석 + 내비 연결 상태 + 데모 설정. */
class ProfileViewModel(private val c: AppContainer) : ViewModel() {

    val uiState: StateFlow<ProfileUiState> = combine(
        c.profile.profile, c.navi.connection, c.navi.currentLocation, c.navi.eventLog, c.clock.override,
    ) { profile, conn, loc, log, clock ->
        ProfileUiState(profile, conn, loc?.address, log, clock)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ProfileUiState(c.profile.profile.value, c.navi.connection.value, null, emptyList(), c.clock.override.value),
    )

    fun toggleInterest(category: PlaceCategory) = c.profile.toggleInterest(category)

    fun resetProfile() = c.profile.reset()

    /** 내비 최근 목적지로 학습. 내비가 없으면 샘플 최근 목적지를 사용한다. */
    fun learnFromNavi() {
        if (c.navi.isConnected) {
            c.navi.refresh() // 결과는 AppContainer 에서 자동 학습
        } else {
            val learned = c.profile.learnFrom(DEMO_RECENT)
            c.showMessage(
                if (learned > 0) "데모 모드 · 샘플 최근 목적지 ${learned}곳에서 취향을 학습했어요"
                else "새로 학습할 최근 목적지가 없어요"
            )
        }
    }

    fun reconnect() = c.navi.connect()

    fun setClockOverride(time: LocalTime?) = c.clock.setOverride(time)

    val voice: StateFlow<VoiceState> = c.voice.state
    val autoAnnounce: StateFlow<Boolean> = c.voice.autoAnnounce

    fun setAutoAnnounce(enabled: Boolean) = c.voice.setAutoAnnounce(enabled)

    fun testVoice() = c.voice.speak("음성 안내를 테스트해요. 목적지에 도착하면 이 목소리로 추천을 알려 드릴게요.")

    companion object {
        val Factory = appViewModelFactory { ProfileViewModel(it) }

        private fun recent(name: String, lat: Double, lng: Double) = RecentDestinationInfo(
            latitude = lat, longitude = lng, name = name, address = "", poiId = "", poiSubId = null,
        )

        private val DEMO_RECENT = listOf(
            recent("판교 요가 & 필라테스", 37.401, 127.108),
            recent("양재 시민의 숲", 37.470, 127.036),
            recent("도곡 스페셜티 커피", 37.488, 127.043),
            recent("판교 미디어아트 전시관", 37.401, 127.103),
            recent("정자동 동네 빵집", 37.366, 127.106),
        )
    }
}
