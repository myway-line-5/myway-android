package com.myway.app.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myway.app.data.local.LocalStore
import com.myway.app.data.local.SettingsStore
import com.myway.app.data.local.SavedRoute
import com.myway.app.data.repository.RepositoryProvider
import com.myway.app.ui.common.toSaved
import com.myway.app.data.repository.Resource
import com.myway.app.data.repository.RouteRepository
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.RouteResult
import com.myway.app.domain.model.RouteType
import com.myway.app.domain.model.StationSlot
import com.myway.app.data.remote.NetworkMonitor
import com.myway.app.ui.route.RouteSelectionViewModel.Conditions
import com.myway.app.ui.route.RouteSelectionViewModel.Points
import com.myway.app.ui.search.StationGroupItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime

sealed interface RouteResultUiState {
    data object Loading : RouteResultUiState
    data class Success(
        val routes: Map<RouteType, RouteResult>,
        val departAt: LocalTime,         // 지금 출발 기준 (시각 선택은 시간표 탐색 확정 전 비활성)
        val savedAt: Long? = null        // null 아님 = 오프라인이라 즐겨찾기에 저장한 경로를 보여 주는 중
    ) : RouteResultUiState
    data class Error(val error: AppError) : RouteResultUiState
}

class RouteResultViewModel(
    private val repo: RouteRepository = RepositoryProvider.routeRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<RouteResultUiState>(RouteResultUiState.Loading)
    val state: StateFlow<RouteResultUiState> = _state.asStateFlow()

    private val _selected = MutableStateFlow(SettingsStore.defaultRouteType)   // 설정 > 경로 기본 기준
    val selected: StateFlow<RouteType> = _selected.asStateFlow()

    private var job: Job? = null
    private var lastPoints: Points? = null
    private var lastCond: Conditions? = null

    /** 출발·도착 또는 조건이 바뀌면 새로 찾는다. 이전 요청의 늦은 응답은 버린다(cancel). */
    fun load(points: Points, cond: Conditions, force: Boolean = false) {
        val o = points.origin ?: return
        val d = points.destination ?: return
        if (!force && points == lastPoints && cond == lastCond) return
        val prefChanged = cond.preference != lastCond?.preference
        lastPoints = points
        lastCond = cond
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = RouteResultUiState.Loading

            // 오프라인: 탐색하지 않는다. 즐겨찾기에 경로째 저장돼 있으면 그것을 보여 줌 (설계서 2장·UC-09)
            if (!NetworkMonitor.isOnline) {
                val saved = LocalStore.savedSnapshot(o.name, d.name)
                val snap = saved?.snapshot
                _state.value = if (snap != null) {
                    _selected.value = snap.type
                    RouteResultUiState.Success(mapOf(snap.type to snap), now(), saved?.savedAt ?: 0L)
                } else RouteResultUiState.Error(AppError.Network)
                return@launch
            }

            val condition = RouteCondition(
                origin = o.toSlot(), destination = d.toSlot(),
                constraints = cond.constraints,
                preference = cond.preference
            )
            _state.value = when (val r = repo.findRoutes(condition)) {
                is Resource.Success ->
                    if (r.data.isEmpty()) RouteResultUiState.Error(AppError.RouteNotFound)
                    else {
                        val routes = r.data.associateBy { it.type }
                        // 선호를 새로 정했으면 '맞춤' 탭부터. 고른 탭이 없어졌으면 기본 기준으로
                        when {
                            prefChanged && RouteType.PREFERENCE in routes -> _selected.value = RouteType.PREFERENCE
                            _selected.value !in routes -> _selected.value =
                                SettingsStore.defaultRouteType.takeIf { it in routes } ?: routes.keys.first()
                        }
                        RouteResultUiState.Success(routes, now()).also { s -> saveRecent(o, d, s) }
                    }
                is Resource.Failure -> RouteResultUiState.Error(r.error)
            }
        }
    }

    private fun now() = LocalTime.now().withSecond(0).withNano(0)

    /** 1-10 최근 경로: 탐색에 성공한 출발·도착 쌍만 기록 (설계서 UC-10) */
    private fun saveRecent(o: StationGroupItem, d: StationGroupItem, s: RouteResultUiState.Success) {
        val best = s.routes[RouteType.SHORTEST_TIME] ?: s.routes.values.first()
        LocalStore.addRecentRoute(SavedRoute(o.toSaved(), d.toSaved(), best.type, Timeline.minutes(best.totalSeconds)))
    }

    /** ☆: 출발·도착 쌍 + 지금 보고 있는 경로 전체를 저장 (오프라인 재열람용) */
    fun toggleFavorite(points: Points) {
        val o = points.origin ?: return
        val d = points.destination ?: return
        val s = _state.value as? RouteResultUiState.Success
        val route = s?.routes?.get(_selected.value)
        LocalStore.toggleFavoriteRoute(SavedRoute(o.toSaved(), d.toSaved(), _selected.value,
            route?.let { Timeline.minutes(it.totalSeconds) },
            snapshot = route, savedAt = route?.let { System.currentTimeMillis() }))
    }

    /** 지금 보고 있는 경로 (지도 보기용) */
    fun currentRoute(): RouteResult? =
        (_state.value as? RouteResultUiState.Success)?.let { it.routes[_selected.value] ?: it.routes.values.first() }

    fun retry() {
        val p = lastPoints ?: return
        load(p, lastCond ?: Conditions(), force = true)
    }
    fun select(type: RouteType) { _selected.value = type }

    // TODO: 환승역 묶음(여의도 5·9)은 서버가 그룹으로 받게 되면 groupId 로 전달
    private fun StationGroupItem.toSlot() = StationSlot(
        rawName = name,
        suggestedName = null,
        station = activeStations.firstOrNull() ?: stations.first()
    )
}
