package com.myway.app.data.repository

import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.RouteResult
import com.myway.app.domain.model.RouteSegment
import com.myway.app.domain.model.RouteType
import com.myway.app.domain.model.Station
import kotlinx.coroutines.delay

class FakeRouteRepository : RouteRepository {

    // ---- 샘플 역 (진짜 서버 ID가 아님. 화면 확인용) ----
    private val yeouido = Station("L5_FAKE_01", "여의도", "5", true)
    private val gongdeok = Station("L5_FAKE_02", "공덕", "5", true)
    private val euljiro4Line5 = Station("L5_FAKE_03", "을지로4가", "5", true)
    private val euljiro4Line2 = Station("L2_FAKE_01", "을지로4가", "2", true)
    private val euljiro3Line2 = Station("L2_FAKE_02", "을지로3가", "2", true)

    // ---- 샘플 경로: 여의도(5) -> 공덕 -> 을지로4가(5) -> 환승 -> 을지로4가(2) -> 을지로3가 ----
    private val segments = listOf(
        RouteSegment(yeouido, gongdeok, "5", seconds = 540, meters = 4200, isTransfer = false),
        RouteSegment(gongdeok, euljiro4Line5, "5", seconds = 900, meters = 6100, isTransfer = false),
        RouteSegment(euljiro4Line5, euljiro4Line2, "2", seconds = 240, meters = 200, isTransfer = true),
        RouteSegment(euljiro4Line2, euljiro3Line2, "2", seconds = 120, meters = 900, isTransfer = false)
    )

    // 구간 합계로 총합을 계산하므로 숫자가 서로 어긋나지 않는다
    private fun result(type: RouteType) = RouteResult(
        type = type,
        segments = segments,
        totalSeconds = segments.sumOf { it.seconds },
        totalMeters = segments.sumOf { it.meters },
        totalFareKrw = 1550,
        transferCount = segments.count { it.isTransfer }
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