package com.jason.publisher.main.realtime

object LiveModeGuard {
    @Volatile var offlineScheduleMode: Boolean = false   // user memilih offline schedule
    fun allowLiveNetworking(hasInternet: Boolean): Boolean =
        hasInternet && !offlineScheduleMode
}
