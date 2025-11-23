# Screen Recording Configuration Guide

## Overview

Screen recording has been optimized to prevent performance issues (lag, high RAM usage). **Built-in screen recording is now DISABLED by default** to ensure the app runs with optimal performance.

## Issues Addressed

- ✅ **Built-in recording caused lag and freezes** — now DISABLED by default
- ✅ **High RAM usage** — recording is only enabled when truly needed
- ✅ **UI elements not updating smoothly** — with recording disabled, the app runs more fluidly

## Configuration

### Option 1: Use a 3rd-Party App (RECOMMENDED)

**Using a third-party app like AZ Screen Recorder provides better performance** because it doesn’t burden the main app.

#### How to Enable 3rd-Party Recording:

```kotlin
// From anywhere in the app
import com.jason.publisher.main.activity.SplashActivity

// Enable auto-launch of AZ Screen Recorder when the app starts
SplashActivity.setThirdPartyRecordingEnabled(this, true)

// Or use another recorder app
SplashActivity.setThirdPartyRecordingEnabled(
    this,
    true,
    "com.hecorat.screenrecorder.free" // AZ Screen Recorder
)
```

#### Supported Apps:

- **AZ Screen Recorder** (default): `com.hecorat.screenrecorder.free`
- **Mobizen**: `com.rsupport.mvagent`
- **DU Recorder**: `com.duapps.recorder`
- Or any other app package name you prefer

### Option 2: Enable Built-in Recording (NOT RECOMMENDED)

**Only use this if absolutely necessary** because it affects app performance.

```kotlin
// Enable built-in recording (not recommended because it impacts performance)
SplashActivity.setBuiltinRecordingEnabled(this, true)
```

## How to Use for Driver Documentation

### Setup for Testing on Driver Tablets:

1. **Install AZ Screen Recorder** on the tablet (or another recorder app)
2. **Enable 3rd-party recording** with the code below:

```kotlin
// Add this in onCreate() of MapActivity or SplashActivity
SplashActivity.setThirdPartyRecordingEnabled(this, true)
```

3. **When the app opens**, AZ Screen Recorder will auto-launch
4. **The driver just taps Record** in AZ Screen Recorder
5. **BusFlow runs at optimal performance** without lag

### Check Status:

```kotlin
// Check whether built-in recording is enabled
val isBuiltinEnabled = SplashActivity.isBuiltinRecordingEnabled(this)

// Check whether 3rd-party recording is enabled
val is3rdPartyEnabled = SplashActivity.isThirdPartyRecordingEnabled(this)
```

## Default Behavior

- ✅ **Built-in recording: DISABLED** (default)
- ✅ **3rd-party recording: DISABLED** (default)
- ✅ **App runs with optimal performance** without recording overhead

## Troubleshooting

### If the 3rd-Party App Doesn’t Auto-Launch:

1. Make sure the app is installed on the device
2. Verify the package name being used
3. If the app isn’t found, the Play Store will open automatically

### If There’s Still Lag:

1. Ensure built-in recording is **DISABLED**:

   ```kotlin
   SplashActivity.setBuiltinRecordingEnabled(this, false)
   ```

2. Make sure no recording service is still running
3. Restart the app after changing configuration

## Performance Impact

| Mode                       | CPU Usage | RAM Usage | Performance      |
| -------------------------- | --------- | --------- | ---------------- |
| **No Recording** (default) | Normal    | Normal    | ✅ Optimal       |
| **3rd-Party App**          | Normal    | Normal    | ✅ Optimal       |
| **Built-in Recording**     | High      | High      | ⚠️ May cause lag |

## Notes

- Built-in recording consumes a lot of resources due to real-time video encoding
- 3rd-party apps are usually more efficient because they’re optimized specifically for recording
- For driver documentation, use a 3rd-party recorder for best results
