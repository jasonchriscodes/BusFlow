# Bug Analysis: Next Run & Late Calculation

## Problem Discovered

From the screenshot, we see:

- **"Next run in: 1250 mins 21 seconds"** — Highly abnormal (more than 20 hours!)
- **"Late for next run by 220 mins"** — Highly abnormal (more than 3 hours!)

## Root Cause Analysis

### 🔴 Main Problem: Mismatch between Current Time Display and Calculation Time

**Cause:**
After fixing the timer using `startCurrentTimeUpdater()`, there is a **mismatch** between:

1. **Time displayed in the UI** → Using `Date()` (actual tablet time)
2. **Time used for calculation** → Still using `simulatedStartTime` (outdated/not updated time)

### Problem Details

#### 1. "Next run in: 1250 mins" – TimeManager.kt

**Location:** `TimeManager.kt` line 127 and `MapActivity.kt` line 2013

**Problematic Code:**

```kotlin
// TimeManager.kt startNextTripCountdownUpdater()
val currentTime = simulatedStartTime.clone() as Calendar  // ❌ PROBLEM HERE
val nextTripStartTime = getNextScheduleStartTime()

if (nextTripStartTime != null) {
    val timeParts = nextTripStartTime.split(":").map { it.toInt() }
    val nextTripCalendar = Calendar.getInstance().apply {
        set(Calendar.YEAR, currentTime.get(Calendar.YEAR))
        set(Calendar.MONTH, currentTime.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, currentTime.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, timeParts[0])
        set(Calendar.MINUTE, timeParts[1])
        set(Calendar.SECOND, 0)
        if (timeInMillis <= currentTime.timeInMillis) add(Calendar.DATE, 1)
    }
    val diff = nextTripCalendar.timeInMillis - currentTime.timeInMillis
    // ... calculate mins and secs
}
```

**Problem:**

- `simulatedStartTime` is **NOT updated** when using `startCurrentTimeUpdater()`
- `startCurrentTimeUpdater()` only updates `currentTimeTextView.text` with `Date()`, but **does NOT update `simulatedStartTime`**
- `simulatedStartTime` still holds an old value (maybe the schedule start time or time when first set)
- When calculating "Next run in", it uses `simulatedStartTime` which is **far behind** the actual time

**Example:**

- Actual tablet time: **13:51:14** (from the screenshot)
- `simulatedStartTime` might still be: **10:00:00** (schedule start time)
- Next trip: **10:00:00** (tomorrow)
- Calculation: Next trip (tomorrow 10:00) - `simulatedStartTime` (today 10:00) = **24 hours = 1440 minutes**
- But if `simulatedStartTime` is even earlier, it could be **1250 minutes**

#### 2. "Late for next run by 220 mins" – ScheduleStatusManager.kt

**Location:** `ScheduleStatusManager.kt` line 380

**Problematic Code:**

```kotlin
// ScheduleStatusManager.kt overrideLateStatusForNextSchedule()
val predictedArrival = Calendar.getInstance().apply {
    time = activity.timeManager.simulatedStartTime.time  // ❌ PROBLEM HERE
    add(Calendar.SECOND, t1.toInt())
}

val nextScheduleStartTime = activity.timeManager.parseTimeToday(nextScheduleStartStr)
val deltaNextSec = ((nextScheduleStartTime.time - predictedArrival.time.time) / 1000).toInt()
```

**Problem:**

- `predictedArrival` uses `simulatedStartTime.time` as its base
- But `simulatedStartTime` is not updated, so its base time is incorrect
- Predicted arrival becomes inaccurate
- Calculation of "late" becomes incorrect

**Example:**

- Actual time: **13:51:14**
- `simulatedStartTime`: **10:00:00** (lagging ~4 hours)
- Predicted arrival is calculated from **10:00:00** + t1, not from **13:51:14** + t1
- The result: predicted arrival is too early
- Next trip start time – predicted arrival = **220 minutes** (because base time is wrong)

### 🔍 Code Analysis

#### startCurrentTimeUpdater() – TimeManager.kt

```kotlin
fun startCurrentTimeUpdater() {
    currentTimeHandler = Handler(Looper.getMainLooper())
    currentTimeRunnable = object : Runnable {
        override fun run() {
            val currentTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val nowStr = currentTimeFormat.format(Date())  // ✅ Uses Date()
            owner.currentTimeTextView.text = nowStr
            // ❌ Does NOT update simulatedStartTime!
            currentTimeHandler?.postDelayed(this, 1000)
        }
    }
    currentTimeHandler?.post(currentTimeRunnable!!)
}
```

**Problem:**

- Only updates UI text with `Date()`
- **Does NOT update `simulatedStartTime`**
- `simulatedStartTime` keeps holding the old value

#### startNextTripCountdownUpdater() – TimeManager.kt

```kotlin
fun startNextTripCountdownUpdater() {
    nextTripRunnable = object : Runnable {
        override fun run() {
            val currentTime = simulatedStartTime.clone() as Calendar  // ❌ Uses simulatedStartTime
            val nextTripStartTime = getNextScheduleStartTime()
            // ... calculate diff
        }
    }
}
```

**Problem:**

- Uses `simulatedStartTime` that isn't updated
- Should use actual tablet time (`Date()` or `Calendar.getInstance()`)

## Conclusion

### Is this a data problem?

**NO.** The schedule data is probably correct. The problem is in the **calculation logic**.

### Is this a bug in get/set?

**YES.** There's a bug in:

1. **Get:** Using `simulatedStartTime` that isn't updated
2. **Set:** `startCurrentTimeUpdater()` doesn't update `simulatedStartTime`

### Root Cause Summary

1. **Timer Display Fix** uses `startCurrentTimeUpdater()` that only updates the UI, not `simulatedStartTime`
2. **Calculations still use `simulatedStartTime`** which is not updated
3. **Mismatch** between displayed time (real time) and calculation time (stale time)
4. Result: "Next run in" and "Late for next run" calculations become **extremely inaccurate**

## Solution Required

### Option 1: Update simulatedStartTime inside startCurrentTimeUpdater()

```kotlin
fun startCurrentTimeUpdater() {
    currentTimeRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            val currentTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val nowStr = currentTimeFormat.format(now)
            owner.currentTimeTextView.text = nowStr

            // ✅ ALSO UPDATE simulatedStartTime
            simulatedStartTime.time = now
            simulatedStartTime.add(Calendar.SECOND, 1)

            currentTimeHandler?.postDelayed(this, 1000)
        }
    }
}
```

### Option 2: Use Date() directly for calculations

```kotlin
// In startNextTripCountdownUpdater()
val currentTime = Calendar.getInstance()  // ✅ Use real time, not simulatedStartTime
```

### Option 3: Sync simulatedStartTime with current time when startCurrentTimeUpdater() is called

```kotlin
fun startCurrentTimeUpdater() {
    // ✅ Sync simulatedStartTime with the real current time
    simulatedStartTime.time = Date()

    currentTimeRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            simulatedStartTime.time = now
            owner.currentTimeTextView.text = timeFormat.format(now)
            currentTimeHandler?.postDelayed(this, 1000)
        }
    }
}
```

## Recommendation

**Recommendation:** Use **Option 1** or **Option 3** because:

- `simulatedStartTime` is still used in many places for calculation
- It's safer to update `simulatedStartTime` so it remains synced with real time
- No need to change every place that uses `simulatedStartTime`

## Testing Checklist After Fix

- [ ] "Next run in" displays a reasonable time (< 24 hours)
- [ ] "Late for next run" displays a reasonable time
- [ ] Calculations use the same time as shown in the UI
- [ ] No mismatch between display time and calculation time
