package com.jason.publisher.main.utils

import android.content.Context
import android.os.Environment
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * Reads token from Documents/.vlrshiddenfolder/busDataCache.txt
 * structure written by saveBusDataToCache(): { "aid": "...", "config":[{aid, accessToken, ...}], ... }
 */
object TokenStore {
    private const val TAG = "TokenStore"

    data class MinimalBusItem(val aid: String?, val accessToken: String?)
    data class CacheShape(val aid: String?, val config: List<MinimalBusItem>?)

    fun getAccessToken(context: Context): String? {
        return try {
            val cacheFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                ".vlrshiddenfolder/busDataCache.txt"
            )
            if (!cacheFile.exists()) return null

            val json = cacheFile.readText()
            val cache = Gson().fromJson(json, CacheShape::class.java) ?: return null

            val myAid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            val match = cache.config?.firstOrNull { it.aid == myAid }?.accessToken
            if (match.isNullOrBlank()) null else match
        } catch (e: Exception) {
            Log.e(TAG, "Error reading token: ${e.message}")
            null
        }
    }
}

