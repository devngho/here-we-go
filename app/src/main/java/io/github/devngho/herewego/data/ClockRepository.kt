package io.github.devngho.herewego.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.time.LocalTime
import kotlin.time.Duration.Companion.seconds

/** 현재 시각. 시연을 위해 '데모 시각'으로 고정할 수 있다 (기본값: 점심 직후 13:10). */
class ClockRepository {
    private val _override = MutableStateFlow<LocalTime?>(LocalTime.of(13, 10))
    val override: StateFlow<LocalTime?> = _override.asStateFlow()

    private val ticker: Flow<LocalTime> = flow {
        while (true) {
            emit(LocalTime.now().withSecond(0).withNano(0))
            delay(30.seconds)
        }
    }

    val now: Flow<LocalTime> = combine(ticker, _override) { real, fixed -> fixed ?: real }

    fun current(): LocalTime = _override.value ?: LocalTime.now().withSecond(0).withNano(0)

    fun setOverride(time: LocalTime?) {
        _override.value = time
    }
}
