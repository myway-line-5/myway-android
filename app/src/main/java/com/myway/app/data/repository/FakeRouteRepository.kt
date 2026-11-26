package com.myway.app.data.repository

import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.Criterion
import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.RouteResult
import com.myway.app.domain.model.RouteSegment
import com.myway.app.domain.model.RouteType
import com.myway.app.domain.model.Station
import com.myway.app.domain.model.StationSlot
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.home.MapTracks
import kotlinx.coroutines.delay
import java.util.PriorityQueue

/**
 * 서버 연결 전 시연용: 2·5·9호선 노선도의 역 연결(MapTracks)로 실제 경로를 앱 안에서 계산한다.
 * - 경로(갈아타는 역)는 정확. 시간·거리·요금은 추정치
 *   (역 간 시간 = 노선도 선로 길이 비율로 약 1~3.5분, 환승 = 도보 3분, 요금 = 수도권 거리 비례제)
 * - 서버가 붙으면 RealRouteRepository 가 대신하므로 이 파일은 시연·테스트용으로만 남는다.
 */
class FakeRouteRepository : RouteRepository {

    override suspend fun findRoutes(condition: RouteCondition): Resource<List<RouteResult>> {
        delay(300) // 네트워크 대기처럼 보이게. 로딩 화면 확인용

        val from = condition.origin?.name() ?: return Resource.Failure(AppError.UnsupportedCondition)
        val to = condition.destination?.name() ?: return Resource.Failure(AppError.UnsupportedCondition)
        if (from == to) return Resource.Failure(AppError.UnsupportedCondition)
        val stations = (FakeStationRepository().getStations() as? Resource.Success)?.data.orEmpty()
            .associateBy { LineStyle.key(it.line) to it.name }

        val fastest = search(from, to, RouteType.SHORTEST_TIME)?.let { result(RouteType.SHORTEST_TIME, it, stations) }
            ?: return Resource.Failure(AppError.RouteNotFound)
        // 다른 기준 경로가 더 나을 게 없으면(요금 같음·환승 같음) 더 빠른 최단시간 경로를 그대로 씀
        val cheap = search(from, to, RouteType.MIN_FARE)?.let { result(RouteType.MIN_FARE, it, stations) }
            ?.takeIf { it.totalFareKrw < fastest.totalFareKrw } ?: fastest.copy(type = RouteType.MIN_FARE)
        val few = search(from, to, RouteType.MIN_TRANSFERS)?.let { result(RouteType.MIN_TRANSFERS, it, stations) }
            ?.takeIf { it.transferCount < fastest.transferCount } ?: fastest.copy(type = RouteType.MIN_TRANSFERS)
        val base = listOf(fastest, cheap, few)

        // 선호가 있으면 세 후보 중 우선순위대로 가장 맞는 것을 '맞춤' 결과로 (서버 규칙 흉내)
        val pref = condition.preference?.let { p ->
            val order = p.priority.ifEmpty { listOf(Criterion.DURATION) }
            base.sortedWith(order.map { c -> compareBy<RouteResult> { r -> score(r, c) } }
                .reduce { a, b -> a.thenComparing(b) }).first().copy(type = RouteType.PREFERENCE)
        }

        val c = condition.constraints
        val routes = (base + listOfNotNull(pref)).filter { r ->
            (c.maxDurationMinutes?.let { r.totalSeconds <= it * 60 } ?: true) &&
                (c.maxTransfers?.let { r.transferCount <= it } ?: true) &&
                (c.maxFareKrw?.let { r.totalFareKrw <= it } ?: true)
        }
        return if (routes.isEmpty()) Resource.Failure(AppError.RouteNotFound) else Resource.Success(routes)
    }

    private fun StationSlot.name() = (station?.name ?: suggestedName ?: rawName).replace(" ", "").ifBlank { null }

    private fun score(r: RouteResult, c: Criterion): Int = when (c) {
        Criterion.DURATION -> r.totalSeconds
        Criterion.TRANSFERS -> r.transferCount
        Criterion.FARE -> r.totalFareKrw
    }

    // ---- 그래프: 노드 = "호선|역", 간선 = 같은 노선 이웃 역 + 같은 이름 다른 노선(환승) ----

    private class Edge(val to: String, val seconds: Int, val meters: Int, val transfer: Boolean)

    /** 기준별 비용: 최단시간 = 시간, 최소환승 = 환승 수 → 시간, 최소비용 = 거리(요금) → 시간 */
    private fun cost(e: Edge, type: RouteType): Long = when (type) {
        RouteType.MIN_TRANSFERS -> (if (e.transfer) 1_000_000L else 0L) + e.seconds
        RouteType.MIN_FARE -> e.meters * 1_000L + e.seconds
        else -> e.seconds.toLong()
    }

    /** 다익스트라. 출발역의 모든 노선에서 시작(첫 환승 없음), 도착역 어느 노선이든 닿으면 끝 */
    private fun search(from: String, to: String, type: RouteType): List<Pair<String, Edge>>? {
        val starts = graph.keys.filter { it.substringAfter('|') == from }
        if (starts.isEmpty() || graph.keys.none { it.substringAfter('|') == to }) return null
        val dist = HashMap<String, Long>(); val prev = HashMap<String, Pair<String, Edge>>()
        val q = PriorityQueue<Pair<Long, String>>(compareBy { it.first })
        starts.forEach { dist[it] = 0L; q += 0L to it }
        while (q.isNotEmpty()) {
            val (d, u) = q.poll()
            if (d > (dist[u] ?: Long.MAX_VALUE)) continue
            if (u.substringAfter('|') == to) {
                val path = ArrayList<Pair<String, Edge>>()
                var cur = u
                while (true) { val p = prev[cur] ?: break; path += p; cur = p.first }
                return path.reversed()
            }
            for (e in graph[u].orEmpty()) {
                val nd = d + cost(e, type)
                if (nd < (dist[e.to] ?: Long.MAX_VALUE)) { dist[e.to] = nd; prev[e.to] = u to e; q += nd to e.to }
            }
        }
        return null
    }

    private fun result(type: RouteType, path: List<Pair<String, Edge>>, stations: Map<Pair<String, String>, Station>): RouteResult {
        fun st(node: String): Station {
            val line = node.substringBefore('|'); val name = node.substringAfter('|')
            return stations[line to name] ?: Station("map:$line:$name", name, line, true)
        }
        val segments = path.map { (u, e) ->
            RouteSegment(st(u), st(e.to), e.to.substringBefore('|'), e.seconds, e.meters, e.transfer)
        }
        val meters = segments.sumOf { it.meters }
        return RouteResult(
            type = type,
            segments = segments,
            totalSeconds = segments.sumOf { it.seconds },
            totalMeters = meters,
            totalFareKrw = fare(meters),
            transferCount = segments.count { it.isTransfer },
            fareVersion = "fare_250628"
        )
    }

    private companion object {
        const val TRANSFER_SECONDS = 180
        const val TRANSFER_METERS = 150

        /** 수도권 지하철 교통카드 성인: 10km까지 1,550원, 50km까지 5km마다 +100, 그 뒤 8km마다 +100 */
        fun fare(meters: Int): Int {
            val km = meters / 1000.0
            var f = 1550
            if (km > 10) f += 100 * kotlin.math.ceil((minOf(km, 50.0) - 10) / 5).toInt()
            if (km > 50) f += 100 * kotlin.math.ceil((km - 50) / 8).toInt()
            return f
        }

        /** 한 번만 만들어 재사용 */
        val graph: Map<String, List<Edge>> by lazy {
            val g = HashMap<String, MutableList<Edge>>()
            val avg = MapTracks.links.groupBy { it.line }.mapValues { (_, l) -> l.map { it.px }.average().toFloat() }
            for (l in MapTracks.links) {
                val r = l.px / (avg[l.line] ?: l.px)                      // 이 노선 평균 대비 길이
                val sec = (60 + 60 * r).toInt().coerceIn(80, 210)         // 평균 약 2분
                val m = (1200 * r).toInt().coerceIn(500, 3500)            // 평균 약 1.2km
                g.getOrPut("${l.line}|${l.a}") { mutableListOf() } += Edge("${l.line}|${l.b}", sec, m, false)
                g.getOrPut("${l.line}|${l.b}") { mutableListOf() } += Edge("${l.line}|${l.a}", sec, m, false)
            }
            // 환승: 같은 역 이름의 다른 노선끼리
            g.keys.toList().groupBy { it.substringAfter('|') }.values.filter { it.size > 1 }.forEach { same ->
                for (a in same) for (b in same) if (a != b)
                    g.getValue(a) += Edge(b, TRANSFER_SECONDS, TRANSFER_METERS, true)
            }
            g
        }
    }
}
