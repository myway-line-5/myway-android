package com.myway.app.ui.result

import android.content.Context
import com.myway.app.R
import com.myway.app.domain.model.RouteResult
import com.myway.app.domain.model.Station
import com.myway.app.ui.common.LineStyle
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 타임라인 한 줄: 역(Stop) 또는 구간(Leg) */
sealed interface TimelineRow {
    data class Stop(
        val time: String,
        val line: String,          // 배지·레일 색
        val badge: String,         // "5" / "환승" / "하차"
        val name: String,
        val sub: String?
    ) : TimelineRow

    data class Leg(
        val line: String?,         // null 이면 도보 환승(회색 레일)
        val label: String          // "4개 역 (8분)" / "환승 (도보 3분)"
    ) : TimelineRow
}

object Timeline {

    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    private sealed interface Part {
        data class Ride(val line: String, val from: Station, var to: Station, var count: Int, var seconds: Int) : Part
        data class Walk(val to: Station, val seconds: Int) : Part
    }

    /** 구간 목록 → [승차역, n개 역, 환승역, 도보, 승차역, …, 하차역] */
    fun build(ctx: Context, route: RouteResult, departAt: LocalTime): List<TimelineRow> {
        val parts = mutableListOf<Part>()
        route.segments.forEach { seg ->
            if (seg.isTransfer) {
                parts += Part.Walk(seg.toStation, seg.seconds)
            } else {
                val line = LineStyle.key(seg.lineName)
                val last = parts.lastOrNull()
                if (last is Part.Ride && last.line == line) {
                    last.to = seg.toStation; last.count += 1; last.seconds += seg.seconds
                } else {
                    parts += Part.Ride(line, seg.fromStation, seg.toStation, 1, seg.seconds)
                }
            }
        }

        val rows = mutableListOf<TimelineRow>()
        var t = departAt
        parts.forEachIndexed { i, part ->
            when (part) {
                is Part.Ride -> {
                    if (i == 0 || parts[i - 1] !is Part.Walk) {
                        rows += TimelineRow.Stop(t.format(clock), part.line, part.line, part.from.name,
                            ctx.getString(R.string.result_board, part.line))
                    }
                    rows += TimelineRow.Leg(part.line,
                        ctx.getString(R.string.result_ride, part.count, minutes(part.seconds)))
                    t = t.plusSeconds(part.seconds.toLong())

                    val next = parts.getOrNull(i + 1)
                    val nextRide = parts.drop(i + 1).firstOrNull { it is Part.Ride } as? Part.Ride
                    rows += if (next == null) {
                        TimelineRow.Stop(t.format(clock), part.line, ctx.getString(R.string.result_badge_alight),
                            part.to.name, null)
                    } else {
                        TimelineRow.Stop(t.format(clock), part.line, ctx.getString(R.string.result_badge_transfer),
                            part.to.name, nextRide?.let { ctx.getString(R.string.result_transfer_to, it.line) })
                    }
                }
                is Part.Walk -> {
                    rows += TimelineRow.Leg(null, ctx.getString(R.string.result_walk, minutes(part.seconds)))
                    t = t.plusSeconds(part.seconds.toLong())
                    val nextRide = parts.getOrNull(i + 1) as? Part.Ride
                    if (nextRide != null) {
                        rows += TimelineRow.Stop(t.format(clock), nextRide.line, nextRide.line, nextRide.from.name,
                            ctx.getString(R.string.result_board, nextRide.line))
                    }
                }
            }
        }
        return rows
    }

    fun minutes(seconds: Int) = (seconds + 59) / 60

    /** 47분 / 1시간 7분 */
    fun duration(ctx: Context, seconds: Int): String {
        val m = minutes(seconds)
        return if (m < 60) ctx.getString(R.string.result_min, m)
        else ctx.getString(R.string.result_hour_min, m / 60, m % 60)
    }

    fun fare(ctx: Context, krw: Int): String =
        ctx.getString(R.string.result_fare, NumberFormat.getNumberInstance(Locale.KOREA).format(krw))

    fun clock(time: LocalTime): String = time.format(clock)
}
