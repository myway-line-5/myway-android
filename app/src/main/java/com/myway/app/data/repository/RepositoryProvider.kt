package com.myway.app.data.repository

import com.myway.app.data.remote.RetrofitClient

object RepositoryProvider {
    // true: 서버 없이 Fake로 동작 / false: 로컬 서버에 연결
    const val USE_FAKE = true

    fun stationRepository(): StationRepository =
        if (USE_FAKE) FakeStationRepository() else RealStationRepository(RetrofitClient.api)

    fun routeRepository(): RouteRepository =
        if (USE_FAKE) FakeRouteRepository() else RealRouteRepository(RetrofitClient.api)

    fun conditionRepository(): ConditionRepository =
        if (USE_FAKE) FakeConditionRepository() else RealConditionRepository(RetrofitClient.api)

    // 역 정보 API(첫차·막차·시간표·편의시설) 서버 11/24 추가. 혼잡도는 서버 데이터 확보 전까지 빈 목록
    fun stationDetailRepository(): StationDetailRepository =
        if (USE_FAKE) FakeStationDetailRepository() else RealStationDetailRepository(RetrofitClient.api)
}
