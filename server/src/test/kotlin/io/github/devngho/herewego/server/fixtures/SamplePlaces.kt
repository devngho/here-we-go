package io.github.devngho.herewego.server.fixtures

import io.github.devngho.herewego.server.model.GeoPoint
import io.github.devngho.herewego.server.model.Place
import io.github.devngho.herewego.server.model.PlaceCategory
import io.github.devngho.herewego.server.model.PlaceCategory.BAKERY
import io.github.devngho.herewego.server.model.PlaceCategory.BOOKSTORE
import io.github.devngho.herewego.server.model.PlaceCategory.CAFE
import io.github.devngho.herewego.server.model.PlaceCategory.CONVENIENCE
import io.github.devngho.herewego.server.model.PlaceCategory.EV_CHARGING
import io.github.devngho.herewego.server.model.PlaceCategory.FLORIST
import io.github.devngho.herewego.server.model.PlaceCategory.GALLERY
import io.github.devngho.herewego.server.model.PlaceCategory.GIFT_SHOP
import io.github.devngho.herewego.server.model.PlaceCategory.GYM
import io.github.devngho.herewego.server.model.PlaceCategory.HOME
import io.github.devngho.herewego.server.model.PlaceCategory.MART
import io.github.devngho.herewego.server.model.PlaceCategory.OFFICE
import io.github.devngho.herewego.server.model.PlaceCategory.PARK
import io.github.devngho.herewego.server.model.PlaceCategory.PHARMACY
import io.github.devngho.herewego.server.model.PlaceCategory.RESTAURANT
import io.github.devngho.herewego.server.model.PlaceCategory.WINE_SHOP
import java.time.LocalTime

/**
 * 데모용 POI 데이터.
 *
 * NaviHelper SDK 는 POI 검색 API 를 제공하지 않으므로, 프로토타입에서는 양재 ~ 판교 ~ 분당 축에
 * 가상의 장소를 배치해 추천 로직을 시연한다. 앵커(42dot, 소프트웨어드림센터)의 좌표·POI ID 는
 * Pleos 공식 NaviHelper 샘플 앱의 값을 그대로 사용했다. 그 외 상호명은 모두 가상이다.
 * 실제 서비스에서는 이 객체를 지도/POI API 기반 Repository 로 교체하면 된다.
 */
object SamplePlaces {

    private fun t(h: Int, m: Int = 0): LocalTime = LocalTime.of(h, m)
    private val ALL_DAY_CLOSE: LocalTime = LocalTime.of(23, 59)

    private fun place(
        id: String,
        name: String,
        category: PlaceCategory,
        lat: Double,
        lng: Double,
        address: String,
        rating: Double,
        stay: Int,
        open: LocalTime = t(9),
        close: LocalTime = t(22),
        highlights: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        poiId: String = "",
    ) = Place(id, name, category, GeoPoint(lat, lng), address, rating, stay, open, close, highlights, tags, poiId)

    // ---- 앵커(일정 장소 / 목적지) -------------------------------------------------------------

    val office42dot = place(
        "a_42dot", "42dot 양재 사옥", OFFICE, 37.48544722, 127.03636666,
        "서울시 강남구 남부순환로 2621", 4.5, 0, t(0), ALL_DAY_CLOSE,
        poiId = "36khp37hh8gqcm5nqhe5",
    )
    val softwareDreamCenter = place(
        "a_sdc", "소프트웨어드림센터", OFFICE, 37.41275833, 127.09481944,
        "경기도 성남시 수정구 창업로40번길 6", 4.4, 0, t(0), ALL_DAY_CLOSE,
        poiId = "36khp2hdj2cfcm5nqhe3",
    )
    val friendHouse = place(
        "a_friend", "친구네 새 집 (정자동)", HOME, 37.3668, 127.1083,
        "경기도 성남시 분당구 정자동 (샘플)", 5.0, 0, t(0), ALL_DAY_CLOSE,
    )
    val parentsHouse = place(
        "a_parents", "부모님 댁 (수지)", HOME, 37.3240, 127.0978,
        "경기도 용인시 수지구 (샘플)", 5.0, 0, t(0), ALL_DAY_CLOSE,
    )

    val anchors = listOf(office42dot, softwareDreamCenter, friendHouse, parentsHouse)

    // ---- 양재 / 도곡 ---------------------------------------------------------------------------

    val yangjaeRestaurant = place(
        "r1", "양재 숯불 한식당", RESTAURANT, 37.4842, 127.0349, "서울 서초구 양재동 (샘플)", 4.4, 60,
        t(11), t(22), listOf("돼지 숯불구이 정식", "차돌 된장찌개", "계절 나물 비빔밥"), listOf("점심 특선"),
    )

    private val yangjae = listOf(
        yangjaeRestaurant,
        place(
            "r2", "남부순환 칼국수", RESTAURANT, 37.4861, 127.0388, "서울 서초구 양재동 (샘플)", 4.2, 40,
            t(10, 30), t(21), listOf("바지락 칼국수", "고기 왕만두", "보리밥"),
        ),
        place(
            "c1", "숲길 로스터리", CAFE, 37.4835, 127.0378, "서울 서초구 양재동 (샘플)", 4.7, 50,
            t(8), t(22), listOf("핸드드립 싱글오리진", "바스크 치즈케이크", "창가 좌석에서 작업하기"), listOf("조용한", "작업하기 좋은"),
        ),
        place(
            "c2", "양재천 테라스 카페", CAFE, 37.4795, 127.0400, "서울 강남구 개포동 (샘플)", 4.5, 45,
            t(10), t(22), listOf("아인슈페너", "양재천 뷰 테라스석"), listOf("뷰 맛집"),
        ),
        place(
            "b1", "도곡 브레드랩", BAKERY, 37.4868, 127.0405, "서울 강남구 도곡동 (샘플)", 4.6, 15,
            t(8), t(21), listOf("소금빵", "홀케이크 (당일 예약)", "선물용 쿠키 박스"),
        ),
        place(
            "p1", "양재천 산책로", PARK, 37.4810, 127.0420, "서울 강남구 양재천로 일대", 4.6, 40,
            t(0), ALL_DAY_CLOSE, listOf("양재천 따라 30분 산책", "징검다리 건너기", "벤치에서 쉬어 가기"), listOf("산책"),
        ),
        place(
            "p2", "양재 시민의 숲", PARK, 37.4700, 127.0360, "서울 서초구 매헌로 일대", 4.7, 60,
            t(5), t(23), listOf("메타세쿼이아 숲길 산책", "기념관 둘러보기"), listOf("산책", "자연"),
        ),
        place(
            "g1", "양재 스포츠 피트니스", GYM, 37.4875, 127.0340, "서울 서초구 양재동 (샘플)", 4.3, 70,
            t(6), t(23), listOf("일일권 웨이트", "러닝머신", "샤워 시설"), listOf("일일권"),
        ),
        place(
            "bk1", "양재 동네책방", BOOKSTORE, 37.4850, 127.0410, "서울 서초구 양재동 (샘플)", 4.6, 30,
            t(11), t(21), listOf("독립출판물 큐레이션", "주말 북토크"),
        ),
        place(
            "gl1", "양재 아트스페이스", GALLERY, 37.4820, 127.0330, "서울 서초구 양재동 (샘플)", 4.4, 45,
            t(10), t(19), listOf("신진 작가 기획전", "아트 굿즈샵"),
        ),
        place(
            "f1", "양재 화훼마켓 플라워", FLORIST, 37.4596, 127.0425, "서울 서초구 양재동 화훼단지 (샘플)", 4.7, 20,
            t(7), t(19), listOf("꽃다발", "공기정화 화분", "선물 포장"),
        ),
        place(
            "m1", "양재 대형마트", MART, 37.4625, 127.0430, "서울 서초구 양재동 (샘플)", 4.3, 30,
            t(8), t(22), listOf("생활용품 (휴지·세제)", "제철 과일", "간편식"),
        ),
        place(
            "cv1", "편의점 양재역점", CONVENIENCE, 37.4845, 127.0345, "서울 서초구 양재동 (샘플)", 4.0, 5,
            t(0), ALL_DAY_CLOSE, listOf("얼음컵", "생수", "일회용품"),
        ),
        place(
            "w1", "도곡 와인셀러", WINE_SHOP, 37.4880, 127.0430, "서울 강남구 도곡동 (샘플)", 4.6, 20,
            t(11), t(22), listOf("내추럴 와인", "2~3만원대 선물 와인", "선물 포장"),
        ),
        place(
            "gf1", "소품샵 오후", GIFT_SHOP, 37.4830, 127.0395, "서울 서초구 양재동 (샘플)", 4.5, 20,
            t(11), t(21), listOf("향초·디퓨저", "핸드메이드 머그"),
        ),
        place(
            "ph1", "양재 온누리약국", PHARMACY, 37.4850, 127.0355, "서울 서초구 양재동 (샘플)", 4.2, 10,
            t(9), t(21), listOf("비타민", "건강음료 세트"),
        ),
    )

    // ---- 내곡 / 청계산 / 고등 (양재 → 판교 경로 중간) ---------------------------------------------

    private val midRoute = listOf(
        place(
            "m2", "내곡 로컬푸드 마트", MART, 37.4605, 127.0570, "서울 서초구 내곡동 (샘플)", 4.4, 25,
            t(9), t(21), listOf("지역 농산물", "과일 선물세트", "생활용품"),
        ),
        place(
            "f2", "내곡 플라워 하우스", FLORIST, 37.4580, 127.0600, "서울 서초구 내곡동 (샘플)", 4.5, 15,
            t(9), t(20), listOf("화분", "미니 꽃다발"),
        ),
        place(
            "c3", "청계산 입구 카페", CAFE, 37.4480, 127.0560, "서울 서초구 원지동 (샘플)", 4.4, 40,
            t(9), t(21), listOf("산 전망 루프탑", "수제 대추차"), listOf("뷰 맛집"),
        ),
        place(
            "p3", "청계산 둘레길 입구", PARK, 37.4460, 127.0540, "서울 서초구 원지동 일대", 4.6, 60,
            t(5), t(20), listOf("둘레길 1코스 산책", "약수터 들르기"), listOf("산책", "자연"),
        ),
        place(
            "r3", "청계산 두부마을", RESTAURANT, 37.4470, 127.0580, "서울 서초구 원지동 (샘플)", 4.3, 50,
            t(10), t(21), listOf("순두부 정식", "손두부 보쌈"),
        ),
        place(
            "ev1", "세곡 EV 충전소", EV_CHARGING, 37.4500, 127.0700, "서울 강남구 세곡동 (샘플)", 4.1, 30,
            t(0), ALL_DAY_CLOSE, listOf("초급속 200kW", "대기 라운지"),
        ),
        place(
            "b2", "고등동 베이커리", BAKERY, 37.4380, 127.0780, "경기 성남시 수정구 고등동 (샘플)", 4.4, 15,
            t(8), t(21), listOf("단팥빵", "생크림 케이크", "샌드위치"),
        ),
        place(
            "w2", "판교IC 와인마켓", WINE_SHOP, 37.4280, 127.0880, "경기 성남시 수정구 (샘플)", 4.3, 15,
            t(11), t(22), listOf("가성비 와인", "치즈 플래터"),
        ),
        place(
            "cv2", "편의점 고등지구점", CONVENIENCE, 37.4350, 127.0790, "경기 성남시 수정구 고등동 (샘플)", 4.0, 5,
            t(0), ALL_DAY_CLOSE, listOf("얼음", "생수"),
        ),
    )

    // ---- 판교 ---------------------------------------------------------------------------------

    private val pangyo = listOf(
        place(
            "r4", "판교 덮밥 식당", RESTAURANT, 37.4110, 127.0960, "경기 성남시 수정구 시흥동 (샘플)", 4.3, 40,
            t(11), t(21), listOf("연어 덮밥", "규동", "미니 우동"),
        ),
        place(
            "r5", "테크노밸리 쌀국수", RESTAURANT, 37.4020, 127.1080, "경기 성남시 분당구 삼평동 (샘플)", 4.2, 35,
            t(11), t(21), listOf("양지 쌀국수", "분짜"),
        ),
        place(
            "c4", "판교 코워킹 카페", CAFE, 37.4120, 127.0975, "경기 성남시 수정구 (샘플)", 4.5, 60,
            t(8), t(22), listOf("노트북 작업석", "콘센트 좌석", "콜드브루"), listOf("작업하기 좋은"),
        ),
        place(
            "c5", "화랑공원 카페", CAFE, 37.3990, 127.1120, "경기 성남시 분당구 (샘플)", 4.4, 40,
            t(9), t(22), listOf("호수 뷰 창가석", "말차 라떼"), listOf("뷰 맛집"),
        ),
        place(
            "p4", "판교 화랑공원", PARK, 37.3975, 127.1130, "경기 성남시 분당구 일대", 4.6, 40,
            t(0), ALL_DAY_CLOSE, listOf("호수 한 바퀴 산책", "야외 조각 감상"), listOf("산책"),
        ),
        place(
            "g2", "판교 크로스핏 박스", GYM, 37.4040, 127.1050, "경기 성남시 분당구 (샘플)", 4.5, 60,
            t(6), t(23), listOf("드롭인 클래스", "샤워 시설"), listOf("일일권"),
        ),
        place(
            "bk2", "판교 대형서점", BOOKSTORE, 37.3940, 127.1110, "경기 성남시 분당구 (샘플)", 4.5, 40,
            t(10), t(22), listOf("IT·자기계발 신간", "문구 코너"),
        ),
        place(
            "f3", "판교 플라워 아틀리에", FLORIST, 37.4000, 127.1100, "경기 성남시 분당구 (샘플)", 4.6, 15,
            t(10), t(20), listOf("꽃다발", "드라이플라워"),
        ),
        place(
            "gf2", "판교 디자인 문구", GIFT_SHOP, 37.3950, 127.1115, "경기 성남시 분당구 (샘플)", 4.4, 20,
            t(10), t(21), listOf("디자인 문구", "선물 포장"),
        ),
        place(
            "gl2", "판교 미디어아트 전시관", GALLERY, 37.4010, 127.1030, "경기 성남시 분당구 (샘플)", 4.5, 50,
            t(10), t(20), listOf("몰입형 미디어아트", "포토존"),
        ),
        place(
            "ph2", "판교역 약국", PHARMACY, 37.3948, 127.1112, "경기 성남시 분당구 (샘플)", 4.1, 10,
            t(9), t(22), listOf("영양제", "건강음료"),
        ),
        place(
            "ev2", "테크노밸리 충전소", EV_CHARGING, 37.4070, 127.1010, "경기 성남시 분당구 (샘플)", 4.2, 30,
            t(0), ALL_DAY_CLOSE, listOf("급속 100kW"),
        ),
    )

    // ---- 정자 / 수지 ---------------------------------------------------------------------------

    private val bundang = listOf(
        place(
            "m3", "정자동 동네마트", MART, 37.3680, 127.1070, "경기 성남시 분당구 정자동 (샘플)", 4.2, 20,
            t(8), t(23), listOf("휴지·세제", "과일"),
        ),
        place(
            "b3", "정자 카페거리 베이커리", BAKERY, 37.3655, 127.1060, "경기 성남시 분당구 정자동 (샘플)", 4.7, 15,
            t(8), t(21), listOf("과일 타르트", "레터링 케이크"),
        ),
        place(
            "c6", "정자 카페거리 카페", CAFE, 37.3660, 127.1050, "경기 성남시 분당구 정자동 (샘플)", 4.5, 45,
            t(9), t(23), listOf("시그니처 라떼", "테라스석"),
        ),
        place(
            "p5", "탄천 산책로 (정자)", PARK, 37.3640, 127.1110, "경기 성남시 분당구 탄천 일대", 4.5, 40,
            t(0), ALL_DAY_CLOSE, listOf("탄천 자전거길 산책"), listOf("산책"),
        ),
        place(
            "f4", "수지 꽃집", FLORIST, 37.3230, 127.0960, "경기 용인시 수지구 (샘플)", 4.4, 15,
            t(9), t(20), listOf("카네이션", "꽃바구니"),
        ),
        place(
            "m4", "수지 대형마트", MART, 37.3210, 127.0950, "경기 용인시 수지구 (샘플)", 4.3, 30,
            t(9), t(23), listOf("과일 선물세트", "생활용품"),
        ),
    )

    val all: List<Place> = anchors + yangjae + midRoute + pangyo + bundang
}
