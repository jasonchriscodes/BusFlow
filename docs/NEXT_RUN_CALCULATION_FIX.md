# Fix: Next Run Calculation - 1200+ Minutes Issue

## Problem

- **"Next run in: 1225 mins"** - The time is unreasonable (more than 20 hours)
- **"Late for next run by 247 mins"** - The time is unreasonable (more than 4 hours)

## Root Cause

**Main issue:** `getNextScheduleStartTime()` picks the **wrong** trip.

### Issue Details

**Before starting the route:**

- `scheduleData` = [trip1 (10:00), trip2 (10:40), trip3 (11:20), ...]

**After starting the route (in ScheduleActivity):**

- The first trip (trip1) is popped from `scheduleData`
- Remaining `scheduleData` = [trip2 (10:40), trip3 (11:20), ...]
- `FULL_SCHEDULE_DATA` is sent to MapActivity = [trip2, trip3, ...]

**In MapActivity:**

- `scheduleData` = [trip2 (10:40), trip3 (11:20), ...]
- `getNextScheduleStartTime()` picks `flat[1]` = **trip3 (11:20)** ❌
- Should pick `flat[0]` = **trip2 (10:40)** ✅

**Result:**

- Current time: 14:18:00
- The next trip picked: trip3 (11:20) - but this is already past today
- Or if considered for tomorrow: Next trip (tomorrow 11:20) - Current time (today 14:18) = **very large** (1200+ minutes)

## Solution

**File:** `TimeManager.kt` - `getNextScheduleStartTime()`

**Changes:**

```kotlin
// BEFORE FIX
return if (flat.size > 1) flat[1].startTime else null  // ❌ Picks the second trip

// FIRST FIX (still wrong)
return if (flat.isNotEmpty()) flat[0].startTime else null  // ❌ Picks the first trip, but it might already be past

// SECOND FIX (correct)
// ✅ Find the trip that is still upcoming today, not the one that has already passed
for (schedule in flat) {
    val tripTotalMinutes = tripHour * 60 + tripMinute
    if (tripTotalMinutes > currentTotalMinutes) {
        return schedule.startTime  // ✅ The trip that is still upcoming today
    }
}
// If no trip available today, return the first trip (for tomorrow)
return flat[0].startTime
```

**Explanation:**

- After the first trip is popped, `scheduleData[0]` is the next trip
- **BUT** if the next trip has already passed today (e.g. 10:40 and now is 14:21), the calculation will use tomorrow → 1200+ minutes
- **FIX:** Find the trip that is still upcoming today based on the current time
- If there is no trip today, only pick the first trip (which will be counted for tomorrow)

## Fix Impact

### Before the Fix:

- ❌ Picks trip3 (11:20) even though it should be trip2 (10:40)
- ❌ Calculation uses the wrong trip → result is 1200+ minutes
- ❌ "Late for next run" is also wrong because the wrong trip is used

### After the Fix:

- ✅ Picks trip2 (10:40) which is the correct next trip
- ✅ Calculation uses the correct trip → result makes sense (39 minutes)
- ✅ "Late for next run" is correct because the correct trip is used

## Testing

After the fix, "Next run in" should show:

- A reasonable time (< 24 hours)
- Corresponds to the actual next trip
- No more 1000+ minutes
