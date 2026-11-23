package com.myway.app.data.repository

import com.myway.app.data.remote.RetrofitClient

object RepositoryProvider {
    // true: 서버 없이 Fake로 동작 / false: 로컬 서버에 연결
    const val USE_FAKE = true

    fun stationRepository(): StationRepository =
        if (USE_FAKE) FakeStationRepository() else RealStationRepository(RetrofitClient.api)

    // TODO: RealRouteRepository 생기면 USE_FAKE로 전환
    fun routeRepository(): RouteRepository = FakeRouteRepository()

    // TODO: RealConditionRepository(메인 서버 /conditions/interpret) 생기면 USE_FAKE로 전환
    fun conditionRepository(): ConditionRepository = FakeConditionRepository()

    // TODO: 역 정보 API(첫차·막차·시간표·혼잡도·편의시설) 생기면 USE_FAKE로 전환
    fun stationDetailRepository(): StationDetailRepository = FakeStationDetailRepository()
}
