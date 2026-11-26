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
import com.myway.app.data.repository.Facility
import com.myway.app.ui.common.FacilityNames
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.common.StationNames
import com.myway.app.ui.home.MapTracks
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** AI 모드 화면 상태 (Figma 2-1~2-10) */
sealed interface AiUiState {
    data object Idle : AiUiState                                                  // 2-1, 2-2
    data class Listening(val heard: String) : AiUiState                           // 2-3 듣는 중 (들린 말 실시간)
    data object NoSpeech : AiUiState                                              // 2-7 음성 인식 실패
    data class Interpreting(val said: String) : AiUiState                         // 2-4
    data class Confirm(val said: String, val draft: AiDraft) : AiUiState          // 2-5
    data class Candidates(val said: String, val draft: AiDraft, val slot: Slot,
                          val query: String, val options: List<StationGroupItem>) : AiUiState   // 2-8
    data class Unsupported(val said: String) : AiUiState                          // 2-9
    data class NotUnderstood(val said: String) : AiUiState                        // 역을 하나도 못 찾음
    data class Failed(val said: String) : AiUiState                               // 2-10
    data class NextTrain(val said: String, val station: String, val trains: List<TrainEta>) : AiUiState   // 45 정형 '다음 열차'
    /** 편의시설 질문 답: label = 물어본 시설 이름(문자열 리소스), found = 그 시설이 있음(없으면 역의 다른 시설을 보여 줌) */
    data class FacilityAnswer(val said: String, val item: StationGroupItem, val label: Int,
                              val items: List<Facility>, val found: Boolean) : AiUiState
}

/** 한 방향의 곧 올 열차: 노선, 방면(다음 역), 첫 열차·그다음 열차까지 남은 초 */
data class TrainEta(val line: String, val toward: String, val seconds: Int, val nextSeconds: Int)

/** 확인 중인 조건 + 앱이 고른(확정한) 역 묶음 */
data class AiDraft(
    val condition: RouteCondition,
    val origin: StationGroupItem?,
    val destination: StationGroupItem?
) {
    /** 출발·도착이 같은 역 (AI가 한 역만 두 번 잡았거나 후보를 같은 역으로 고름) */
    val sameStation get() = origin != null && destination != null && origin.name == destination.name
    val ready get() = origin != null && destination != null && !sameStation
}

class AiModeViewModel(
    private val repo: ConditionRepository = RepositoryProvider.conditionRepository(),
    private val stations: com.myway.app.data.repository.StationRepository = RepositoryProvider.stationRepository(),
    private val details: com.myway.app.data.repository.StationDetailRepository = RepositoryProvider.stationDetailRepository()
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
        // '다음 열차 언제 와?' 같은 정형 질문은 AI를 부르지 않고 앱이 바로 답함 (설계서 기능 29·화면 45)
        if (NEXT_TRAIN.containsMatchIn(said)) { job = viewModelScope.launch { _state.value = nextTrain(said) }; return }
        // "왕십리 화장실 어디야?" 같은 편의시설 위치 질문도 AI 없이 역 정보(편의시설)로 바로 답함
        // ("엘리베이터만 타고 가고 싶어"처럼 위치를 묻지 않으면 기존대로 경로 조건 → 미지원 안내)
        FacilityNames.kindOf(said)?.takeIf { FacilityNames.asksWhere.containsMatchIn(said) }?.let { kind ->
            _state.value = AiUiState.Interpreting(said)
            job = viewModelScope.launch { _state.value = facility(said, kind) }
            return
        }
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

    // ---- 음성 입력 (앱 안에서 듣기, 2-3·2-7) ----
    fun startListening() { job?.cancel(); _state.value = AiUiState.Listening("") }
    fun heard(text: String) { if (_state.value is AiUiState.Listening) _state.value = AiUiState.Listening(text) }
    fun noSpeech() { _state.value = AiUiState.NoSpeech }

    fun reset() { job?.cancel(); kept = null; lastSaid = ""; _state.value = AiUiState.Idle }

    /** 2-8 후보 선택 */
    fun choose(item: StationGroupItem) {
        val s = _state.value as? AiUiState.Candidates ?: return
        val d = if (s.slot == Slot.ORIGIN) s.draft.copy(origin = item) else s.draft.copy(destination = item)
        _state.value = next(s.said, d)
    }

    /**
     * 역의 방향별 곧 올 열차. 역 이름이 없으면 방금 확인한 출발역 기준.
     * 실시간 도착 API(서버) 전이라 역·방향마다 고정된 추정값을 쓴다 → 서버 연결 시 이 부분만 바꾸면 됨
     */
    private suspend fun nextTrain(said: String): AiUiState {
        val name = stationIn(said, allStations()) ?: return AiUiState.NotUnderstood(said)
        val trains = MapTracks.links.filter { it.a == name || it.b == name }.map { l ->
            val toward = if (l.a == name) l.b else l.a
            val seed = (name + l.line + toward).hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
            val first = 40 + seed % 260
            TrainEta(l.line, toward, first, first + 300 + seed % 180)
        }.sortedWith(compareBy({ it.line }, { it.seconds }))
        return if (trains.isEmpty()) AiUiState.NotUnderstood(said) else AiUiState.NextTrain(said, name, trains)
    }

    /** 편의시설 답: 역 상세(편의시설)를 받아 물어본 종류만 추림. 없으면 그 역의 다른 시설을 대신 보여 줌 */
    private suspend fun facility(said: String, kind: FacilityNames.Kind): AiUiState {
        val all = allStations()
        val name = stationIn(said, all) ?: return AiUiState.NotUnderstood(said)
        val group = all.filter { it.name == name }
        val st = group.firstOrNull { LineStyle.isSupported(it.line) } ?: group.firstOrNull() ?: return AiUiState.NotUnderstood(said)
        val detail = (details.getDetail(st) as? Resource.Success)?.data ?: return AiUiState.Failed(said)
        val hit = detail.facilities.filter { it.code in kind.codes }
        return AiUiState.FacilityAnswer(said, StationGroupItem(name, group), kind.label,
            hit.ifEmpty { detail.facilities }, hit.isNotEmpty())
    }

    private suspend fun allStations(): List<Station> = (stations.getStations() as? Resource.Success)?.data.orEmpty()

    /** 문장 속 역 이름 (긴 이름 먼저, 빈칸 무시). 없으면 방금 확인한 출발역 */
    private fun stationIn(said: String, all: List<Station>): String? {
        val key = said.replace(" ", "")
        return (if (said.any { it in 'a'..'z' || it in 'A'..'Z' }) StationNames.findIn(said).firstOrNull()?.first
            else all.map { it.name }.distinct().sortedByDescending { it.length }.firstOrNull { key.contains(it.replace(" ", "")) })
            ?: kept?.origin?.name
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
        // "을지로 3가"처럼 띄어 써도 같은 역 (빈칸 무시)
        val key = raw.replace(" ", "")
        val exact = groups.entries.firstOrNull { it.key.replace(" ", "") == key }
            ?: return groups.entries.singleOrNull()?.let { StationGroupItem(it.key, it.value) }
        return StationGroupItem(exact.key, exact.value)
    }

    /** 확정 안 된 칸이 있으면 후보 선택, 아니면 해석 확인 */
    private fun next(said: String, d: AiDraft): AiUiState {
        for (slot in listOf(Slot.ORIGIN, Slot.DESTINATION)) {
            val picked = if (slot == Slot.ORIGIN) d.origin else d.destination
            val raw = (if (slot == Slot.ORIGIN) d.condition.origin else d.condition.destination)?.rawName
            // 반대 칸에서 이미 고른 역은 후보에서 뺌 (같은 역 출발·도착 방지)
            val other = (if (slot == Slot.ORIGIN) d.destination else d.origin)?.name
            val options = pendingMatches[slot].orEmpty().groupBy { it.name }
                .filterKeys { it != other }.map { (n, l) -> StationGroupItem(n, l) }
            if (picked == null && raw != null && options.size > 1) {
                return AiUiState.Candidates(said, d, slot, raw, options)
            }
        }
        kept = d
        return AiUiState.Confirm(said, d)
    }

    companion object {
        /** 정형 '다음 열차' 질문 */
        private val NEXT_TRAIN = Regex("다음\\s*(열차|차|지하철)|열차\\s*(언제|몇\\s*분)|언제\\s*와|next train|when.*train", RegexOption.IGNORE_CASE)

        /** 첫 우선순위 → 결과 화면에서 먼저 보여 줄 탭 */
        fun preferredType(c: RouteCondition): RouteType? = when (c.preference?.priority?.firstOrNull()) {
            Criterion.TRANSFERS -> RouteType.MIN_TRANSFERS
            Criterion.FARE -> RouteType.MIN_FARE
            Criterion.DURATION -> RouteType.SHORTEST_TIME
            null -> null
        }
    }
}
