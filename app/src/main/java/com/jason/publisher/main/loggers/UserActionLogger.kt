package com.jason.publisher.main.loggers

/**
 * Consistent, grep-able logging for what the driver actually DID — button taps, input changes,
 * and app-driven state transitions triggered by those actions. Paired with [FileLogger] (async,
 * non-blocking) and [TripStateSnapshot] (current page/trip context), this lets a bug report be
 * reconstructed purely from the log file: which screen, which button, what value, what changed.
 *
 * All lines share the "<Activity> UI_ACTION" tag so `adb logcat` / the log file can be grepped
 * with a single filter to reconstruct a full session of user interaction.
 */
object UserActionLogger {

    /** A button/icon/menu item was tapped. [detail] can carry extra context, e.g. which run/stop. */
    fun click(activity: String, control: String, detail: String? = null) {
        FileLogger.i(
            "$activity UI_ACTION",
            "CLICK | control=$control" + (detail?.let { " | $it" } ?: "") + " | ${TripStateSnapshot.describe()}"
        )
    }

    /** A text field, spinner, switch, or similar input changed value. */
    fun inputChanged(activity: String, field: String, newValue: Any?) {
        FileLogger.i(
            "$activity UI_ACTION",
            "INPUT_CHANGED | field=$field | value=$newValue | ${TripStateSnapshot.describe()}"
        )
    }

    /** A meaningful in-memory variable/state transition, usually caused by a user action. */
    fun stateChanged(activity: String, variable: String, oldValue: Any?, newValue: Any?) {
        FileLogger.d(
            "$activity UI_ACTION",
            "STATE_CHANGED | $variable: $oldValue -> $newValue"
        )
    }

    /** A dialog/toast/screen was shown to the user — useful to know what they were told. */
    fun shown(activity: String, what: String, detail: String? = null) {
        FileLogger.i(
            "$activity UI_ACTION",
            "SHOWN | $what" + (detail?.let { " | $it" } ?: "")
        )
    }
}
