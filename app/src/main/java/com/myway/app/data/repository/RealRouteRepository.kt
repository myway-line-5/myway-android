package com.myway.app.data.repository

import com.myway.app.data.remote.MyWayApi
import com.myway.app.data.remote.dto.RouteSearchRequestDto
import com.myway.app.data.remote.dto.RouteSearchResponseDto
import com.myway.app.data.remote.dto.toDomain
import com.myway.app.data.remote.safeApiCall
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.RouteResult
import com.myway.app.domain.model.Station
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class RealRouteRepository(
    private val api: MyWayApi
) : RouteRepository {

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

        // TODO: condition.constraints / preference 는 서버 요청에 실을 자리가 생기면 추가
        // 조합마다 서버에 물어본다
        val results = coroutineScope {
            pairs.map { (o, d) ->
                async<Resource<List<RouteResult>>> {
                    when (val res = safeApiCall {
                        api.searchRoute(RouteSearchRequestDto(o.stationId, d.stationId))
                    }) {
                        is Resource.Failure -> res
                        is Resource.Success -> res.data.toResource(o, d)
                    }
                }
            }.awaitAll()
        }

        // 성공한 것 중 가장 빠른 경로를 고른다. 전부 실패하면 첫 실패를 돌려준다
        val routes = mutableListOf<RouteResult>()
        var firstFailure: Resource.Failure? = null
        for (r in results) {
            when (r) {
                is Resource.Success -> routes += r.data
                is Resource.Failure -> if (firstFailure == null) firstFailure = r
            }
        }
        val best = routes.minWithOrNull(compareBy({ it.totalSeconds }, { it.transferCount }))
        if (best != null) return Resource.Success(listOf(best))
        return firstFailure ?: Resource.Failure(AppError.Server)
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