package com.myway.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.myway.app.domain.model.RouteResult
import com.myway.app.domain.model.RouteSegment
import com.myway.app.domain.model.RouteType
import com.myway.app.domain.model.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 같은 장소의 역 묶음 (여의도 5·9). 이름만으로 구분하지 않도록 노선별 Station 을 함께 저장 */
data class SavedStation(val name: String, val stations: List<Station>)

data class SavedRoute(
    val origin: SavedStation,
    val destination: SavedStation,
    val type: RouteType = RouteType.SHORTEST_TIME,
    val minutes: Int? = null,
    val label: String? = null,         // "출근길" 같은 이름 (선택)
    val snapshot: RouteResult? = null, // 즐겨찾기할 때의 경로 전체 → 오프라인에서도 다시 보기 (UC-09)
    val savedAt: Long? = null          // 스냅샷 저장 시각(ms)
) {
    fun samePair(o: String, d: String) = origin.name == o && destination.name == d
}

/**
 * 최근 검색·즐겨찾기 로컬 저장소 (임시: SharedPreferences + JSON).
 * TODO: Room(설계서 SQLite DAO)으로 교체해도 화면은 이 함수들만 쓰므로 그대로 둔다.
 */
object LocalStore {

    private const val MAX_RECENT_STATIONS = 10
    private const val MAX_RECENT_ROUTES = 5

    private var prefs: SharedPreferences? = null

    private val _recentStations = MutableStateFlow<List<SavedStation>>(emptyList())
    private val _recentRoutes = MutableStateFlow<List<SavedRoute>>(emptyList())
    private val _favoriteStations = MutableStateFlow<List<SavedStation>>(emptyList())
    private val _favoriteRoutes = MutableStateFlow<List<SavedRoute>>(emptyList())

    val recentStations: StateFlow<List<SavedStation>> = _recentStations.asStateFlow()
    val recentRoutes: StateFlow<List<SavedRoute>> = _recentRoutes.asStateFlow()
    val favoriteStations: StateFlow<List<SavedStation>> = _favoriteStations.asStateFlow()
    val favoriteRoutes: StateFlow<List<SavedRoute>> = _favoriteRoutes.asStateFlow()

    /** MainActivity.onCreate 에서 한 번 */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("myway_local", Context.MODE_PRIVATE)
        _recentStations.value = readStations(K_RECENT_STATIONS)
        _recentRoutes.value = readRoutes(K_RECENT_ROUTES)
        _favoriteStations.value = readStations(K_FAV_STATIONS)
        _favoriteRoutes.value = readRoutes(K_FAV_ROUTES)
    }

    // ---------- 최근 검색 ----------
    fun addRecentStation(s: SavedStation) = setStations(K_RECENT_STATIONS, _recentStations,
        (listOf(s) + _recentStations.value.filterNot { it.name == s.name }).take(MAX_RECENT_STATIONS))

    fun removeRecentStation(name: String) =
        setStations(K_RECENT_STATIONS, _recentStations, _recentStations.value.filterNot { it.name == name })

    fun addRecentRoute(r: SavedRoute) = setRoutes(K_RECENT_ROUTES, _recentRoutes,
        (listOf(r) + _recentRoutes.value.filterNot { it.samePair(r.origin.name, r.destination.name) })
            .take(MAX_RECENT_ROUTES))

    fun removeRecentRoute(r: SavedRoute) = setRoutes(K_RECENT_ROUTES, _recentRoutes,
        _recentRoutes.value.filterNot { it.samePair(r.origin.name, r.destination.name) })

    fun clearRecent() {
        setStations(K_RECENT_STATIONS, _recentStations, emptyList())
        setRoutes(K_RECENT_ROUTES, _recentRoutes, emptyList())
    }

    // ---------- 즐겨찾기 ----------
    fun isFavoriteStation(name: String) = _favoriteStations.value.any { it.name == name }

    fun toggleFavoriteStation(s: SavedStation) = setStations(K_FAV_STATIONS, _favoriteStations,
        if (isFavoriteStation(s.name)) _favoriteStations.value.filterNot { it.name == s.name }
        else _favoriteStations.value + s)

    fun removeFavoriteStation(name: String) =
        setStations(K_FAV_STATIONS, _favoriteStations, _favoriteStations.value.filterNot { it.name == name })

    fun isFavoriteRoute(o: String, d: String) = _favoriteRoutes.value.any { it.samePair(o, d) }

    /** 오프라인에서 보여 줄 저장 경로 (즐겨찾기에 경로째 저장된 것만) */
    fun savedSnapshot(o: String, d: String): SavedRoute? =
        _favoriteRoutes.value.firstOrNull { it.samePair(o, d) && it.snapshot != null }

    fun toggleFavoriteRoute(r: SavedRoute) = setRoutes(K_FAV_ROUTES, _favoriteRoutes,
        if (isFavoriteRoute(r.origin.name, r.destination.name))
            _favoriteRoutes.value.filterNot { it.samePair(r.origin.name, r.destination.name) }
        else _favoriteRoutes.value + r)

    fun removeFavoriteRoute(r: SavedRoute) = setRoutes(K_FAV_ROUTES, _favoriteRoutes,
        _favoriteRoutes.value.filterNot { it.samePair(r.origin.name, r.destination.name) })

    fun moveFavoriteRoute(from: Int, to: Int) =
        setRoutes(K_FAV_ROUTES, _favoriteRoutes, _favoriteRoutes.value.moved(from, to))

    fun moveFavoriteStation(from: Int, to: Int) =
        setStations(K_FAV_STATIONS, _favoriteStations, _favoriteStations.value.moved(from, to))

    // ---------- 저장 ----------
    private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
        if (from !in indices || to !in indices) return this
        return toMutableList().apply { add(to, removeAt(from)) }
    }

    private fun setStations(key: String, flow: MutableStateFlow<List<SavedStation>>, list: List<SavedStation>) {
        flow.value = list
        prefs?.edit()?.putString(key, JSONArray(list.map(::stationJson)).toString())?.apply()
    }

    private fun setRoutes(key: String, flow: MutableStateFlow<List<SavedRoute>>, list: List<SavedRoute>) {
        flow.value = list
        prefs?.edit()?.putString(key, JSONArray(list.map(::routeJson)).toString())?.apply()
    }

    private fun stationJson(s: SavedStation) = JSONObject()
        .put("name", s.name)
        .put("stations", JSONArray(s.stations.map {
            JSONObject().put("id", it.stationId).put("name", it.name).put("line", it.line).put("active", it.isActive)
        }))

    private fun routeJson(r: SavedRoute) = JSONObject()
        .put("o", stationJson(r.origin)).put("d", stationJson(r.destination))
        .put("type", r.type.name).put("min", r.minutes ?: -1).put("label", r.label ?: "")
        .apply { r.snapshot?.let { put("snap", snapshotJson(it)); put("at", r.savedAt ?: 0L) } }

    private fun stationObj(s: Station) =
        JSONObject().put("id", s.stationId).put("name", s.name).put("line", s.line).put("active", s.isActive)

    private fun parseStationObj(s: JSONObject) =
        Station(s.getString("id"), s.getString("name"), s.getString("line"), s.getBoolean("active"))

    private fun snapshotJson(r: RouteResult) = JSONObject()
        .put("type", r.type.name).put("sec", r.totalSeconds).put("m", r.totalMeters)
        .put("fare", r.totalFareKrw).put("tr", r.transferCount).put("fv", r.fareVersion)
        .put("seg", JSONArray(r.segments.map {
            JSONObject().put("f", stationObj(it.fromStation)).put("t", stationObj(it.toStation))
                .put("line", it.lineName).put("sec", it.seconds).put("m", it.meters).put("x", it.isTransfer)
        }))

    private fun parseSnapshot(o: JSONObject): RouteResult {
        val arr = o.getJSONArray("seg")
        return RouteResult(
            type = runCatching { RouteType.valueOf(o.getString("type")) }.getOrDefault(RouteType.SHORTEST_TIME),
            segments = (0 until arr.length()).map {
                val s = arr.getJSONObject(it)
                RouteSegment(parseStationObj(s.getJSONObject("f")), parseStationObj(s.getJSONObject("t")),
                    s.getString("line"), s.getInt("sec"), s.getInt("m"), s.getBoolean("x"))
            },
            totalSeconds = o.getInt("sec"), totalMeters = o.getInt("m"),
            totalFareKrw = o.getInt("fare"), transferCount = o.getInt("tr"), fareVersion = o.optString("fv")
        )
    }

    private fun parseStation(o: JSONObject): SavedStation {
        val arr = o.getJSONArray("stations")
        return SavedStation(o.getString("name"), (0 until arr.length()).map {
            val s = arr.getJSONObject(it)
            Station(s.getString("id"), s.getString("name"), s.getString("line"), s.getBoolean("active"))
        })
    }

    private fun readStations(key: String): List<SavedStation> = runCatching {
        val arr = JSONArray(prefs?.getString(key, "[]") ?: "[]")
        (0 until arr.length()).map { parseStation(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun readRoutes(key: String): List<SavedRoute> = runCatching {
        val arr = JSONArray(prefs?.getString(key, "[]") ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SavedRoute(
                origin = parseStation(o.getJSONObject("o")),
                destination = parseStation(o.getJSONObject("d")),
                type = runCatching { RouteType.valueOf(o.getString("type")) }.getOrDefault(RouteType.SHORTEST_TIME),
                minutes = o.optInt("min", -1).takeIf { m -> m >= 0 },
                label = o.optString("label").ifBlank { null },
                snapshot = o.optJSONObject("snap")?.let { s -> runCatching { parseSnapshot(s) }.getOrNull() },
                savedAt = o.optLong("at", 0L).takeIf { t -> t > 0 }
            )
        }
    }.getOrDefault(emptyList())

    private const val K_RECENT_STATIONS = "recent_stations"
    private const val K_RECENT_ROUTES = "recent_routes"
    private const val K_FAV_STATIONS = "fav_stations"
    private const val K_FAV_ROUTES = "fav_routes"
}
