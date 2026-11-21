package com.myway.app.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.myway.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 노선도 종류. 이미지는 res/drawable-nodpi/{drawableName}.png
 * (없으면 홈에 안내 문구만 표시 — 빌드는 깨지지 않음)
 */
enum class MapType(
    val line: String?,
    val drawableName: String,
    @StringRes val labelRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int
) {
    ALL(null, "map_all", R.string.map_all_label, R.string.map_all_title, R.string.map_all_desc),
    LINE2("2", "map_line2", R.string.map_line2_label, R.string.map_line2_title, R.string.map_line2_desc),
    LINE5("5", "map_line5", R.string.map_line5_label, R.string.map_line5_title, R.string.map_line5_desc),
    LINE9("9", "map_line9", R.string.map_line9_label, R.string.map_line9_title, R.string.map_line9_desc)
}

class HomeViewModel : ViewModel() {

    private val _map = MutableStateFlow(MapType.ALL)
    val map: StateFlow<MapType> = _map.asStateFlow()

    /** 핀치 안내: 한 번 확대·이동하면 숨김 */
    private val _hintVisible = MutableStateFlow(true)
    val hintVisible: StateFlow<Boolean> = _hintVisible.asStateFlow()

    fun select(type: MapType) { _map.value = type }
    fun onHintSeen() { _hintVisible.value = false }
}
