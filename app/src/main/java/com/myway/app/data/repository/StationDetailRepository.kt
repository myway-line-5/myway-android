package com.myway.app.data.repository

import com.myway.app.domain.model.Station

/** 역 상세(5-2): 첫차·막차, 시간표, 시간대별 혼잡도, 편의시설. 설계서 기능 13 '역 정보 저장·조회' */
enum class DayType { WEEKDAY, SATURDAY, HOLIDAY }

data class FirstLast(val direction: String, val first: String, val last: String)

data class Facility(val code: String, val name: String, val where: String)

data class StationDetail(
    val station: Station,
    val firstLast: List<FirstLast>,
    /** 요일 → 방면 → 출발 시각("HH:mm", 자정 넘김은 24:xx) */
    val timetable: Map<DayType, Map<String, List<String>>>,
    /** 0~23시 혼잡도 0~100 (평일) */
    val congestion: List<Int>,
    val facilities: List<Facility>
)

interface StationDetailRepository {
    suspend fun getDetail(station: Station): Resource<StationDetail>
}
