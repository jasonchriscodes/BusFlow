package com.jason.publisher.modules.rep.viewmodels

import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.graphics.Color
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.TripLog
import com.jason.publisher.main.loggers.TripStateSnapshot
import com.jason.publisher.modules.map.activities.MapActivity
import com.jason.publisher.modules.rep.activities.RepActivity
import com.jason.publisher.modules.map.utils.formatPanelLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale.getDefault
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.min
import kotlin.text.isNullOrBlank

class RepDetailPanelController(
    private val activity: RepActivity,
    private val detailContainer: LinearLayout
) {
    var activeSegment: String? = null

    /** Track if trip has been logged to prevent duplicate TripLog.start() calls */
    private var tripLogStarted = false

    private var lastPanelDump: String? = null
    var panelDebugEnabled = true

    var hasDumpedPanelLog = false

    @RequiresApi(Build.VERSION_CODES.M)
    fun refreshPanelDetailWithLogging(zoom: Double) {
        try {
            refreshDetailPanelIcons(zoom)
        } catch (e: Exception) {
            FileLogger.w("RepDetailPanelController", "refreshDetailPanelIcons failed | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
        }
        try {
            activity.runOnUiThread { logPanelDetailTextOnly() }
        } catch (e: Exception) {
            FileLogger.w("RepDetailPanelController", "logPanelDetailTextOnly (via runOnUiThread) failed | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
        }
    }

    /** Call this any time you re-draw or move markers on the map */
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    fun refreshDetailPanelIcons(zoom: Double) {
        activity.runOnUiThread {
            detailContainer.removeAllViews()

            val selfToken = activity.viewModel.token

            // Other buses that are currently drawn, on screen, exist in arrBusData, AND have valid label
            // This ensures we only show buses that are actually in the system and have started a trip
            val validBusTokens = activity.viewModel.arrBusData.map { it.accessToken }.toSet()

            // Detail panel should show all active buses based on otherBusLabels
            // Only show buses that have a valid label (have started a trip)
            val allActiveOthers = if (isReallyOnline()) {
                activity.otherBusLabels
                    .filter { (t, label) ->
                        t != selfToken && t in validBusTokens && label.isNotBlank()
                    }
                    .keys
                    .toList()
            } else {
                // In offline mode, return empty list - no other buses can be tracked
                emptyList()
            }

            // If zoomed way in or there are no other active buses → show only self
            val displayOrderRaw = if (zoom > 25.0) {
                listOf(selfToken)
            } else {
                val prioritized = activity.mapController.activeBusToken?.takeIf { it in allActiveOthers } ?: allActiveOthers.firstOrNull()
                if (prioritized != null) {
                    listOf(selfToken, prioritized) + allActiveOthers.filter { it != prioritized }
                } else {
                    listOf(selfToken)
                }
            }

            // Remove duplicate tokens from displayOrder to prevent duplicate panels
            val displayOrderDistinct = displayOrderRaw.distinct()
            if (displayOrderDistinct.size != displayOrderRaw.size) {
                Log.w("MapViewController refreshDetailPanelIcons", "⚠️ WARNING: Found duplicate tokens in displayOrder! Raw: $displayOrderRaw, Distinct: $displayOrderDistinct")
            }

            displayOrderDistinct.forEachIndexed { idx, token ->
                // If we're in deep zoom or there are no others, skip any non-self rows
                if (idx >= 1 && (zoom > 25.0 || allActiveOthers.isEmpty())) {
                    return@forEachIndexed
                }

                val iconName = if (idx == 0) "ic_bus_symbol"
                else "ic_bus_symbol${(idx + 1).coerceAtMost(10)}"
                val iconRes  = activity.resources.getIdentifier(iconName, "drawable", activity.packageName)

                // Labels:
                // - self: current trip label if available
                // - others: stored label only; if missing/blank, skip (no "---")
                val label: String = if (token == selfToken) {
                    val selfLbl = activeSegment ?: selfToken
                    // Show self bus even if on Break - it's still an active trip
                    selfLbl
                } else {
                    val lbl = activity.otherBusLabels[token]
                    if (lbl.isNullOrBlank()) {
                        return@forEachIndexed
                    }
                    // Show other buses even if on Break - they're still active and should be tracked
                    lbl
                }

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val pad = activity.dpToPx(8)
                    setPadding(pad, pad/2, pad, pad/2)
                }

                val iv = ImageView(activity).apply {
                    setImageResource(iconRes)
                    layoutParams = LinearLayout.LayoutParams(activity.dpToPx(16), activity.dpToPx(16))
                }
                val tv = TextView(activity).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(activity.dpToPx(8), 0, 0, 0)
                    setTextColor(Color.BLACK)
                    // store the token on the view for logging use (not shown to user)
                    tag = token
                }

                row.addView(iv)
                row.addView(tv)
                detailContainer.addView(row)

                if (idx < displayOrderDistinct.lastIndex) {
                    detailContainer.addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpToPx(1)).apply {
                            topMargin = activity.dpToPx(4); bottomMargin = activity.dpToPx(4)
                        }
                        setBackgroundColor(Color.LTGRAY)
                    })
                }
                try {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            activity.runOnUiThread {
                                logPanelDetailTextOnly()
                            }
                        } catch (e: Exception) {
                            FileLogger.w("RepMapViewController", "Failed to call logPanelDetailTextOnly() | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
                        }
                    }, 120L)
                } catch (e: Exception) {
                    FileLogger.w("RepMapViewController", "postDelayed logPanelDetailTextOnly failed | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
                }
            }

            if (allActiveOthers.isEmpty() && !isReallyOnline()) {
                val hint = TextView(activity).apply {
                    text = "Other bus tracking is not available in offline mode"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.GRAY)
                    setPadding(activity.dpToPx(8), activity.dpToPx(4), activity.dpToPx(8), activity.dpToPx(8))
                    gravity = Gravity.CENTER
                }
                detailContainer.addView(hint)
            }
        }
    }

    /**
     * Returns true if the system’s current network is both capable of, and validated for, Internet.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun isReallyOnline(): Boolean {
        val cm = activity.connectivityManager
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)?.run {
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false
    }

    private fun currentPanelDebugNo(): Int =
        activity.getSharedPreferences("panel_debug_pref", MODE_PRIVATE)
            .getInt("panel_debug_no", 0)

    fun logPanelDetailTextOnly() {
        try {
            val lines = mutableListOf<String>()

            for (i in 0 until detailContainer.childCount) {
                val child = detailContainer.getChildAt(i)
                when (child) {
                    is TextView -> {
                        val label = child.text?.toString()?.takeIf { it.isNotBlank() } ?: continue
                        val token = child.tag as? String
                        val aid = activity.viewModel.getAidOfToken(token)
                        lines.add("$label  [aid=$aid]")
                    }
                    is LinearLayout -> {
                        // look for a TextView child and use its tag
                        for (j in 0 until child.childCount) {
                            val sub = child.getChildAt(j)
                            if (sub is TextView) {
                                val label = sub.text?.toString()?.takeIf { it.isNotBlank() } ?: continue
                                val token = sub.tag as? String
                                val aid = activity.viewModel.getAidOfToken(token)
                                lines.add("$label  [aid=$aid]")
                                break
                            }
                        }
                    }
                    else -> {
                        // fallback: find TextViews and try to read their tag if present
                        fun findTextViews(v: View) {
                            if (v is TextView) {
                                val label = v.text?.toString()?.takeIf { it.isNotBlank() } ?: return
                                val token = v.tag as? String
                                val aid = activity.viewModel.getAidOfToken(token)
                                lines.add("$label  [aid=$aid]")
                            } else if (v is ViewGroup) {
                                for (k in 0 until v.childCount) findTextViews(v.getChildAt(k))
                            }
                        }
                        findTextViews(child)
                    }
                }
            }

            val sb = StringBuilder()
            sb.appendLine("no: ${currentPanelDebugNo()}")
            sb.appendLine("runName: ${activity.viewModel.scheduleList.firstOrNull()?.runName ?: "?"}")
            sb.appendLine("panelDetail:")
            lines.forEach { sb.appendLine(it) }

            val dump = sb.toString().trimEnd()
            if (dump == lastPanelDump) return
            lastPanelDump = dump
            Log.d("PanelDebug", dump)
        } catch (e: Exception) {
            FileLogger.e("PanelDebug", "Error logging detail panel | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
        }
    }

    @SuppressLint("LongLogTag")
    fun logPanelDebugFromDetailPanel() {
        if (!panelDebugEnabled) return

        val sb = StringBuilder()
        sb.appendLine("no: ${currentPanelDebugNo()}")

        val first = activity.viewModel.scheduleList.firstOrNull()
        if (first != null) {
            sb.appendLine("currentDetailPanel: ic_bus_symbol ${formatPanelLabel(first)}")
        }

        val fromStop = first?.busStops?.firstOrNull()?.abbreviation
        val toStop   = first?.busStops?.lastOrNull()?.abbreviation

        // Only call TripLog.start() once per trip (not every time panel debug is logged)
        if (!tripLogStarted) {
            TripLog.start(
                activity,
                TripLog.ActiveTrip(
                    startedAt   = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", getDefault()).format(Date()),
                    type        = "trip",
                    label       = activeSegment, // your "08:10 RUN … A → B"
                    aid         = activity.viewModel.aid,
                    runNo       = first?.runNo,
                    runName     = first?.runName,
                    startTime   = first?.startTime,
                    endTime     = first?.endTime,
                    fromStop    = fromStop,
                    toStop      = toStop,
                    scheduleSize = activity.viewModel.scheduleData.size,
                    routeDataSize = activity.viewModel.busRouteData.size
                ),
                extraDump = mapOf(
                    "configCount" to (activity.viewModel.config?.size ?: 0),
                    "stopsCount" to (activity.viewModel.stops.size),
                    "durationsCount" to (activity.viewModel.durationBetweenStops.size)
                )
            )
            tripLogStarted = true
        }

        // detailContainer[0] is "me"; others are other buses
        if (detailContainer.childCount > 1) {
            for (i in 1 until detailContainer.childCount) {
                val row = detailContainer.getChildAt(i) as? LinearLayout ?: continue
                val text = (0 until row.childCount)
                    .firstNotNullOfOrNull { j -> (row.getChildAt(j) as? TextView)?.text?.toString() }
                if (!text.isNullOrBlank()) {
                    val iconName = "ic_bus_symbol${min(i + 1, 10)}"
                    sb.appendLine("otherDetailPanel: \"$iconName\" $text")
                }
            }
        }

        val dump = sb.toString().trimEnd()
        if (dump == lastPanelDump) return  // ← only log when changed
        lastPanelDump = dump
    }
}