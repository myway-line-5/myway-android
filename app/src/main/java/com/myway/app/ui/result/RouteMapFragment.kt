package com.myway.app.ui.result

import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.myway.app.R
import com.myway.app.databinding.FragmentRouteMapBinding
import com.myway.app.domain.model.RouteResult
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.home.MapImage
import com.myway.app.ui.home.MapStations
import com.myway.app.ui.home.MapTracks
import com.myway.app.ui.home.ZoomableMapView
import com.myway.app.ui.home.ZoomableMapView.PinKind
import kotlinx.coroutines.launch
import com.myway.app.ui.common.displayName

/**
 * 화면 59 선택 경로 지도: 전체 노선도 위에 지나는 역을 노선색으로 잇고 출발·도착 핀.
 * 처음엔 경로 전체가 보이게 맞추고, 손가락으로 확대·이동 가능.
 */
class RouteMapFragment : Fragment(R.layout.fragment_route_map) {

    private var _b: FragmentRouteMapBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentRouteMapBinding.bind(view).also { _b = it }
        val args = requireArguments()
        val names = args.getStringArrayList(K_NAMES).orEmpty()
        val lines = args.getStringArrayList(K_LINES).orEmpty()
        val walk = args.getBooleanArray(K_WALK) ?: BooleanArray(names.size)

        b.backButton.setOnClickListener { findNavController().popBackStack() }
        b.title.text = getString(R.string.route_map_title, args.getString(K_O), args.getString(K_D))
        b.summary.text = args.getString(K_SUM)
        b.mapView.placeholderText = ""

        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = MapImage.load(requireContext(), R.drawable.map_all)
            val v = _b ?: return@launch
            v.mapView.drawable = bmp?.let { BitmapDrawable(resources, it) }
            v.mapView.placeholderText = getString(R.string.home_map_missing)
            if (bmp != null) draw(v, names, lines, walk)
        }
    }

    private fun draw(b: FragmentRouteMapBinding, names: List<String>, lines: List<String>, walk: BooleanArray) {
        val ctx = requireContext()
        val last = names.lastIndex
        val stops = names.indices.mapNotNull { i ->
            val s = find(names[i], lines.getOrNull(i).orEmpty()) ?: return@mapNotNull null
            val nextWalk = walk.getOrElse(i + 1) { false }
            // 같은 노선 인접 역이면 노선도의 실제 선로 모양을 따라 그림
            val via = if (i > 0 && !walk.getOrElse(i) { false })
                MapTracks.between(LineStyle.key(lines.getOrNull(i).orEmpty()), names[i - 1], names[i]) else null
            ZoomableMapView.RouteStop(
                s.x.toFloat(), s.y.toFloat(),
                color = ctx.getColor(LineStyle.colorRes(lines.getOrNull(i).orEmpty())),
                major = i == 0 || i == last || walk.getOrElse(i) { false } || nextWalk,
                connector = walk.getOrElse(i) { false },
                via = via
            )
        }
        if (stops.size < 2) { b.missing.isVisible = true; return }

        b.mapView.route = stops
        b.mapView.pins = listOf(
            ZoomableMapView.Pin(stops.first().x, stops.first().y, PinKind.ORIGIN, names.first().displayName()),
            ZoomableMapView.Pin(stops.last().x, stops.last().y, PinKind.DESTINATION, names.last().displayName())
        )
        val box = RectF(stops.first().x, stops.first().y, stops.first().x, stops.first().y)
        stops.forEach { box.union(it.x, it.y) }
        b.mapView.showRegion(box)
    }

    /** 같은 이름·노선 → 없으면 같은 이름 (환승역은 노선마다 점이 다를 수 있음) */
    private fun find(name: String, line: String): MapStations.S? {
        val key = LineStyle.key(line)
        return MapStations.all.firstOrNull { it.name == name && it.line == key }
            ?: MapStations.all.firstOrNull { it.name == name }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        private const val K_NAMES = "names"
        private const val K_LINES = "lines"
        private const val K_WALK = "walk"
        private const val K_O = "origin"
        private const val K_D = "destination"
        private const val K_SUM = "summary"

        /** 지나는 역 순서대로 이름·노선, 환승으로 도착한 역 표시 */
        fun args(route: RouteResult, origin: String, destination: String, summary: String): Bundle {
            val names = arrayListOf<String>()
            val lines = arrayListOf<String>()
            val walk = mutableListOf<Boolean>()
            route.segments.firstOrNull()?.let {
                names += it.fromStation.name; lines += it.fromStation.line.ifBlank { it.lineName }; walk += false
            }
            route.segments.forEach {
                names += it.toStation.name; lines += it.toStation.line.ifBlank { it.lineName }; walk += it.isTransfer
            }
            return bundleOf(K_NAMES to names, K_LINES to lines, K_WALK to walk.toBooleanArray(),
                K_O to origin, K_D to destination, K_SUM to summary)
        }
    }
}
