package com.jason.publisher.modules.schedule.viewmodels

import android.annotation.SuppressLint
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.jason.publisher.main.loggers.LifecycleLogger
import com.jason.publisher.main.model.BusItem
import com.jason.publisher.main.model.BusRoute
import com.jason.publisher.main.model.BusStop
import com.jason.publisher.main.model.RouteData
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.modules.map.utils.formatPanelLabel
import com.jason.publisher.modules.map.utils.safeRunName
import com.jason.publisher.modules.map.mqtt.helpers.MqttConfigHelper
import com.jason.publisher.modules.schedule.models.BusDataCache
import java.io.File
import java.util.Locale
import kotlin.collections.orEmpty

class ScheduleViewModel: ViewModel() {
    companion object {
        fun provideFactory(): ViewModelProvider.Factory = object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return (ScheduleViewModel() as T)
            }
        }

        /** Maximum number of schedule-item row to display per page */
        const val MAX_ROWS_PER_PAGE = 5
    }

    var aid = ""
    var jsonString = ""
    var token = ""
    var config: List<BusItem>? = emptyList()
    var route: List<BusRoute> = emptyList()
    var stops: List<BusStop> = emptyList()
    var busRouteData: List<RouteData> = emptyList()

    /** Schedule data to be displayed */
    var activeScheduleData: List<ScheduleItem> = emptyList()

    /** Schedule data to keep track of existing schedule even after they are no longer active */
    var scheduleData: List<ScheduleItem> = emptyList()
    var durationBetweenStops: List<Double> = emptyList()
    var arrBusData: List<BusItem> = emptyList()

    var isScheduleUpdatedFromServer = false
    var isTabulatedView: Boolean = false
    var isDarkMode = false
    var currentPage = 0
    val paginatedActiveScheduleData: List<ScheduleItem>
        get() {
            val start = currentPage * MAX_ROWS_PER_PAGE
            val end   = minOf(activeScheduleData.size, start + MAX_ROWS_PER_PAGE)
            return if (activeScheduleData.isEmpty())
                emptyList()
            else
                activeScheduleData.subList(start, end)
        }
    val totalPages: Int
        get() = (activeScheduleData.size + MAX_ROWS_PER_PAGE - 1) / MAX_ROWS_PER_PAGE
    var dotCount = 1

    var fetchRoster = false
    private var lastPreStartDump: String? = null
    /**
     * Flag to ensure cache is updated only once.
     */
    var isScheduleCacheUpdated = false

    /**
     * Flag to ensure cache is updated only once.
     */
    var isBusCacheUpdated = false

    private fun firstScheduleOrNull(): ScheduleItem? = activeScheduleData.firstOrNull()

    fun buildExtraDump(): Map<String, Any?> = mapOf(
        "aid" to aid,
        "configCount" to (config?.size ?: 0),
        "busRouteDataCount" to (busRouteData.size),
        "scheduleCount" to (activeScheduleData.size),
        "firstSchedule" to firstScheduleOrNull()?.copy(busStops = firstScheduleOrNull()?.busStops?.take(3) ?: emptyList()),
        // 👇 replace the offending line with this:
        "firstRouteSummary" to busRouteData.firstOrNull()?.let { rd ->
            val start = rd.startingPoint.address
            val end = rd.nextPoints.lastOrNull()?.address ?: "?"
            "$start → $end"
        },
        "stopsCount" to stops.size,
        "durationsCount" to durationBetweenStops.size
    )

    /** Map "1" or "Route 1" → 0-based index into busRouteData */
    fun routeIndexFromRouteNo(runNo: String): Int? {
        // Accepts "1" or "Route 1" etc.
        val cleaned = runNo.trim().lowercase(Locale.ROOT)
        val digits  = cleaned.removePrefix("route").trim().toIntOrNull() ?: return null
        val runOffset = scheduleData.first().runNo.toIntOrNull() ?: return null
        // Offset is the number of breaks in scheduleData plus 1 (because index starts from 0)
        val offset = scheduleData.subList(0, digits - runOffset).count { it.runName.lowercase() == "break" } + 1
        val idx     = digits.minus(offset)
        return if (idx in busRouteData.indices) idx else null
    }

    fun logActivityEntry() {
        LifecycleLogger.logActivityEntry("ScheduleActivity", mapOf(
            "scheduleCount" to activeScheduleData.size,
            "routeCount" to busRouteData.size,
            "aid" to aid,
            "firstTripInfo" to (activeScheduleData.firstOrNull()?.let { "${it.startTime} ${it.runName}" } ?: "N/A")
        ))
    }

    /** Set [token] from [aid] and [config] */
    fun loadAccessToken() {
        token = MqttConfigHelper.getAccessToken(aid, config.orEmpty())
    }

    fun loadBusDataFromCache() {
        val cacheFile = File(getHiddenFolder(), "busDataCache.txt")

        if (cacheFile.exists()) {
            try {
                val jsonContent = cacheFile.readText()
                val cachedData = Gson().fromJson(jsonContent, BusDataCache::class.java)

                // Ensure all values are updated correctly
                aid = cachedData.aid
                config = cachedData.config ?: emptyList()
                busRouteData = cachedData.busRouteData ?: emptyList()

                Log.d("ScheduleViewModel", "Loaded cached AID: $aid")
                Log.d("ScheduleViewModel", "Loaded cached Config: $config")
                Log.d("ScheduleViewModel", "Loaded cached BusRouteData: $busRouteData")

                // Extract `route`, `stops`, and `durationBetweenStops` from `busRouteData`
                processBusRouteData()
            } catch (e: Exception) {
                Log.e("ScheduleViewModel", "Error reading bus data cache: ${e.message}")
            }
        } else {
            Log.e("ScheduleViewModel", "No cached bus data found.")
        }
    }

    fun loadScheduleDataFromCache(cacheFile: File) {
        val jsonContent = cacheFile.readText()
        val cachedSchedule =
            Gson().fromJson(jsonContent, Array<ScheduleItem>::class.java).toList()
        scheduleData = cachedSchedule.map { it.copy(runName = safeRunName(it)) }
        activeScheduleData = scheduleData.toList() // Shallow copy to sever connection

        Log.d("ScheduleViewModel", "✅ Loaded cached schedule data: $activeScheduleData")
    }

    /**
     * Gets the hidden folder directory where cached bus data is stored.
     * Creates the folder if it does not exist.
     *
     * @return File representing the hidden directory.
     */
    fun getHiddenFolder(): File {
        Log.d("storage",
            "api=${Build.VERSION.SDK_INT}, " +
                    "allFiles=${if (Build.VERSION.SDK_INT>=30) Environment.isExternalStorageManager() else "n/a"}"
        )

        val externalDocumentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val hiddenFolder = File(externalDocumentsDir, ".vlrshiddenfolder")

        if (!hiddenFolder.exists()) {
            hiddenFolder.mkdirs()
        }

        return hiddenFolder
    }

    /**
     * Saves the latest bus data to the cache file.
     */
    @SuppressLint("LongLogTag")
    fun saveBusDataToCache() {
        val cacheFile = File(getHiddenFolder(), "busDataCache.txt")
        val busData = mapOf(
            "aid"          to aid,
            "busRouteData" to busRouteData,
            "config"       to config
        )
        cacheFile.writeText(Gson().toJson(busData))

        // Mark flag so we never repeat
        isBusCacheUpdated = true
    }

    /**
     * Saves the latest schedule data to the cache file.
     */
    @SuppressLint("LongLogTag")
    fun saveScheduleDataToCache() {
        val cacheFile = File(getHiddenFolder(), "scheduleDataCache.txt")
        if (!cacheFile.exists()) {
            cacheFile.createNewFile()
        }
        cacheFile.writeText(Gson().toJson(activeScheduleData))

        // Mark flag so we never repeat
        isScheduleCacheUpdated = true
    }

    fun logPanelDebugPreStart(no: Int, first: ScheduleItem) {
        val current = "ic_bus_symbol ${formatPanelLabel(first)}"
        val dump = buildString {
            appendLine("no: $no")
            appendLine("runName: ${first.runName}")
            append("currentDetailPanel: $current")
        }
        if (dump == lastPreStartDump) return
        lastPreStartDump = dump
        Log.d("PanelDebug", dump)
    }

    /**
     * Processes [busRouteData] and set the value of [route], [stops], and [durationBetweenStops]
     * based on it.
     */
    fun processBusRouteData() {
        val newRoute = mutableListOf<BusRoute>()
        val newStops = mutableListOf<BusStop>()
        val newDurations = mutableListOf<Double>()

        for (routeData in busRouteData) {
            // Add starting point as the first stop
            newStops.add(
                BusStop(
                    latitude = routeData.startingPoint.latitude,
                    longitude = routeData.startingPoint.longitude,
                    address = routeData.startingPoint.address
                )
            )

            for (nextPoint in routeData.nextPoints) {
                // Add stops
                newStops.add(
                    BusStop(
                        latitude = nextPoint.latitude,
                        longitude = nextPoint.longitude,
                        address = nextPoint.address
                    )
                )

                // Add route coordinates
                var last: Pair<Double,Double>? = null
                for (coordinate in nextPoint.routeCoordinates) {
                    val lat = coordinate[1]
                    val lon = coordinate[0]
                    if (last == lat to lon) continue      // skip duplicate
                    newRoute.add(BusRoute(latitude = lat, longitude = lon))
                    last = lat to lon
                }

                // Extract duration
                val durationValue = nextPoint.duration.split(" ")[0].toDoubleOrNull() ?: 0.0
                newDurations.add(durationValue)
            }
        }

        route = newRoute
        stops = newStops
        durationBetweenStops = newDurations

        Log.d("ScheduleViewModel", "Extracted Route: $route")
        Log.d("ScheduleViewModel", "Extracted Stops: $stops")
        Log.d("ScheduleViewModel", "Extracted Duration Between Stops: $durationBetweenStops")
    }

    /**
     * Extracts work intervals and duty names from the schedule data dynamically.
     */
    fun extractWorkIntervalsAndRunNames(): Pair<List<Pair<String, String>>, List<String>> {
        val workIntervals = mutableListOf<Pair<String, String>>()
        val runNames = mutableListOf<String>()

        for (item in activeScheduleData) { // Limit to first 3 entries directly
            val startTime = item.startTime
            val endTime = item.endTime
            val runName = item.runName

            if (startTime.isNotEmpty() && endTime.isNotEmpty()) {
                workIntervals.add(Pair(startTime, endTime))
                runNames.add(runName)
            }
        }

        Log.d("ScheduleViewModel", "✅ Extracted Work Intervals: $workIntervals")
        Log.d("ScheduleViewModel", "✅ Extracted Duty Names: $runNames")
        return Pair(workIntervals, runNames)
    }
}