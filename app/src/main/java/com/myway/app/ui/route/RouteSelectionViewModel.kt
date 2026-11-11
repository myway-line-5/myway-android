package com.myway.app.ui.route

import androidx.lifecycle.ViewModel
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

    /** 역 선택 시트(1-12)에 띄울 역 */
    private val _focused = MutableStateFlow<StationGroupItem?>(null)
    val focused: StateFlow<StationGroupItem?> = _focused.asStateFlow()

    fun focus(item: StationGroupItem) { _focused.value = item }

    /** @return 반대편과 같은 역이면 false (지정하지 않음) */
    fun set(slot: Slot, item: StationGroupItem): Boolean {
        val p = _points.value
        val other = if (slot == Slot.ORIGIN) p.destination else p.origin
        if (other != null && other.sameStationAs(item)) return false
        _points.value = if (slot == Slot.ORIGIN) p.copy(origin = item) else p.copy(destination = item)
        return true
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
