package com.myway.app.ui.aimode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myway.app.data.repository.ConditionRepository
import com.myway.app.data.repository.InterpretResult
import com.myway.app.data.repository.RepositoryProvider
import com.myway.app.data.repository.Resource
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.Criterion
import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.RouteType
import com.myway.app.domain.model.Station
import com.myway.app.ui.route.RouteSelectionViewModel.Slot
import com.myway.app.ui.search.StationGroupItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** AI 모드 화면 상태 (Figma 2-1~2-10) */
sealed interface AiUiState {
    data object Idle : AiUiState                                                  // 2-1, 2-2
    data class Interpreting(val said: String) : AiUiState                         // 2-4
    data class Confirm(val said: String, val draft: AiDraft) : AiUiState          // 2-5
    data class Candidates(val said: String, val draft: AiDraft, val slot: Slot,
                          val query: String, val options: List<StationGroupItem>) : AiUiState   // 2-8
    data class Unsupported(val said: String) : AiUiState                          // 2-9
    data class NotUnderstood(val said: String) : AiUiState                        // 역을 하나도 못 찾음
    data class Failed(val said: String) : AiUiState                               // 2-10
}

/** 확인 중인 조건 + 앱이 고른(확정한) 역 묶음 */
data class AiDraft(
    val condition: RouteCondition,
    val origin: StationGroupItem?,
    val destination: StationGroupItem?
) {
    val ready get() = origin != null && destination != null
}

class AiModeViewModel(
    private val repo: ConditionRepository = RepositoryProvider.conditionRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    /** 마지막으로 확인된 조건. 실패해도 이 값은 유지한다 (설계서: 기존 조건 유지) */
    private var kept: AiDraft? = null
    private var job: Job? = null
    private var lastSaid = ""

    fun submit(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        lastSaid = said
        job?.cancel()                                  // 늦게 온 이전 응답은 버림
        _state.value = AiUiState.Interpreting(said)
        job = viewModelScope.launch {
            _state.value = when (val r = repo.interpret(said, kept?.condition)) {
                is Resource.Success ->
                    // 출발·도착 어느 것도 못 알아들었으면 '다시 말해 주세요' (미지원 조건과 구분)
                    if (r.data.condition.origin == null && r.data.condition.destination == null) AiUiState.NotUnderstood(said)
                    else next(said, toDraft(r.data))
                is Resource.Failure -> when (r.error) {
                    AppError.UnsupportedCondition -> AiUiState.Unsupported(said)
                    else -> AiUiState.Failed(said)
                }
            }
        }
    }

    /** 2-4 취소: 요청을 버리고 이전 화면으로 */
    fun cancel() {
        job?.cancel()
        _state.value = kept?.let { AiUiState.Confirm(lastSaid, it) } ?: AiUiState.Idle
    }

    /** 2-10 'AI로 다시 시도': 사용자가 명시했을 때만 다시 호출 (자동 재전송 0회) */
    fun retry() { if (lastSaid.isNotEmpty()) submit(lastSaid) }

    /** 입력창에 다시 채워 줄 문장 (한 번 쓰면 비움) */
    var prefill: String? = null
        private set
    fun consumePrefill(): String? = prefill.also { prefill = null }

    /** '다시 입력하기': 방금 쓴 문장을 입력창에 남겨서 고쳐 쓰게 */
    fun editAgain() { prefill = lastSaid; backToInput() }

    /** 2-9 '다시 말하기': 입력으로 돌아가되 확인된 조건은 유지 */
    fun backToInput() { job?.cancel(); _state.value = AiUiState.Idle }

    fun reset() { job?.cancel(); kept = null; lastSaid = ""; _state.value = AiUiState.Idle }

    /** 2-8 후보 선택 */
    fun choose(item: StationGroupItem) {
        val s = _state.value as? AiUiState.Candidates ?: return
        val d = if (s.slot == Slot.ORIGIN) s.draft.copy(origin = item) else s.draft.copy(destination = item)
        _state.value = next(s.said, d)
    }

    private fun toDraft(r: InterpretResult) = AiDraft(
        condition = r.condition,
        origin = resolve(r.condition.origin?.rawName, r.originMatches),
        destination = resolve(r.condition.destination?.rawName, r.destinationMatches)
    ).also { pendingMatches = mapOf(Slot.ORIGIN to r.originMatches, Slot.DESTINATION to r.destinationMatches) }

    private var pendingMatches: Map<Slot, List<Station>> = emptyMap()

    /** 이름이 정확히 같은 한 곳이면 확정. 여러 곳이면 후보로 남김 */
    private fun resolve(raw: String?, matches: List<Station>): StationGroupItem? {
        if (raw == null) return null
        val groups = matches.groupBy { it.name }
        val exact = groups[raw] ?: return groups.entries.singleOrNull()?.let { StationGroupItem(it.key, it.value) }
        return StationGroupItem(raw, exact)
    }

    /** 확정 안 된 칸이 있으면 후보 선택, 아니면 해석 확인 */
    private fun next(said: String, d: AiDraft): AiUiState {
        for (slot in listOf(Slot.ORIGIN, Slot.DESTINATION)) {
            val picked = if (slot == Slot.ORIGIN) d.origin else d.destination
            val raw = (if (slot == Slot.ORIGIN) d.condition.origin else d.condition.destination)?.rawName
            val options = pendingMatches[slot].orEmpty().groupBy { it.name }.map { (n, l) -> StationGroupItem(n, l) }
            if (picked == null && raw != null && options.size > 1) {
                return AiUiState.Candidates(said, d, slot, raw, options)
            }
        }
        kept = d
        return AiUiState.Confirm(said, d)
    }

    companion object {
        /** 첫 우선순위 → 결과 화면에서 먼저 보여 줄 탭 */
        fun preferredType(c: RouteCondition): RouteType? = when (c.preference?.priority?.firstOrNull()) {
            Criterion.TRANSFERS -> RouteType.MIN_TRANSFERS
            Criterion.FARE -> RouteType.MIN_FARE
            Criterion.DURATION -> RouteType.SHORTEST_TIME
            null -> null
        }
    }
}
