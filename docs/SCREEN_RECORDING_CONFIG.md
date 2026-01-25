# Screen Recording Configuration Guide

## Overview

Screen recording has been optimized to prevent performance issues (lag, high RAM usage). **Built-in screen recording is now DISABLED by default** to ensure the app runs smoothly.

## Issues Resolved

- ✅ **Built-in recording caused lag and freezing** - Now DISABLED by default
- ✅ **High RAM usage** - Recording will only activate when truly needed
- ✅ **UI elements not updating** - With recording disabled, the app runs more smoothly

## Configuration

### Option 1: Use a 3rd Party App (RECOMMENDED)

**Using a third-party app like AZ Screen Recorder offers better performance** because it doesn't burden the main app.

#### How to Enable 3rd Party Recording:

```kotlin
// From anywhere in your app code
import com.jason.publisher.main.activity.SplashActivity

// Enable auto-launch of AZ Screen Recorder when the app starts
SplashActivity.setThirdPartyRecordingEnabled(this, true)

// Or use another recording app
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
- Or any other recording app's package name

### Option 2: Enable Built-in Recording (NOT RECOMMENDED)

**Only use if absolutely necessary** as it will affect app performance.

```kotlin
// Enable built-in recording (not recommended due to performance impact)
SplashActivity.setBuiltinRecordingEnabled(this, true)
```

## How to Use for Driver Documentation

### Setup for Testing on Driver Tablet:

1. **Install AZ Screen Recorder** on the tablet (or another recording app)
2. **Enable 3rd party recording** with the following code:

```kotlin
// Add in onCreate() of MapActivity or SplashActivity
SplashActivity.setThirdPartyRecordingEnabled(this, true)
```

3. **When the app opens**, AZ Screen Recorder will automatically launch
4. **Driver just needs to press record** in AZ Screen Recorder
5. **BusFlow app will run optimally** with no lag

### Check Status:

```kotlin
// Check if built-in recording is enabled
val isBuiltinEnabled = SplashActivity.isBuiltinRecordingEnabled(this)

// Check if 3rd party recording is enabled
val is3rdPartyEnabled = SplashActivity.isThirdPartyRecordingEnabled(this)
```

## Default Behavior

- ✅ **Built-in recording: DISABLED** (default)
- ✅ **3rd party recording: DISABLED** (default)
- ✅ **App runs optimally** without recording overhead

## Troubleshooting

### If 3rd Party App Does Not Launch:

1. Make sure the app is installed on the device
2. Check the package name used
3. If the app is not found, the Play Store will automatically open

### If There Is Still Lag:

1. Make sure built-in recording is **DISABLED**:

   ```kotlin
   SplashActivity.setBuiltinRecordingEnabled(this, false)
   ```

2. Make sure no recording service is still running
3. Restart the app after changing the configuration

## Performance Impact

| Mode                       | CPU Usage | RAM Usage | Performance      |
| -------------------------- | --------- | --------- | ---------------- |
| **No Recording** (default) | Normal    | Normal    | ✅ Optimal       |
| **3rd Party App**          | Normal    | Normal    | ✅ Optimal       |
| **Built-in Recording**     | High      | High      | ⚠️ May cause lag |

## Notes

- Built-in recording consumes significant resources due to real-time video encoding
- 3rd party apps are usually more efficient as they are specialized for recording
- For driver documentation, use a 3rd party app for best results

```

```
