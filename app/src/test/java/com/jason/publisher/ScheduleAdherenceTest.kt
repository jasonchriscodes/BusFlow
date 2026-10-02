package com.jason.publisher

import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.utils.ScheduleAdherence
import com.jason.publisher.main.utils.ScheduleState
import com.jason.publisher.main.utils.calculateScheduleStatus
import com.jason.publisher.main.utils.classifyScheduleDelta
import com.jason.publisher.main.utils.estimateEffectiveSpeed
import com.jason.publisher.main.utils.formatNextRunCountdown
import com.jason.publisher.main.utils.nextRunAfterActive
import com.jason.publisher.main.utils.nextRunSlackSeconds
import com.jason.publisher.main.utils.remainingAfterActive
import com.jason.publisher.main.utils.resolveTimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleAdherenceTest {
    private val tz = TimeZone.getDefault()

    private fun at(h: Int, m: Int, s: Int = 0): Long = Calendar.getInstance(tz).apply {
        set(2026, Calendar.SEPTEMBER, 30, h, m, s)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun item(runNo: String, start: String, name: String = "Run $runNo") =
        ScheduleItem(runNo, start, start, name, emptyList())

    // --- CLAUDE.md section 18A deterministic cases ---

    @Test fun ahead() {
        val r = calculateScheduleStatus(at(10, 30), at(10, 28))
        assertEquals(120L, r.deltaSeconds)
        assertEquals(ScheduleState.AHEAD, r.state)
    }

    @Test fun onTime() {
        val r = calculateScheduleStatus(at(10, 30), at(10, 30, 10))
        assertEquals(-10L, r.deltaSeconds)
        assertEquals(ScheduleState.ON_TIME, r.state)
        assertEquals("On Time", r.displayText)
    }

    @Test fun behind() {
        val r = calculateScheduleStatus(at(10, 30), at(10, 33))
        assertEquals(-180L, r.deltaSeconds)
        assertEquals(ScheduleState.SLIGHTLY_BEHIND, r.state)
    }

    @Test fun underOneMinuteLabel() {
        assertEquals("Slightly Behind (~<1 min late)", calculateScheduleStatus(at(10, 30), at(10, 30, 45)).displayText)
        assertEquals("Slightly Behind (~1 min late)", calculateScheduleStatus(at(10, 30), at(10, 31, 30)).displayText)
    }

    @Test fun veryBehind() {
        assertEquals(ScheduleState.VERY_BEHIND, calculateScheduleStatus(at(10, 30), at(10, 36)).state)
    }

    // The two contradictions called out in CLAUDE.md section 10 now resolve to one answer.
    @Test fun previouslyContradictoryDeltas() {
        assertEquals(ScheduleState.ON_TIME, classifyScheduleDelta(15))
        assertEquals(ScheduleState.SLIGHTLY_BEHIND, classifyScheduleDelta(-100))
    }

    @Test fun boundaries() {
        assertEquals(ScheduleState.ON_TIME, classifyScheduleDelta(30))
        assertEquals(ScheduleState.AHEAD, classifyScheduleDelta(31))
        assertEquals(ScheduleState.ON_TIME, classifyScheduleDelta(-30))
        assertEquals(ScheduleState.SLIGHTLY_BEHIND, classifyScheduleDelta(-31))
        assertEquals(ScheduleState.SLIGHTLY_BEHIND, classifyScheduleDelta(-300))
        assertEquals(ScheduleState.VERY_BEHIND, classifyScheduleDelta(-301))
    }

    @Test fun hysteresisHoldsStateNearBoundary() {
        // Jitter around +30s keeps the previous state...
        assertEquals(ScheduleState.ON_TIME, classifyScheduleDelta(35, ScheduleState.ON_TIME))
        assertEquals(ScheduleState.AHEAD, classifyScheduleDelta(25, ScheduleState.AHEAD))
        // ...but a clear move still switches.
        assertEquals(ScheduleState.AHEAD, classifyScheduleDelta(45, ScheduleState.ON_TIME))
        assertEquals(ScheduleState.VERY_BEHIND, classifyScheduleDelta(-400, ScheduleState.AHEAD))
    }

    // --- time resolution ---

    @Test fun resolvesAcrossMidnight() {
        val now = at(23, 58)
        assertEquals(now + 7 * 60_000L, resolveTimeOfDay("00:05", now, tz))
        val justAfter = at(0, 2)
        assertEquals(justAfter - 7 * 60_000L, resolveTimeOfDay("23:55:00", justAfter, tz))
    }

    @Test fun malformedTimeIsNull() {
        assertNull(resolveTimeOfDay("", at(10, 0), tz))
        assertNull(resolveTimeOfDay("--:--", at(10, 0), tz))
        assertNull(resolveTimeOfDay("25:00", at(10, 0), tz))
        assertNull(resolveTimeOfDay(null, at(10, 0), tz))
    }

    // --- next run selection ---

    private val active = item("1", "08:00")
    private val next = item("2", "09:00")
    private val later = item("3", "10:00")

    @Test fun nextRunWhenActiveAlreadyRemoved() {
        assertEquals(next, nextRunAfterActive(active, listOf(next, later)))
    }

    @Test fun nextRunWhenActiveStillIncluded() {
        assertEquals(next, nextRunAfterActive(active, listOf(active, next, later)))
    }

    @Test fun lastTripHasNoNextRun() {
        assertNull(nextRunAfterActive(active, emptyList()))
        assertNull(nextRunAfterActive(active, listOf(active)))
        assertEquals(emptyList<ScheduleItem>(), remainingAfterActive(active, listOf(active)))
    }

    @Test fun breakCountsAsNextItem() {
        val brk = item("B", "08:45", "Break")
        assertEquals(brk, nextRunAfterActive(active, listOf(brk, next)))
    }

    // --- countdown never shows impossible values ---

    @Test fun countdownNormal() {
        assertEquals("Next run in: 10 mins 0 seconds", formatNextRunCountdown("09:10", at(9, 0)))
        assertEquals("You are late for the next run by 5 mins", formatNextRunCountdown("09:00", at(9, 5)))
    }

    @Test fun countdownNeverRollsToTomorrow() {
        // Old code produced "Next run in: 1435 mins" here.
        assertEquals("You are late for the next run by 5 mins", formatNextRunCountdown("09:00", at(9, 5)))
        // Stale roster from far away in the day: show the time, not a giant number.
        assertEquals("Next run at 03:00", formatNextRunCountdown("03:00", at(15, 0)))
        assertEquals("No more scheduled trips for today", formatNextRunCountdown(null, at(9, 0)))
    }

    @Test fun nextRunSlackRejectsImplausible() {
        assertEquals(1200L, nextRunSlackSeconds("09:30", 600.0, at(9, 0)))
        assertNull(nextRunSlackSeconds("03:00", 600.0, at(15, 0)))
        assertNull(nextRunSlackSeconds("09:30", Double.POSITIVE_INFINITY, at(9, 0)))
    }

    // --- speed ---

    @Test fun speedSource() {
        assertEquals(ScheduleAdherence.SPEED_SOURCE_GPS, estimateEffectiveSpeed(36.0, 5.0).source)
        assertEquals(10.0, estimateEffectiveSpeed(36.0, 5.0).mps, 1e-9)
        val stopped = estimateEffectiveSpeed(0.0, 5.0)
        assertEquals(ScheduleAdherence.SPEED_SOURCE_SCHEDULE_AVERAGE, stopped.source)
        assertEquals(5.0, stopped.mps, 1e-9)
        assertEquals(ScheduleAdherence.MIN_SPEED_MPS, estimateEffectiveSpeed(0.0, null).mps, 1e-9)
        assertEquals(ScheduleAdherence.SPEED_SOURCE_SCHEDULE_AVERAGE, estimateEffectiveSpeed(500.0, 8.0).source)
    }
}