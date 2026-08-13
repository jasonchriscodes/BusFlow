package com.jason.publisher.main.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import android.provider.Settings
import android.util.Log
import com.jason.publisher.main.loggers.FileLogger
import java.io.File

/**
 * Resolves this device's canonical AID - the identifier ThingsBoard's bus config is actually
 * keyed on. NOT necessarily the same as the raw hardware [Settings.Secure.ANDROID_ID]: if
 * Documents/.vlrshiddenfolder/aid.txt already holds a value, that value wins (it's written once,
 * the first time this ever runs on a device, and is what a device gets registered under - a
 * factory reset or app reinstall can change ANDROID_ID without this file changing to match).
 *
 * Any code that needs to identify "this device" to ThingsBoard must go through this rather than
 * reading ANDROID_ID directly - confirmed on-device that skipping the file lookup resolves to an
 * AID with no matching bus config entry at all, silently breaking any attribute publish keyed on
 * the wrong id.
 */
@SuppressLint("HardwareIds")
fun getOrCreateDeviceAid(context: Context): String {
    return try {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val hiddenFolder = File(documentsDir, ".vlrshiddenfolder")
        val aidFile = File(hiddenFolder, "aid.txt")

        if (aidFile.exists()) {
            val storedAid = aidFile.readText().trim()
            if (storedAid.isNotEmpty()) {
                Log.d("DeviceAid", "Loaded AID from file: $storedAid")
                return storedAid
            }
        }

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        Log.d("DeviceAid", "Generated AID from device: $androidId")

        if (!hiddenFolder.exists()) hiddenFolder.mkdirs()
        aidFile.writeText(androidId)
        Log.d("DeviceAid", "Saved AID to file: ${aidFile.absolutePath}")

        androidId
    } catch (e: Exception) {
        FileLogger.e("DeviceAid", "Error handling AID file | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
    }
}
