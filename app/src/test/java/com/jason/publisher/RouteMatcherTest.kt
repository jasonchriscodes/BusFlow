package com.jason.publisher

import com.jason.publisher.main.model.BusScheduleInfo
import com.jason.publisher.main.model.RouteData
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.utils.RouteMatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RouteMatcherTest {

    private fun stop(lat: Double, lon: Double) = BusScheduleInfo("s", "00:00", lat, lon, "a", "a")

    private fun item(runNo: String, start: String, name: String, vararg pts: Pair<Double, Double>) =
        ScheduleItem(runNo, start, start, name, pts.map { stop(it.first, it.second) })

    private fun route(from: Pair<Double, Double>, to: Pair<Double, Double>) = RouteData(
        RouteData.StartingPoint(from.first, from.second, "start"),
        listOf(RouteData.NextPoint(to.first, to.second, "end", "1", emptyList()))
    )

    private val depot = -36.793698 to 175.052331
    private val a = -36.7872 to 175.07255
    private val b = -36.78008 to 174.99199

    // Sign On placeholder and a depot->depot REP share both terminals: position must decide.
    private val roster = listOf(
        item("1", "06:28", "Sign On", depot),
        item("2", "06:29", "REP", depot, depot),
        item("3", "06:50", "50A-217", a, b),
    )
    private val routes = listOf(route(depot, depot), route(depot, depot), route(a, b))

    @Test fun repPicksItsOwnRouteNotSignOnPlaceholder() {
        assertEquals(1, RouteMatcher.findRouteIndex(roster[1], routes, roster))
    }

    @Test fun trimmedRosterAlignsToEnd() {
        val trimmed = roster.drop(1) // Sign On consumed; cache now holds [REP, Trip]
        assertEquals(1, RouteMatcher.findRouteIndex(roster[1], routes, trimmed))
        assertEquals(2, RouteMatcher.findRouteIndex(roster[2], routes, trimmed.drop(1)))
    }

    @Test fun noGeometricMatchReturnsMinusOne() {
        val far = item("9", "09:00", "Trip", -37.0 to 175.5, -37.1 to 175.6)
        assertEquals(-1, RouteMatcher.findRouteIndex(far, routes, roster))
        assertEquals(-1, RouteMatcher.findRouteIndex(roster[2], emptyList(), roster))
    }

    @Test fun unknownItemFallsBackToGeometry() {
        val adHoc = item("99", "13:00", "50A-217", a, b)
        assertEquals(2, RouteMatcher.findRouteIndex(adHoc, routes, roster))
    }

    /**
     * Real Bus A / Bus B payload from ThingsBoard (terminal coordinates only, no tokens).
     * Kept outside the repo; set BUSFLOW_ROUTE_FIXTURE to run it.
     */
    @Test fun realThingsBoardPayloadMatchesEveryTripAndRep() {
        val path = System.getenv("BUSFLOW_ROUTE_FIXTURE")
        assumeTrue("BUSFLOW_ROUTE_FIXTURE not set", path != null && File(path).exists())
        val root = JSONObject(File(path!!).readText())
        var checked = 0
        for (bus in root.keys()) {
            val s = root.getJSONObject(bus).getJSONArray("s")
            val r = root.getJSONObject(bus).getJSONArray("r")
            val fullRoster = (0 until s.length()).map { i ->
                val row = s.getJSONArray(i)
                val c = row.getJSONArray(3)
                val pts = (0 until c.length() step 2).map { k -> c.getDouble(k) to c.getDouble(k + 1) }
                item(row.getString(0), row.getString(1), row.getString(2), *pts.toTypedArray())
            }
            val routeList = (0 until r.length()).map { i ->
                val c: JSONArray = r.getJSONArray(i)
                route(c.getDouble(0) to c.getDouble(1), c.getDouble(2) to c.getDouble(3))
            }
            // Every front-trimmed roster the app can hold (fresh fetch, then after each item).
            for (consumed in fullRoster.indices) {
                val cached = fullRoster.drop(consumed)
                val current = cached.first()
                val name = current.runName.lowercase()
                if (name == "break" || name.contains("sign")) continue
                assertEquals(
                    "Bus $bus item ${consumed + 1} ${current.runName} ${current.startTime}",
                    consumed,
                    RouteMatcher.findRouteIndex(current, routeList, cached)
                )
                checked++
            }
        }
        println("RouteMatcher real payload: $checked Trip/REP launches matched their own route")
    }
}