package com.myway.app.ui.common

import android.content.Context
import com.myway.app.R
import com.myway.app.data.repository.Facility

/**
 * 편의시설 코드 → 화면 이름, 그리고 "화장실 어디야?" 같은 질문 낱말 → 찾을 시설 코드.
 * 역 상세(5-2)와 AI 모드 편의시설 답변이 같이 씀. 코드는 메인 서버(load_detail_data.py) 기준 + 가짜 데이터용 WC·EV·ES.
 */
object FacilityNames {

    fun name(ctx: Context, f: Facility): String = when (f.code) {
        "WC", "toilet" -> ctx.getString(R.string.detail_fac_wc)
        "EV", "elevator" -> ctx.getString(R.string.detail_fac_ev)
        "ES", "escalator" -> ctx.getString(R.string.detail_fac_es)
        "wheelchair_lift" -> ctx.getString(R.string.detail_fac_wheelchair_lift)
        "nursing_room" -> ctx.getString(R.string.detail_fac_nursing_room)
        "meeting_place" -> ctx.getString(R.string.detail_fac_meeting_place)
        "cultural_space" -> ctx.getString(R.string.detail_fac_cultural_space)
        "transfer_parking" -> ctx.getString(R.string.detail_fac_transfer_parking)
        "bike_parking" -> ctx.getString(R.string.detail_fac_bike_parking)
        "manned_luggage", "locker" -> ctx.getString(R.string.detail_fac_manned_luggage)
        "unmanned_civil_service" -> ctx.getString(R.string.detail_fac_civil_kiosk)
        "exchange_kiosk" -> ctx.getString(R.string.detail_fac_exchange_kiosk)
        else -> f.name
    }

    /** 역 상세 왼쪽 네모 배지: 2~3글자 약칭 (서버 코드 "nursing_room"이 그대로 찍혀 넘치던 것 방지) */
    fun badge(f: Facility): String {
        val en = StationNames.english
        return when (f.code) {
            "WC", "toilet" -> "WC"
            "EV", "elevator" -> "EV"
            "ES", "escalator" -> "ES"
            "wheelchair_lift" -> "♿"
            "nursing_room" -> if (en) "BABY" else "수유"
            "meeting_place" -> if (en) "MEET" else "만남"
            "cultural_space" -> if (en) "ART" else "문화"
            "transfer_parking" -> "P"
            "bike_parking" -> if (en) "BIKE" else "자전거"
            "manned_luggage", "locker" -> if (en) "BAG" else "보관"
            "unmanned_civil_service" -> if (en) "DOC" else "민원"
            "exchange_kiosk" -> "₩$"
            else -> f.name.take(2)
        }
    }

    /** 질문에서 찾는 시설 종류: 낱말 → (코드들, 이름 문자열) */
    class Kind(val words: Regex, val codes: Set<String>, val label: Int)

    val kinds = listOf(
        Kind(Regex("화장실|restroom|toilet|bathroom", RegexOption.IGNORE_CASE), setOf("WC", "toilet"), R.string.detail_fac_wc),
        Kind(Regex("엘리베이터|엘베|승강기|elevator|lift", RegexOption.IGNORE_CASE), setOf("EV", "elevator", "wheelchair_lift"), R.string.detail_fac_ev),
        Kind(Regex("에스컬레이터|escalator", RegexOption.IGNORE_CASE), setOf("ES", "escalator"), R.string.detail_fac_es),
        Kind(Regex("수유실|nursing", RegexOption.IGNORE_CASE), setOf("nursing_room"), R.string.detail_fac_nursing_room),
        Kind(Regex("보관함|보관소|짐|locker|luggage", RegexOption.IGNORE_CASE), setOf("manned_luggage", "locker"), R.string.detail_fac_manned_luggage),
        Kind(Regex("민원|발급기|civil", RegexOption.IGNORE_CASE), setOf("unmanned_civil_service"), R.string.detail_fac_civil_kiosk),
        Kind(Regex("주차|parking", RegexOption.IGNORE_CASE), setOf("transfer_parking"), R.string.detail_fac_transfer_parking),
        Kind(Regex("자전거|bike|bicycle", RegexOption.IGNORE_CASE), setOf("bike_parking"), R.string.detail_fac_bike_parking),
        Kind(Regex("환전|exchange", RegexOption.IGNORE_CASE), setOf("exchange_kiosk"), R.string.detail_fac_exchange_kiosk),
    )

    /** "어디 있어?" 처럼 위치를 묻는 말 (경로 조건 "엘리베이터만 타고 가고 싶어"와 구분) */
    val asksWhere = Regex("어디|위치|있어|있나|있니|있을까|있어요|찾아|where|is there|any ", RegexOption.IGNORE_CASE)

    fun kindOf(text: String): Kind? = kinds.firstOrNull { it.words.containsMatchIn(text) }
}
