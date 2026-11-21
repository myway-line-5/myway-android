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
import com.myway.app.ui.route.RouteSelectionViewModel.AiExtras
import com.myway.app.ui.route.RouteSelectionViewModel.Points
import com.myway.app.domain.model.Constraints
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
        val departAt: LocalTime          // 지금 출발 기준 (시각 선택은 시간표 탐색 확정 전 비활성)
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
    private var lastExtras: AiExtras? = null

    /** 출발·도착(또는 AI 조건)이 바뀌면 새로 찾는다. 이전 요청의 늦은 응답은 버린다(cancel). */
    fun load(points: Points, extras: AiExtras? = lastExtras, force: Boolean = false) {
        val o = points.origin ?: return
        val d = points.destination ?: return
        if (!force && points == lastPoints && extras == lastExtras) return
        lastPoints = points
        lastExtras = extras

        job?.cancel()
        job = viewModelScope.launch {
            _state.value = RouteResultUiState.Loading
            // AI 모드에서 사용자가 확인한 제한·선호만 함께 보낸다
            val condition = RouteCondition(
                origin = o.toSlot(), destination = d.toSlot(),
                constraints = extras?.constraints ?: Constraints(),
                preference = extras?.preference
            )
            _state.value = when (val r = repo.findRoutes(condition)) {
                is Resource.Success ->
                    if (r.data.isEmpty()) RouteResultUiState.Error(AppError.RouteNotFound)
                    else RouteResultUiState.Success(
                        routes = r.data.associateBy { it.type },
                        departAt = LocalTime.now().withSecond(0).withNano(0)
                    ).also { s -> saveRecent(o, d, s) }
                is Resource.Failure -> RouteResultUiState.Error(r.error)
            }
        }
    }

    /** 1-10 최근 경로: 탐색에 성공한 출발·도착 쌍만 기록 (설계서 UC-10) */
    private fun saveRecent(o: StationGroupItem, d: StationGroupItem, s: RouteResultUiState.Success) {
        val best = s.routes[RouteType.SHORTEST_TIME] ?: s.routes.values.first()
        LocalStore.addRecentRoute(SavedRoute(o.toSaved(), d.toSaved(), best.type, Timeline.minutes(best.totalSeconds)))
    }

    /** ☆: 출발·도착 쌍을 즐겨찾기 (현재 탭 기준) */
    fun toggleFavorite(points: Points) {
        val o = points.origin ?: return
        val d = points.destination ?: return
        val s = _state.value as? RouteResultUiState.Success
        val route = s?.routes?.get(_selected.value)
        LocalStore.toggleFavoriteRoute(SavedRoute(o.toSaved(), d.toSaved(), _selected.value,
            route?.let { Timeline.minutes(it.totalSeconds) }))
    }

    fun retry() { lastPoints?.let { load(it, lastExtras, force = true) } }

    fun select(type: RouteType) { _selected.value = type }

    // TODO: 환승역 묶음(여의도 5·9)은 서버가 그룹으로 받게 되면 groupId 로 전달
    private fun StationGroupItem.toSlot() = StationSlot(
        rawName = name,
        suggestedName = null,
        station = activeStations.firstOrNull() ?: stations.first()
    )
}
