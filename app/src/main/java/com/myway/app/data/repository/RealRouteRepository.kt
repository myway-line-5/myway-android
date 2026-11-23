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

class RealRouteRepository(
    private val api: MyWayApi
) : RouteRepository {

    override suspend fun findRoutes(condition: RouteCondition): Resource<List<RouteResult>> {
        val rawOrigin = condition.origin?.station
        val rawDestination = condition.destination?.station
        if (rawOrigin == null || rawDestination == null) {
            return Resource.Failure(AppError.UnsupportedCondition)
        }

        // 노선도에서 만든 임시 역(map:)은 서버의 같은 이름·호선 역으로 바꿔서 보낸다
        val origin = resolve(rawOrigin)
        val destination = resolve(rawDestination)
        android.util.Log.d("MyWayRoute", "요청 origin=${origin?.stationId} destination=${destination?.stationId}")
        if (origin == null || destination == null) {
            android.util.Log.d("MyWayRoute", "서버에 없는 역 -> UnsupportedCondition")
            return Resource.Failure(AppError.UnsupportedCondition)
        }

        // TODO: condition.constraints / preference 는 서버 요청에 실을 자리가 생기면 추가
        return when (val res = safeApiCall {
            api.searchRoute(RouteSearchRequestDto(origin.stationId, destination.stationId))
        }) {
            is Resource.Failure -> res
            is Resource.Success -> res.data.toResource(origin, destination)
        }
    }

    // "map:{호선}:{이름}" 임시 역 -> 서버에서 같은 이름·호선 역을 찾는다. 못 찾으면 null
    private suspend fun resolve(s: Station): Station? {
        if (!s.stationId.startsWith("map:")) return s
        val parts = s.stationId.split(":", limit = 3)
        if (parts.size < 3) return null
        val line = parts[1]
        val name = parts[2]
        val res = safeApiCall { api.searchStations(name).toDomain() }
        return (res as? Resource.Success)?.data?.firstOrNull { it.name == name && it.line == line }
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