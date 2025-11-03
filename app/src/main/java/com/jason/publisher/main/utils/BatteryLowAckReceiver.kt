package com.jason.publisher.main.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.jason.publisher.main.utils.FileLogger

class BatteryLowAckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        NotificationManagerCompat.from(context).cancel(id)
        FileLogger.i("BatteryLowWatcher", "User acknowledged battery-low notification (id=$id)")
    }

    companion object {
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
