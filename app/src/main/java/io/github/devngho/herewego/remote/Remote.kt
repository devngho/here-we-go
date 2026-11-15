package io.github.devngho.herewego.remote

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/** 서버에서 받아 오는 값의 상태. Loaded 가 아니면 화면은 내장 엔진의 결과를 보여 준다. */
sealed interface Remote<out T> {
    data object Loading : Remote<Nothing>
    data class Loaded<T>(val value: T) : Remote<T>
    data object Failed : Remote<Nothing>
}

/**
 * 요청 조건이 바뀔 때마다 서버에 다시 묻는다. 진행 중이던 요청은 취소된다.
 * 실패는 예외로 올리지 않고 [Remote.Failed] 로 바꾼다.
 *
 * @param stages 차례로 실행해 결과가 나올 때마다 내보낸다. 빠른 규칙 기반 결과를 먼저 보여 주고
 *   몇 초 걸리는 LLM 결과로 바꾸는 데 쓴다. 뒤 단계가 실패하면 앞 단계의 결과를 그대로 둔다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <K, T> Flow<K>.fetchLatest(tag: String, onFailure: (Exception) -> Unit = {}, vararg stages: suspend (K) -> T): Flow<Remote<T>> =
    distinctUntilChanged().transformLatest { key ->
        emit(Remote.Loading)
        stages.forEachIndexed { i, stage ->
            try {
                emit(Remote.Loaded(stage(key)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "request stage $i failed: $e")
                if (i == 0) {
                    onFailure(e)
                    emit(Remote.Failed)
                    return@transformLatest
                }
            }
        }
    }

/** 규칙 기반 순위만 요청할 때 보내는 값. */
const val RANK_RULES = "rules"
