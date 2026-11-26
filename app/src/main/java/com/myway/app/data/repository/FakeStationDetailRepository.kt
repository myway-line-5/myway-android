package com.myway.app.data.repository

import com.myway.app.domain.model.Station
import com.myway.app.ui.common.LineStyle
import kotlinx.coroutines.delay
import com.myway.app.ui.common.StationNames

/**
 * 가짜 역 상세. 서버(역 정보 API)가 생기면 RepositoryProvider에서 교체.
 * 숫자는 화면 확인용 예시이며 실제 시간표가 아님.
 */
class FakeStationDetailRepository : StationDetailRepository {

    override suspend fun getDetail(station: Station): Resource<StationDetail> {
        delay(250)
        val dirs = directions(station)
        val seed = (station.name.hashCode() and 0x7fffffff) % 6       // 역마다 조금씩 다르게
        val firstLast = dirs.mapIndexed { i, d ->
            FirstLast(d, hm(5 * 60 + 27 + seed + i * 4), hm(23 * 60 + 49 + seed * 3 - i * 11))
        }
        val timetable = DayType.entries.associateWith { day ->
            dirs.mapIndexed { i, d -> d to trains(5 * 60 + 27 + seed + i * 4, 23 * 60 + 49 + seed * 3 - i * 11, day) }.toMap()
        }
        return Resource.Success(
            StationDetail(
                station = station,
                firstLast = firstLast,
                timetable = timetable,
                congestion = CURVE.map { (it + seed * 2).coerceAtMost(100) },
                facilities = facilities(seed)
            )
        )
    }

    /** 가짜 편의시설: 역마다 출구 번호·칸 위치만 조금씩 다르게. 진짜는 서버가 서울교통공사 편의시설 API로 채움 */
    private fun facilities(seed: Int): List<Facility> = if (StationNames.english) listOf(
        Facility("WC", "Restroom", "Inside the gates · near platform ${seed + 2}-1"),
        Facility("EV", "Elevator", "Exit ${seed + 1} ↔ concourse ↔ platform"),
        Facility("ES", "Escalator", "Concourse ↔ platform · near car ${seed + 4}"),
        Facility("nursing_room", "Nursing room", "Concourse B1 · next to customer service"),
        Facility("manned_luggage", "Lockers", "Outside the gates · toward exit ${seed + 3}"),
        Facility("unmanned_civil_service", "Civil document kiosk", "Concourse B1 · near exit ${seed + 2}")
    ) else listOf(
        Facility("WC", "화장실", "개찰구 안쪽 · 승강장 ${seed + 2}-1 부근"),
        Facility("EV", "엘리베이터", "${seed + 1}번 출구 ↔ 대합실 ↔ 승강장"),
        Facility("ES", "에스컬레이터", "대합실 ↔ 승강장 · ${seed + 4}번 칸 부근"),
        Facility("nursing_room", "수유실", "지하 1층 대합실 · 고객안전실 옆"),
        Facility("manned_luggage", "물품보관함", "개찰구 바깥 · ${seed + 3}번 출구 방향"),
        Facility("unmanned_civil_service", "무인 민원발급기", "지하 1층 대합실 · ${seed + 2}번 출구 쪽")
    )

    private fun directions(s: Station): List<String> = when (LineStyle.key(s.line)) {
        "2" -> when (s.name) {
            in setOf("용답", "신답", "용두", "신설동") -> listOf("성수행", "신설동행")
            in setOf("도림천", "양천구청", "신정네거리", "까치산") -> listOf("신도림행", "까치산행")
            else -> listOf("내선순환", "외선순환")
        }
        "5" -> if (s.name in setOf("개롱", "거여", "마천")) listOf("방화행", "마천행") else listOf("방화행", "하남검단산행")
        "9" -> listOf("개화행", "중앙보훈병원행")
        else -> listOf("상행", "하행")
    }

    /** 출퇴근 시간엔 촘촘하게, 주말·공휴일엔 듬성하게 */
    private fun trains(first: Int, last: Int, day: DayType): List<String> {
        val out = mutableListOf<String>()
        var t = first
        while (t <= last) {
            out += hm(t)
            val h = (t / 60) % 24
            t += when (day) {
                DayType.WEEKDAY -> if (h in 7..9 || h in 18..20) 4 else 7
                DayType.SATURDAY -> 8
                DayType.HOLIDAY -> 9
            }
        }
        return out
    }

    private fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private companion object {
        // 0시 ~ 23시
        val CURVE = listOf(18, 13, 9, 6, 4, 3, 5, 7, 15, 30, 38, 27, 19, 17, 18, 20, 22, 24, 26, 30, 36, 40, 33, 25)
    }
}
