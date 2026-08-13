package com.jason.publisher.main.loggers

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers that the driver already chose "Fetch Roster" earlier in this continuous app-open
 * session, so a crash mid-trip - which cold-starts the process straight back through
 * SplashActivity - never silently re-fetches from ThingsBoard and resets the schedule behind
 * their back. That re-fetch should only ever happen again from an explicit, deliberate choice:
 * fully closing the app and picking "Fetch Roster" again on the next open.
 *
 * Persisted (survives process death, unlike an in-memory flag). Only cleared once the app is
 * judged fully closed - see App.kt, which uses the same "no live activities for a settle
 * period" signal it already relies on elsewhere for "the app is closed".
 */
object FetchSessionStore {
    private const val PREFS = "fetch_session_prefs"
    private const val KEY_HAS_FETCHED = "has_fetched_this_open"

    fun markFetched(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_HAS_FETCHED, true)
        }
    }

    fun hasFetchedThisOpen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HAS_FETCHED, false)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_HAS_FETCHED)
        }
    }
}
