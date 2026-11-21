package com.myway.app.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myway.app.data.local.AppDatabase
import com.myway.app.data.repository.FavoriteRepository
import com.myway.app.data.repository.RepositoryProvider
import com.myway.app.data.repository.Resource
import com.myway.app.data.repository.RoomFavoriteRepository
import com.myway.app.data.repository.StationRepository
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.Station
import com.myway.app.ui.common.LineStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 목록 한 줄 = 물리적 역 하나. 여의도(5·9)처럼 환승역은 배지 여러 개로 한 줄에.
 * 선택 결과는 이름이 아니라 stations 의 id 목록으로 넘긴다.
 */
data class StationGroupItem(
    val name: String,
    val stations: List<Station>,          // 노선별 역 (id 서로 다름)
    val isFavorite: Boolean = false
) {
    /** 2·5·9호선이 아니면 미지원 노선 행 (빨간 안내, 선택 불가) */
    val isSupported get() = stations.all { LineStyle.isSupported(it.line) }
    val activeStations get() = stations.filter { it.isActive }
    val isActive get() = activeStations.isNotEmpty()
}

sealed interface SearchUiState {
    data object Idle : SearchUiState                                   // TODO: 1-10 최근 검색
    data object Loading : SearchUiState
    data class Success(val items: List<StationGroupItem>) : SearchUiState
    data class Empty(val query: String) : SearchUiState                // 1-15
    data class Error(val error: AppError) : SearchUiState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    // 자리는 StationRepository, 지금 앉힌 건 Fake. 서버 완성되면 RepositoryProvider 쪽만 교체.
    private val repo: StationRepository = RepositoryProvider.stationRepository()
    private val favoriteRepo: FavoriteRepository =
        RoomFavoriteRepository(AppDatabase.getInstance(application).favoriteDao())

    private val query = MutableStateFlow("")
    private val favorites: StateFlow<Set<String>> = favoriteRepo.observeFavoriteNames()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val favoriteNames: StateFlow<Set<String>> = favorites
    private var cachedStations: List<Station>? = null

    private val result: Flow<SearchUiState> = query
        .debounce(300)
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                if (q.isEmpty()) { emit(SearchUiState.Idle); return@flow }
                emit(SearchUiState.Loading)
                emit(search(q))
            }
        }

    val state: StateFlow<SearchUiState> = combine(result, favorites) { s, favs ->
        if (s is SearchUiState.Success)
            s.copy(items = s.items.map { it.copy(isFavorite = it.name in favs) })
        else s
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Idle)

    fun onQueryChange(text: String) { query.value = text }

    fun onToggleFavorite(item: StationGroupItem) {
        viewModelScope.launch { favoriteRepo.toggle(item.name) }
    }

    private suspend fun search(q: String): SearchUiState {
        val stations = if (Chosung.isChosungOnly(q)) {
            val all = cachedStations ?: when (val r = repo.getStations()) {
                is Resource.Success -> r.data.also { cachedStations = it }
                is Resource.Failure -> return SearchUiState.Error(r.error)
            }
            all.filter { Chosung.matches(it.name, q) }
        } else {
            when (val r = repo.searchStations(q)) {
                is Resource.Success -> r.data
                is Resource.Failure ->
                    return if (r.error is AppError.StationNotFound) SearchUiState.Empty(q)
                    else SearchUiState.Error(r.error)
            }
        }
        if (stations.isEmpty()) return SearchUiState.Empty(q)

        // TODO: Station 에 groupId 생기면 name 대신 groupId 로 묶기 (설계서 4.1.1)
        // 지원 노선(2·5·9)은 같은 역끼리 한 줄, 미지원 노선은 노선마다 따로 한 줄
        val items = stations
            .groupBy { if (LineStyle.isSupported(it.line)) it.name else "${it.name}#${it.stationId}" }
            .map { (_, list) -> StationGroupItem(list.first().name, list.sortedBy { LineKey.of(it) }) }
            .sortedBy { !it.isSupported }                   // 지원 노선 먼저
        return SearchUiState.Success(items)
    }

    private object LineKey {
        fun of(s: Station) = s.line.toString().filter(Char::isDigit).toIntOrNull() ?: 99
    }
}