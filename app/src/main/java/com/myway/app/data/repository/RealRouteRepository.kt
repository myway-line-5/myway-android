package com.myway.app.data.repository

import com.myway.app.data.remote.MyWayApi
import com.myway.app.data.remote.dto.RouteSearchRequestDto
import com.myway.app.data.remote.dto.RouteSearchResponseDto
import com.myway.app.data.remote.dto.toDomain
import com.myway.app.data.remote.safeApiCall
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.RouteResult
import com.myway.app.domain.model.RouteType
import com.myway.app.domain.model.Station
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class RealRouteRepository(
    private val api: MyWayApi
) : RouteRepository {

    // 탭 3개 = 서버 optimization 값 3개
    private enum class Pick(val optimization: String, val type: RouteType) {
        TIME("shortest_time", RouteType.SHORTEST_TIME),
        TRANSFER("minimum_transfer", RouteType.MIN_TRANSFERS),
        FARE("minimum_fare", RouteType.MIN_FARE)
    }

    override suspend fun findRoutes(condition: RouteCondition): Resource<List<RouteResult>> {
        val rawOrigin = condition.origin?.station
        val rawDestination = condition.destination?.station
        if (rawOrigin == null || rawDestination == null) {
            return Resource.Failure(AppError.UnsupportedCondition)
        }

        // 같은 이름의 서버 역을 모두 찾는다 (환승역이면 호선별로 여러 개)
        val origins = candidates(rawOrigin)
        val destinations = candidates(rawDestination)
        val pairs = origins.flatMap { o -> destinations.map { d -> o to d } }
            .filter { (o, d) -> o.stationId != d.stationId }
        android.util.Log.d(
            "MyWayRoute",
            "후보 출발=${origins.map { it.stationId }} 도착=${destinations.map { it.stationId }}"
        )
        if (pairs.isEmpty()) {
            android.util.Log.d("MyWayRoute", "서버에 없는 역 -> UnsupportedCondition")
            return Resource.Failure(AppError.UnsupportedCondition)
        }

        // 기준 3개 x 역 조합마다 서버에 물어본다 (제한 조건은 서버 세션에 이미 있다)
        val results = coroutineScope {
            Pick.values().flatMap { pick ->
                pairs.map { (o, d) ->
                    async {
                        val res = safeApiCall {
                            api.searchRoute(
                                RouteSearchRequestDto(o.stationId, d.stationId, pick.optimization)
                            )
                        }
                        val mapped: Resource<List<RouteResult>> = when (res) {
                            is Resource.Failure -> res
                            is Resource.Success -> res.data.toResource(o, d)
                        }
                        pick to mapped
                    }
                }
            }.awaitAll()
        }

        // 기준마다 성공한 것 중 가장 좋은 경로 1개를 고른다
        val picked = mutableListOf<RouteResult>()
        var firstFailure: Resource.Failure? = null
        for (pick in Pick.values()) {
            val routes = mutableListOf<RouteResult>()
            for ((p, r) in results) {
                if (p != pick) continue
                when (r) {
                    is Resource.Success -> routes += r.data
                    is Resource.Failure -> if (firstFailure == null) firstFailure = r
                }
            }
            routes.minWithOrNull(comparatorOf(pick))?.let {
                // 탭 키가 겹치지 않게 요청한 기준의 종류로 맞춘다
                picked += it.copy(type = pick.type)
            }
        }
        android.util.Log.d(
            "MyWayRoute",
            "탭 결과: " + picked.joinToString { "${it.type}=${it.totalSeconds}s/${it.transferCount}회/${it.totalFareKrw}원" }
        )
        if (picked.isNotEmpty()) return Resource.Success(picked)
        return firstFailure ?: Resource.Failure(AppError.Server)
    }

    // 기준별 비교: 최단시간=시간 -> 환승, 최소환승=환승 -> 시간, 최소비용=요금 -> 시간
    private fun comparatorOf(pick: Pick): Comparator<RouteResult> = when (pick) {
        Pick.TIME -> compareBy<RouteResult>({ it.totalSeconds }, { it.transferCount })
        Pick.TRANSFER -> compareBy<RouteResult>({ it.transferCount }, { it.totalSeconds })
        Pick.FARE -> compareBy<RouteResult>({ it.totalFareKrw }, { it.totalSeconds })
    }

    // 역 하나 -> 같은 이름의 서버 역 목록 (노선도 임시 역 "map:{호선}:{이름}"도 같은 방식)
    private suspend fun candidates(s: Station): List<Station> {
        val isMap = s.stationId.startsWith("map:")
        val name = if (isMap) {
            s.stationId.split(":", limit = 3).getOrNull(2) ?: return emptyList()
        } else s.name

        val res = safeApiCall { api.searchStations(name).toDomain() }
        val found = (res as? Resource.Success)?.data.orEmpty()
            // 서버 이름이 "흑석(중앙대입구)"처럼 괄호가 붙어도 같은 역으로 본다
            .filter { it.name == name || it.name.startsWith("$name(") }
        val all = if (!isMap && found.none { it.stationId == s.stationId }) found + s else found

        // 운행 중인 역이 있으면 그것만 쓰고, 없으면 그대로 둬서 서버가 비활성 오류를 내게 한다
        val active = all.filter { it.isActive }
        return if (active.isNotEmpty()) active else all
    }

    // 서버 status/error_code -> 앱 결과
    private fun RouteSearchResponseDto.toResource(
        origin: Station,
        destination: Station
    ): Resource<List<RouteResult>> = when (status) {
        "ok" -> {
            val result = route?.toDomain()
            if (result != null) Resource.Success(listOf(result))
            else {
                android.util.Log.e("MyWayRoute", "응답 변환 실패: $this")
                Resource.Failure(AppError.Server)
            }
        }
        "no_route" -> Resource.Failure(AppError.RouteNotFound)
        "incomplete" -> Resource.Failure(AppError.Server)
        "error" -> when (errorCode) {
            "STATION_INACTIVE" ->
                Resource.Failure(AppError.StationInactive(if (!origin.isActive) origin else destination))
            "STATION_NOT_FOUND" -> Resource.Failure(AppError.StationNotFound(origin.name))
            else -> Resource.Failure(AppError.UnsupportedCondition)
        }
        else -> Resource.Failure(AppError.Server)
    }
}