package com.myway.app.data.repository

import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.RouteResult
import com.myway.app.domain.model.RouteSegment
import com.myway.app.domain.model.RouteType
import com.myway.app.domain.model.Station
import kotlinx.coroutines.delay

class FakeRouteRepository : RouteRepository {

    // ---- 샘플 역: 서버 data/stations.csv·segments.csv 의 실제 ID (2·5호선) ----
    private val jongno3Line5 = Station("L5_MAIN_025", "종로3가", "5", true)
    private val euljiro4Line5 = Station("L5_MAIN_026", "을지로4가", "5", true)
    private val euljiro4Line2 = Station("L2_MAIN_004", "을지로4가", "2", true)
    private val euljiro3Line2 = Station("L2_MAIN_003", "을지로3가", "2", true)

    // ---- 샘플 경로: 종로3가(5) -> 을지로4가(5) -> 환승 -> 을지로4가(2) -> 을지로3가(2) ----
    // 시간·거리는 서버 segments.csv / transfers.csv 값 (환승 64초·77m)
    private val segments = listOf(
        RouteSegment(jongno3Line5, euljiro4Line5, "5", seconds = 100, meters = 1000, isTransfer = false),
        RouteSegment(euljiro4Line5, euljiro4Line2, "2", seconds = 64, meters = 77, isTransfer = true),
        RouteSegment(euljiro4Line2, euljiro3Line2, "2", seconds = 60, meters = 600, isTransfer = false)
    )

    // 구간 합계로 총합을 계산하므로 숫자가 서로 어긋나지 않는다
    // totalFareKrw 는 임시값. 운임 규칙(FarePolicy)이 서버에서 확정되면 교체
    private fun result(type: RouteType) = RouteResult(
        type = type,
        segments = segments,
        totalSeconds = segments.sumOf { it.seconds },
        totalMeters = segments.sumOf { it.meters },
        totalFareKrw = 1550,
        transferCount = segments.count { it.isTransfer },
        dataVersion = "fake-data-v1",
        fareVersion = "fake-fare-v1"
    )

    override suspend fun findRoutes(condition: RouteCondition): Resource<List<RouteResult>> {
        delay(300) // 네트워크 대기처럼 보이게. 로딩 화면 확인용

        // 출발/도착이 비어 있으면 탐색 불가 (UI는 버튼을 비활성화해야 하지만 방어용)
        if (condition.origin == null || condition.destination == null) {
            return Resource.Failure(AppError.UnsupportedCondition)
        }

        return Resource.Success(
            listOf(
                result(RouteType.SHORTEST_TIME),
                result(RouteType.MIN_FARE),
                result(RouteType.MIN_TRANSFERS)
            )
        )
    }
}