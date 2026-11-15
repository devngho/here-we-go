package io.github.devngho.herewego.server

/** 환경 변수로 받는 서버 설정. */
data class Config(
    val port: Int = 8080,
    /** 비어 있으면 X-App-Key 검사를 하지 않는다 (로컬 개발용). */
    val appKey: String? = null,
    /** Places API (New) 를 쓸 수 있는 Google Maps Platform 키. */
    val googlePlacesKey: String? = null,
    val geminiApiKey: String? = null,
    val llmModel: String = "gemini-4-hydrogen",
    /**
     * LLM 이 이 시간 안에 답하지 않으면 규칙 기반 순위를 돌려준다.
     * 후보 20여 곳을 고르는 데 실측 6~12초가 걸린다 (리뷰 발췌가 붙으면 길어진다). 앱은 rank=rules 결과를 먼저 보여 주므로 기다릴 수 있다.
     */
    val llmTimeoutMillis: Long = 15_000,
    /**
     * 후보의 리뷰 요약(대표 메뉴·리뷰 경향)을 기다리는 시간. 순위를 매기는 호출과 동시에 돌므로 더해지지 않는다.
     * 늦은 결과는 캐시에 들어가 다음 요청이 쓴다.
     */
    val insightTimeoutMillis: Long = 10_000,
    /** 한 요청이 새로 요약하는 최대 장소 수. 0 이면 요약하지 않는다 (평점·영업시간은 그대로 나온다). */
    val insightMaxPlaces: Int = 24,
    /** Fail closed on invalid limits; zero disables the corresponding paid API. */
    val apiRequestsPerMinute: Int = 60,
    val deviceRequestsPerMinute: Int = 20,
    val placesCallsPerMinute: Int = 40,
    val placesCallsPerDay: Int = 100,
    val placesCallsPerMonth: Int = 1_000,
    val geminiCallsPerMinute: Int = 10,
    val geminiCallsPerDay: Int = 60,
    val geminiCallsPerMonth: Int = 500,
    /** SQLite 파일 위치 (M4 부터 사용). */
    val dbPath: String = "data/herewego.db",
) {
    init {
        require(
            listOf(
                apiRequestsPerMinute,
                deviceRequestsPerMinute,
                placesCallsPerMinute,
                placesCallsPerDay,
                placesCallsPerMonth,
                geminiCallsPerMinute,
                geminiCallsPerDay,
                geminiCallsPerMonth,
            ).all { it >= 0 },
        ) { "Rate limits must be non-negative" }
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun get(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }

            fun limit(
                name: String,
                default: Int,
            ): Int =
                get(name)?.let {
                    requireNotNull(it.toIntOrNull()?.takeIf { n -> n >= 0 }) { "$name must be a non-negative integer" }
                } ?: default
            val default = Config()
            return Config(
                port = get("PORT")?.toIntOrNull() ?: default.port,
                appKey = get("APP_KEY"),
                googlePlacesKey = get("GOOGLE_PLACES_KEY"),
                geminiApiKey = get("GEMINI_API_KEY"),
                llmModel = get("LLM_MODEL") ?: default.llmModel,
                llmTimeoutMillis = get("LLM_TIMEOUT_MS")?.toLongOrNull() ?: default.llmTimeoutMillis,
                insightTimeoutMillis = get("INSIGHT_TIMEOUT_MS")?.toLongOrNull() ?: default.insightTimeoutMillis,
                insightMaxPlaces = get("INSIGHT_MAX_PLACES")?.toIntOrNull() ?: default.insightMaxPlaces,
                dbPath = get("DB_PATH") ?: default.dbPath,
                apiRequestsPerMinute = limit("API_REQUESTS_PER_MINUTE", default.apiRequestsPerMinute),
                deviceRequestsPerMinute = limit("DEVICE_REQUESTS_PER_MINUTE", default.deviceRequestsPerMinute),
                placesCallsPerMinute = limit("PLACES_CALLS_PER_MINUTE", default.placesCallsPerMinute),
                placesCallsPerDay = limit("PLACES_CALLS_PER_DAY", default.placesCallsPerDay),
                placesCallsPerMonth = limit("PLACES_CALLS_PER_MONTH", default.placesCallsPerMonth),
                geminiCallsPerMinute = limit("GEMINI_CALLS_PER_MINUTE", default.geminiCallsPerMinute),
                geminiCallsPerDay = limit("GEMINI_CALLS_PER_DAY", default.geminiCallsPerDay),
                geminiCallsPerMonth = limit("GEMINI_CALLS_PER_MONTH", default.geminiCallsPerMonth),
            )
        }
    }
}
