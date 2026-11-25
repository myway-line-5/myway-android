package com.myway.app.ui.trip

import com.myway.app.domain.model.RouteResult
import com.myway.app.domain.model.Station
import com.myway.app.ui.common.LineStyle

/** 한 번 타고 가는 구간: stops[0] 승차역 → stops.last 하차(환승)역 */
data class TripLeg(val line: String, val stops: List<Station>, val hops: List<Int>) {
    val board get() = stops.first()
    val alight get() = stops.last()
    /** 이 구간이 향하는 쪽 (실시간 방면 정보가 오기 전 임시 표기) */
    val toward get() = stops.last().name
}

/** 이동 중 안내용 경로: 구간들 + 구간 사이 도보 환승 시간 */
data class TripPlan(
    val origin: String,
    val destination: String,
    val legs: List<TripLeg>,
    val walks: List<Int>,            // walks[i] = legs[i] 뒤 도보(초)
    val totalSeconds: Int,
    val transferCount: Int
) {
    companion object {
        fun from(route: RouteResult): TripPlan? {
            val legs = mutableListOf<TripLeg>()
            val walks = mutableListOf<Int>()
            var stops = mutableListOf<Station>(); var hops = mutableListOf<Int>(); var line: String? = null
            fun close() {
                if (line != null && stops.size >= 2) { legs += TripLeg(line!!, stops, hops); walks += 0 }
                stops = mutableListOf(); hops = mutableListOf(); line = null
            }
            route.segments.forEach { s ->
                if (s.isTransfer) {
                    close(); if (walks.isNotEmpty()) walks[walks.lastIndex] = walks.last() + s.seconds
                } else {
                    val l = LineStyle.key(s.lineName)
                    if (line != null && line != l) close()
                    if (line == null) { line = l; stops += s.fromStation }
                    stops += s.toStation; hops += s.seconds
                }
            }
            close()
            if (legs.isEmpty()) return null
            return TripPlan(legs.first().board.name, legs.last().alight.name, legs, walks,
                route.totalSeconds, route.transferCount)
        }
    }
}

/** 결과 화면 → 열차 선택 → 안내 화면으로 넘기는 값 (화면 사이 임시 보관) */
object TripSession {
    var plan: TripPlan? = null
    /** 3-10 '경로 저장' (결과 화면의 즐겨찾기와 같은 동작) */
    var saveFavorite: (() -> Unit)? = null
    var isFavorite: (() -> Boolean)? = null
    /** 원래 경로 (안내 중 '경로 보기' 지도용) */
    var route: com.myway.app.domain.model.RouteResult? = null
    /** 고른 열차가 승차역에 오기까지 남은 시간(초). 이만큼 기다렸다가 안내 시작 */
    var firstWaitSeconds: Int = 0
    /** 말로 "시작"해서 들어옴 → 안내 화면에서 탈 열차를 말로 알려 줌 */
    var voiceStart = false
}
