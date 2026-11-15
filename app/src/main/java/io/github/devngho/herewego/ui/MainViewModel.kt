package io.github.devngho.herewego.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.devngho.herewego.HereWeGoApp
import io.github.devngho.herewego.AppContainer
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.domain.withEuroRo
import io.github.devngho.herewego.navi.NaviConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** AppContainer 를 주입받는 ViewModel 팩토리. */
inline fun <reified VM : ViewModel> appViewModelFactory(
    crossinline create: (AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { create((this[APPLICATION_KEY] as HereWeGoApp).container) }
}

/** 앱 전역: 내비 연결 수명주기, 스낵바 메시지, 장소 상세 시트. */
class MainViewModel(private val c: AppContainer) : ViewModel() {

    val connection: StateFlow<NaviConnection> = c.navi.connection
    val messages: SharedFlow<String> = c.messages
    val openExplore: SharedFlow<Unit> = c.openExplore

    private val _selectedPlace = MutableStateFlow<Place?>(null)
    val selectedPlace: StateFlow<Place?> = _selectedPlace.asStateFlow()

    init {
        // 연결은 프로세스 단위로 유지한다(안내 중 화면을 닫아도 도착 콜백을 받아야 함). connect() 는 멱등.
        c.navi.connect()
    }

    fun select(place: Place?) {
        _selectedPlace.value = place
    }

    fun navigate(place: Place) = c.navi.requestRoute(place)
    fun addWaypoint(place: Place) = c.navi.addWaypoint(place)
    fun showInNavi(place: Place) = c.navi.showInNavi(place)

    /** 장소로 '이동'(데모): Func 1 의 현재 위치가 된다. */
    fun moveHere(place: Place) {
        c.location.moveTo(place)
        c.showMessage("데모 위치를 ${place.name.withEuroRo()} 옮겼어요")
    }

    companion object {
        val Factory = appViewModelFactory { MainViewModel(it) }
    }
}
