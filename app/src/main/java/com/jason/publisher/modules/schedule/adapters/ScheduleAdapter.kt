package com.jason.publisher.modules.schedule.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jason.publisher.R
import com.jason.publisher.main.model.ScheduleItem

class ScheduleAdapter(
    private var items: List<ScheduleItem>,
    private var isDarkMode: Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    private var abbrToFullName: Map<String, String> = buildAbbrToFullName(items)

    fun setThemeMode(isDark: Boolean) {
        isDarkMode = isDark
        notifyDataSetChanged()
    }

    /** public way to swap in a fresh page */
    fun update(newItems: List<ScheduleItem>) {
        items = newItems
        abbrToFullName = buildAbbrToFullName(items)
        notifyDataSetChanged()
    }

    /**
     * Break/Sign On/Sign Off duties often carry a generic placeholder stop name
     * (e.g. "Stop E") on their own busStops entry even though the abbreviation
     * (e.g. "WB") is correct. Trip/Reposition duties carry the real full stop
     * name for the same abbreviation elsewhere in this same schedule, so resolve
     * placeholders against those instead.
     */
    private fun buildAbbrToFullName(source: List<ScheduleItem>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        source.forEach { item ->
            val label = item.activityLabel()
            if (label == "Trip" || label == "Reposition") {
                item.busStops.forEach { stop ->
                    if (stop.abbreviation.isNotBlank() && stop.name.isNotBlank()) {
                        map.putIfAbsent(stop.abbreviation, stop.name)
                    }
                }
            }
        }
        return map
    }

    override fun getItemCount() = items.size + 1  // +1 for header

    override fun getItemViewType(position: Int) =
        if (position == 0) TYPE_HEADER else TYPE_ITEM

    // 2 holders: header vs. normal row
    class HeaderVH(view: View) : RecyclerView.ViewHolder(view)
    class ItemVH(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return if (viewType == TYPE_HEADER) {
            val headerLayout = if (isDarkMode)
                R.layout.dark_item_schedule_header
            else
                R.layout.item_schedule_header

            val view = inflater.inflate(headerLayout, parent, false)
            HeaderVH(view)
        } else {
            val itemLayout = if (isDarkMode)
                R.layout.dark_item_schedule_row
            else
                R.layout.item_schedule_row

            val view = inflater.inflate(itemLayout, parent, false)
            ItemVH(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        if (getItemViewType(pos) == TYPE_ITEM) {
            val real = items[pos - 1]
            val itemHolder = holder as ItemVH

            val runNo = itemHolder.view.findViewById<TextView>(R.id.routeNo)
            val activityText = itemHolder.view.findViewById<TextView>(R.id.activityText)
            val startTime = itemHolder.view.findViewById<TextView>(R.id.startTime)
            val endTime = itemHolder.view.findViewById<TextView>(R.id.endTime)
            val routeName = itemHolder.view.findViewById<TextView>(R.id.routeName)

            val activity = real.activityLabel()

            runNo.text = real.runNo
            activityText.text = activity
            startTime.text = real.startTime
            endTime.text = real.endTime
            routeName.text = real.routeDescription(activity)
        }
    }

    /** "Break", "Sign On", "Sign Off", "Reposition", or "Trip" based on the run name. */
    private fun ScheduleItem.activityLabel(): String = when {
        isBreak() -> "Break"
        runName.contains("sign", ignoreCase = true) ->
            if (runName.contains("off", ignoreCase = true) || runName.contains("out", ignoreCase = true))
                "Sign Off"
            else
                "Sign On"
        isReposition() -> "Reposition"
        else -> "Trip"
    }

    /**
     * Break/Sign On/Sign Off happen at a single stop (e.g. the depot), so the route
     * reads "break at WB (Waiheke Bus Company)". Reposition/Trip move between two
     * stops, so it reads "50B-217 FA(Fourth Avenue) → MT(Matiatia Terminal)".
     */
    private fun ScheduleItem.routeDescription(activity: String): String {
        return when (activity) {
            "Break", "Sign On", "Sign Off" -> {
                val stop = busStops.firstOrNull()
                val abbr = stop?.abbreviation ?: "?"
                val name = abbrToFullName[abbr] ?: stop?.name ?: "?"
                "${activity.lowercase()} at $abbr ($name)"
            }
            else -> {
                val from = busStops.firstOrNull()
                val to = busStops.lastOrNull()
                val fromAbbr = from?.abbreviation ?: "?"
                val fromName = from?.name?.let { abbrToFullName[fromAbbr] ?: it } ?: "?"
                val toAbbr = to?.abbreviation ?: "?"
                val toName = to?.name?.let { abbrToFullName[toAbbr] ?: it } ?: "?"
                "$runName $fromAbbr($fromName) → $toAbbr($toName)"
            }
        }
    }
}