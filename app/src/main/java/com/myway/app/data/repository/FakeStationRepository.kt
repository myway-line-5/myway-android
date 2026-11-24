package com.myway.app.data.repository

import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.Station
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.home.MapStations

class FakeStationRepository : StationRepository {

    // 서버 data/stations.csv·segments.csv 의 실제 ID와 같은 형식 (line 은 "2" 처럼 숫자만)
    // stationId 는 노선별로 다름 (같은 이름이어도 노선이 다르면 다른 역)
    private val stations = listOf(
        // ---- 2호선 (실제 서버 ID) ----
        Station("L2_MAIN_002", "을지로입구", "2", true),
        Station("L2_MAIN_003", "을지로3가", "2", true),
        Station("L2_MAIN_004", "을지로4가", "2", true),             // 5호선과 환승 묶음
        Station("L2_MAIN_005", "동대문역사문화공원", "2", true),     // 5호선과 환승 묶음

        // ---- 5호선 (실제 서버 ID) ----
        Station("L5_MAIN_017", "여의도", "5", true),
        Station("L5_MAIN_018", "여의나루", "5", true),
        Station("L5_MAIN_020", "공덕", "5", true),
        Station("L5_MAIN_025", "종로3가", "5", true),
        Station("L5_MAIN_026", "을지로4가", "5", true),
        Station("L5_MAIN_027", "동대문역사문화공원", "5", true),

        // ---- 아래는 서버에 없는 가짜 케이스 (ID에 FAKE 표시) ----
        // 서버에 9호선 데이터가 아직 없음 → 5·9호선 환승 묶음 UI 확인용
        Station("L9_FAKE_001", "여의도", "9", true),
        // 앱 미지원 노선(3호선) 예시: 동명 다른 노선, 빨간 안내 행
        Station("L3_FAKE_001", "을지로3가", "3", true),
        // 비활성 역 예시
        Station("L9_FAKE_999", "시험용비활성역", "9", false)
    )

    // 노선도에 있는 2·5·9호선 역 전부 (위 목록에 없으면 임시 ID "map:호선:이름").
    // 검색·AI 해석이 노선도에서 고를 수 있는 역을 똑같이 알도록 (전엔 위 13개만 알아서 '강남' 등이 없다고 나옴)
    // RealRouteRepository 가 map: 역을 서버의 같은 이름·호선 역으로 바꿔 보낸다
    // 한 번만 만들어 모든 인스턴스가 같이 씀 (RepositoryProvider 가 부를 때마다 새로 만들지 않게)
    private val all: List<Station> get() = merged(stations)

    override suspend fun getStations(): Resource<List<Station>> {
        return Resource.Success(all)
    }

    private companion object {
        @Volatile private var cached: List<Station>? = null
        fun merged(base: List<Station>): List<Station> = cached ?: (base + MapStations.all
            .filter { m -> base.none { it.name == m.name && LineStyle.key(it.line) == m.line } }
            .distinctBy { it.line to it.name }
            .map { Station("map:${it.line}:${it.name}", it.name, it.line, true) }).also { cached = it }
    }

    override suspend fun searchStations(query: String): Resource<List<Station>> {
        val result = all.filter { it.name.contains(query.trim()) }
        return if (result.isEmpty()) {
            Resource.Failure(AppError.StationNotFound(query))
        } else {
            Resource.Success(result)
        }
    }
}