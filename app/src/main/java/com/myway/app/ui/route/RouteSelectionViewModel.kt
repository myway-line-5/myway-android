package com.myway.app.ui.route

import androidx.lifecycle.ViewModel
import com.myway.app.data.local.LocalStore
import com.myway.app.domain.model.Constraints
import com.myway.app.domain.model.Preference
import com.myway.app.domain.model.RouteType
import com.myway.app.ui.common.toSaved
import com.myway.app.ui.search.StationGroupItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 출발·도착 지정 상태. 화면(검색, 홈, 역 선택 시트)이 함께 쓰도록 Activity 범위로 공유한다.
 * 선택은 역 이름이 아니라 StationGroupItem(노선별 stationId 묶음)으로 보관한다.
 */
class RouteSelectionViewModel : ViewModel() {

    enum class Slot { ORIGIN, DESTINATION }

    data class Points(
        val origin: StationGroupItem? = null,
        val destination: StationGroupItem? = null
    ) {
        val isComplete get() = origin != null && destination != null
        val isEmpty get() = origin == null && destination == null

        /** 하나만 정해졌을 때 남은 칸. 둘 다 비었거나 다 찼으면 null */
        val missing: Slot?
            get() = when {
                isEmpty || isComplete -> null
                origin == null -> Slot.ORIGIN
                else -> Slot.DESTINATION
            }
    }

    private val _points = MutableStateFlow(Points())
    val points: StateFlow<Points> = _points.asStateFlow()

    /** AI 모드에서 확인한 제한·선호. 사용자가 출발·도착을 직접 바꾸면 지운다 */
    data class AiExtras(
        val said: String,                       // 사용자가 한 말
        val constraints: Constraints,
        val preference: Preference?,
        val preferredType: RouteType?
    )
    private val _aiExtras = MutableStateFlow<AiExtras?>(null)
    val aiExtras: StateFlow<AiExtras?> = _aiExtras.asStateFlow()

    /** 2-9에서 고른 기준: 다음 결과 화면이 이 탭부터 (한 번 쓰면 사라짐) */
    private var pendingType: RouteType? = null
    fun setPreferredType(type: RouteType) { pendingType = type }
    fun consumePreferredType(): RouteType? = pendingType.also { pendingType = null }

    fun setAiRoute(origin: StationGroupItem, destination: StationGroupItem, extras: AiExtras) {
        _aiExtras.value = extras
        _points.value = Points(origin, destination)
    }

    /** 역 선택 시트(1-12)에 띄울 역 */
    private val _focused = MutableStateFlow<StationGroupItem?>(null)
    val focused: StateFlow<StationGroupItem?> = _focused.asStateFlow()

    fun focus(item: StationGroupItem) { _focused.value = item }

    /** @return 반대편과 같은 역이면 false (지정하지 않음) */
    fun set(slot: Slot, item: StationGroupItem): Boolean {
        val p = _points.value
        val other = if (slot == Slot.ORIGIN) p.destination else p.origin
        if (other != null && other.sameStationAs(item)) return false
        _aiExtras.value = null
        _points.value = if (slot == Slot.ORIGIN) p.copy(origin = item) else p.copy(destination = item)
        LocalStore.addRecentStation(item.toSaved())          // 1-10 최근 검색
        return true
    }

    /** 최근 경로·즐겨찾기에서 바로 길찾기 */
    fun setRoute(origin: StationGroupItem, destination: StationGroupItem) {
        _aiExtras.value = null
        _points.value = Points(origin, destination)
    }

    /** 새 검색 시작: 지난 검색이 끝난(둘 다 찬) 상태면 비움. 하나만 고른 상태는 이어서 쓰도록 유지 */
    fun startFresh() {
        if (_points.value.isComplete) { _points.value = Points(); _aiExtras.value = null }
    }

    fun clear(slot: Slot) = _points.update {
        if (slot == Slot.ORIGIN) it.copy(origin = null) else it.copy(destination = null)
    }

    fun swap() = _points.update { Points(origin = it.destination, destination = it.origin) }

    private fun StationGroupItem.sameStationAs(other: StationGroupItem): Boolean {
        val ids = stations.map { it.stationId }.toSet()
        return other.stations.any { it.stationId in ids }
    }
}
