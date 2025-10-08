package com.jason.publisher.main.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jason.publisher.R
import com.jason.publisher.main.model.BusScheduleInfo
import com.jason.publisher.main.model.ScheduleItem
import kotlin.math.max

class BreakUpcomingAdapter(
    private val items: List<ScheduleItem>
) : RecyclerView.Adapter<BreakUpcomingAdapter.VH>() {

    class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_up_next_trip, parent, false)
    ) {
        val txtStartEnd: TextView = itemView.findViewById(R.id.txtStartEnd)
        val txtDuration: TextView = itemView.findViewById(R.id.txtDuration)
        val txtDuty: TextView     = itemView.findViewById(R.id.txtDuty)
        val txtType: TextView     = itemView.findViewById(R.id.txtType)
        val txtFromToAbbr: TextView = itemView.findViewById(R.id.txtFromToAbbr)
        val txtFromToFull: TextView = itemView.findViewById(R.id.txtFromToFull)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]

        // Time column
        h.txtStartEnd.text = "${it.startTime} – ${it.endTime}"
        h.txtDuration.text = computeDurationText(it.startTime, it.endTime)

        // Duty column
        h.txtDuty.text = it.dutyName
        h.txtType.text = if (it.dutyName.equals("break", true)) "Break" else "Trip"

        // From/To
        val from = it.busStops.firstOrNull()
        val to   = it.busStops.lastOrNull()

        val fromAbbr = from?.abbreviation?.takeIf { s -> s.isNotBlank() } ?: from?.name ?: "?"
        val toAbbr   = to?.abbreviation?.takeIf { s -> s.isNotBlank() } ?: to?.name ?: "?"
        h.txtFromToAbbr.text = "$fromAbbr → $toAbbr"

        // Full street names. Try name/address fields, fall back gracefully.
        val fromFull = pickFullName(from)
        val toFull   = pickFullName(to)
        h.txtFromToFull.text = "$fromFull → $toFull"
    }

    private fun pickFullName(si: BusScheduleInfo?): String {
        if (si == null) return "?"
        return when {
            !si.name.isNullOrBlank() -> si.name    // full street in your data
            !si.abbreviation.isNullOrBlank() -> si.abbreviation
            else -> "?"
        }
    }
    private fun computeDurationText(start: String?, end: String?): String {
        val s = parseMin(start)
        val e = parseMin(end)
        if (s == null || e == null) return ""
        val d = max(0, e - s)
        val h = d / 60
        val m = d % 60
        return if (h > 0) "${h}h ${m}m" else "${m} min"
    }

    private fun parseMin(hhmm: String?): Int? {
        val p = hhmm?.split(":") ?: return null
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }
}

