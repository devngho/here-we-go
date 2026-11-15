package io.github.devngho.herewego.voice

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import android.speech.tts.TextToSpeech as PlatformTts

enum class VoiceEngine(val label: String) {
    PLEOS("Pleos TextToSpeech (온디바이스)"),
    ANDROID("Android 기본 TTS"),
    NONE("사용할 수 있는 음성 엔진이 없어요"),
}

data class VoiceState(
    val engine: VoiceEngine = VoiceEngine.NONE,
    val initializing: Boolean = true,
    val speaking: Boolean = false,
    /** 마지막으로 읽은 문장. 에뮬레이터 소리가 꺼져 있어도 화면에서 확인할 수 있게. */
    val lastSpoken: String? = null,
    val lastError: String? = null,
) {
    val available: Boolean get() = !initializing && engine != VoiceEngine.NONE
}

/**
 * 음성 안내.
 *
 * Pleos Connect 의 TextToSpeech SDK 를 우선 사용한다. 차량 음성 서비스가 합성·재생하므로
 * 내비 안내음/미디어와의 오디오 포커스를 차량이 관리해 준다. `Mode.ON_DEVICE` 는
 * Pleos Playground 앱 등록(clientId/secret) 없이 동작한다.
 * Pleos 음성 서비스가 없는 환경(일반 폰/에뮬레이터)에서는 Android 기본 TTS 로 대체한다.
 *
 * 앱 프로세스 수명 동안 하나만 쓰므로 release 는 하지 않는다.
 */
class VoiceGuide(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** 도착·경유지 도착 시 자동으로 읽어 줄지. */
    private val _autoAnnounce = MutableStateFlow(true)
    val autoAnnounce: StateFlow<Boolean> = _autoAnnounce.asStateFlow()

    private var pleos: PleosTts? = null
    private var platform: PlatformTts? = null
    /** 초기화가 끝나기 전에 들어온 마지막 요청. */
    private var pending: String? = null
    private var utteranceSeq = 0

    private val pleosListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = _state.update { it.copy(speaking = true, lastError = null) }
        override fun onDone() = _state.update { it.copy(speaking = false) }
        override fun onStop() = _state.update { it.copy(speaking = false) }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "Pleos TTS error: $errorType")
            _state.update { it.copy(speaking = false, lastError = errorType.name) }
        }
    }

    fun initialize() {
        main.post { initPleos() }
    }

    fun setAutoAnnounce(enabled: Boolean) {
        _autoAnnounce.value = enabled
        if (!enabled) stop()
    }

    /** 사용자가 요청한 읽기. 진행 중인 문장은 끊고 새로 읽는다. */
    fun speak(text: String) {
        if (text.isBlank()) return
        main.post {
            _state.update { it.copy(lastSpoken = text) }
            val s = _state.value
            when {
                s.initializing -> pending = text
                s.engine == VoiceEngine.PLEOS -> runCatching { pleos?.speak(text, true) }
                    .onFailure(::fail)

                s.engine == VoiceEngine.ANDROID ->
                    platform?.speak(text, PlatformTts.QUEUE_FLUSH, null, "u${utteranceSeq++}")

                else -> Unit
            }
        }
    }

    /** 자동 안내. 설정에서 끈 경우 읽지 않는다. */
    fun announce(text: String) {
        if (_autoAnnounce.value) speak(text)
    }

    fun stop() {
        main.post {
            pending = null
            when (_state.value.engine) {
                VoiceEngine.PLEOS -> runCatching { pleos?.stop() }
                VoiceEngine.ANDROID -> platform?.stop()
                VoiceEngine.NONE -> Unit
            }
            _state.update { it.copy(speaking = false) }
        }
    }

    // ------------------------------------------------------------------ init

    private fun initPleos() {
        val granted = ContextCompat.checkSelfPermission(appContext, TTS_PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return initPlatform("$TTS_PERMISSION 권한 없음")
        val tts = try {
            PleosTts(appContext, Mode.ON_DEVICE)
        } catch (e: Exception) {
            return initPlatform("Pleos TTS 생성 실패: ${e.message}")
        }
        pleos = tts
        // 음성 서비스가 없으면 콜백이 오지 않을 수 있으니 시간 제한을 둔다.
        val timeout = Runnable { if (_state.value.initializing) fallbackFromPleos("응답 없음") }
        main.postDelayed(timeout, INIT_TIMEOUT_MS)
        try {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        main.removeCallbacks(timeout)
                        if (pleos !== tts) return@post
                        tts.addEventListener(pleosListener)
                        runCatching { tts.setLocale(Locale.KOREA) }
                        ready(VoiceEngine.PLEOS)
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    main.post {
                        main.removeCallbacks(timeout)
                        fallbackFromPleos(errorType.name)
                    }
                }
            })
        } catch (e: Exception) {
            main.removeCallbacks(timeout)
            fallbackFromPleos(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fallbackFromPleos(reason: String) {
        Log.w(TAG, "Pleos TTS unavailable ($reason), falling back to platform TTS")
        runCatching { pleos?.release() }
        pleos = null
        initPlatform("Pleos TTS 초기화 실패: $reason")
    }

    private fun initPlatform(reason: String) {
        Log.i(TAG, "Using platform TTS: $reason")
        lateinit var tts: PlatformTts
        // 리스너가 생성자 안에서 바로 불릴 수 있어 main 으로 한 번 넘긴다.
        tts = PlatformTts(appContext) { status -> main.post { onPlatformInit(tts, status, reason) } }
    }

    private fun onPlatformInit(tts: PlatformTts, status: Int, reason: String) {
        if (status != PlatformTts.SUCCESS) {
            tts.shutdown()
            _state.value = VoiceState(engine = VoiceEngine.NONE, initializing = false, lastError = reason)
            pending = null
            return
        }
        tts.language = Locale.KOREA
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = _state.update { it.copy(speaking = true) }
            override fun onDone(utteranceId: String?) = _state.update { it.copy(speaking = false) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) = _state.update { it.copy(speaking = false) }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = _state.update { it.copy(speaking = false, lastError = "재생 오류") }
        })
        platform = tts
        ready(VoiceEngine.ANDROID, note = reason)
    }

    private fun ready(engine: VoiceEngine, note: String? = null) {
        Log.i(TAG, "Voice ready: $engine")
        _state.update { it.copy(engine = engine, initializing = false, lastError = note) }
        pending?.let {
            pending = null
            speak(it)
        }
    }

    private fun fail(t: Throwable) {
        Log.w(TAG, "TTS speak failed", t)
        _state.update { it.copy(speaking = false, lastError = t.message ?: t.javaClass.simpleName) }
    }

    private companion object {
        const val TAG = "VoiceGuide"
        const val TTS_PERMISSION = "pleos.car.permission.TTS_SERVICE"
        const val INIT_TIMEOUT_MS = 5_000L
    }
}
