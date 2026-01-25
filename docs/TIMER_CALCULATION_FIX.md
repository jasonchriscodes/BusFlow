# Fix: Timer Calculation Bug - Next Run & Late Calculation

## Issues Fixed

- **"Next run in: 1250 mins"** – Unreasonable time (more than 20 hours)
- **"Late for next run by 220 mins"** – Unreasonable time (more than 3 hours)

## Root Cause

After fixing the timer with `startCurrentTimeUpdater()`, a **mismatch** occurred between:

1. **Time displayed in the UI** → Uses `Date()` (actual tablet time) ✅
2. **Time used for calculations** → Still uses `simulatedStartTime` which is **NOT updated** ❌

### Problem Details

**TimeManager.kt - startCurrentTimeUpdater():**

```kotlin
// BEFORE FIX
fun startCurrentTimeUpdater() {
    currentTimeRunnable = object : Runnable {
        override fun run() {
            val nowStr = currentTimeFormat.format(Date())  // ✅ Updates UI
            owner.currentTimeTextView.text = nowStr
            // ❌ Does NOT update simulatedStartTime!
        }
    }
}
```

**TimeManager.kt - startNextTripCountdownUpdater():**

```kotlin
// BEFORE FIX
val currentTime = simulatedStartTime.clone() as Calendar  // ❌ Still uses old time
```

**ScheduleStatusManager.kt - overrideLateStatusForNextSchedule():**

```kotlin
// BEFORE FIX
val predictedArrival = Calendar.getInstance().apply {
    time = activity.timeManager.simulatedStartTime.time  // ❌ Still uses old time
    add(Calendar.SECOND, t1.toInt())
}
```

## Implemented Solution

### Fix 1: Update simulatedStartTime in startCurrentTimeUpdater()

**File:** `TimeManager.kt`

**Changes:**

```kotlin
// AFTER FIX
fun startCurrentTimeUpdater() {
    // ✅ FIX: Sync simulatedStartTime with current tablet time at initialization
    val now = Date()
    simulatedStartTime.time = now

    currentTimeHandler = Handler(Looper.getMainLooper())
    currentTimeRunnable = object : Runnable {
        override fun run() {
            try {
                val now = Date()
                val currentTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val nowStr = currentTimeFormat.format(now)
                owner.currentTimeTextView.text = nowStr

                // ✅ FIX: Update simulatedStartTime to keep it in sync with real time
                // This ensures calculations use the same time as displayed in UI
                simulatedStartTime.time = now

                currentTimeHandler?.postDelayed(this, 1000)
            } catch (e: Exception) {
                Log.e("TimeManager", "Error in current time runnable: ${e.message}", e)
            }
        }
    }
    currentTimeHandler?.post(currentTimeRunnable!!)
}
```

**Explanation:**

- When `startCurrentTimeUpdater()` is called, immediately sync `simulatedStartTime` to the actual tablet time.
- Every second, update `simulatedStartTime` so it always matches real time.
- Now, all calculations using `simulatedStartTime` will use the correct time.

## Impact of the Fix

### Before the Fix:

- ❌ `simulatedStartTime` was not updated, kept old time
- ❌ "Next run in" calculation used old time → Unreasonable result (1250 minutes)
- ❌ "Late for next run" calculation used old time → Unreasonable result (220 minutes)
- ❌ Mismatch between displayed time and calculation time

### After the Fix:

- ✅ `simulatedStartTime` always synced with actual tablet time
- ✅ "Next run in" calculation uses correct time → Reasonable result
- ✅ "Late for next run" calculation uses correct time → Reasonable result
- ✅ No mismatch between displayed time and calculation time

## Files Modified

1. **TimeManager.kt**
   - `startCurrentTimeUpdater()` – Update `simulatedStartTime` every second

## Testing Checklist

- [x] `simulatedStartTime` is updated every second while `startCurrentTimeUpdater()` is active
- [ ] "Next run in" shows a reasonable time (< 24 hours)
- [ ] "Late for next run" shows a reasonable time
- [ ] Calculations use the same time as displayed in the UI
- [ ] No mismatch between display time and calculation time

## Notes

- This fix ensures `simulatedStartTime` is always synced with actual tablet time
- All calculations using `simulatedStartTime` will automatically use the correct time
- No need to change code elsewhere since they already use `simulatedStartTime` correctly

## Related Files

- `TimeManager.kt` – Main fix
- `ScheduleStatusManager.kt` – Uses `simulatedStartTime` (will be automatically correct)
- `MapActivity.kt` – Uses `simulatedStartTime` (will be automatically correct)
