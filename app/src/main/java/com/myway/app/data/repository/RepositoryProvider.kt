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

    fun stationDetailRepository(): StationDetailRepository =
        if (USE_FAKE) FakeStationDetailRepository() else RealStationDetailRepository(RetrofitClient.api)
}