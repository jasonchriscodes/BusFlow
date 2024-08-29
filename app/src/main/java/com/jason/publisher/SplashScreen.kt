package com.jason.publisher

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.jason.publisher.databinding.ActivitySplashScreenBinding
import com.jason.publisher.services.LocationManager
import com.jason.publisher.services.SharedPrefMananger
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

@SuppressLint("CustomSplashScreen")
class SplashScreen : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var sharedPrefMananger: SharedPrefMananger
    private lateinit var binding: ActivitySplashScreenBinding
    private val client = OkHttpClient()

    var aaid = ""
    private var latitude = 0.0
    private var longitude = 0.0
    private var bearing = 0.0F
    private var speed = 0.0F
    private var direction = ""

    @SuppressLint("HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        aaid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        sharedPrefMananger = SharedPrefMananger(this)
        locationManager = LocationManager(this)
        startLocationUpdate()

        val logoExplorer = findViewById<ImageView>(R.id.logoExplorer)
        val logoFullers = findViewById<ImageView>(R.id.logoFullers)
        val animation = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        logoExplorer.startAnimation(animation)
        logoFullers.startAnimation(animation)

        // Report version to server
        val tabletId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val currentVersion = BuildConfig.VERSION_NAME
        reportVersionToServer(tabletId, currentVersion)

        // Check for updates and then show the mode selection dialog
        checkForUpdates()
    }

    fun reportVersionToServer(tabletId: String, currentVersion: String) {
        val client = OkHttpClient()

        // Create the JSON body
        val jsonBody = JSONObject().apply {
            put("device_id", tabletId)  // Ensure key matches the server expectation
            put("version", currentVersion)
        }.toString()

        // Create the RequestBody
        val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())

        // Build the request
        val request = Request.Builder()
            .url("http://43.226.218.98:5000/api/report-version")
            .post(requestBody)
            .build()

        // Execute the request asynchronously
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                // Handle failure
                e.printStackTrace()
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                // Handle response
                if (response.isSuccessful) {
                    println("Version reported successfully")
                } else {
                    println("Failed to report version: ${response.code}")
                }
            }
        })
    }

    private fun checkForUpdates() {
        val request = Request.Builder()
            .url("http://43.226.218.98:5000/api/latest-version")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SplashScreen", "Failed to check for updates", e)
                runOnUiThread {
                    showOptionDialog()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    val json = JSONObject(responseData!!)
                    val latestVersion = json.getString("version")
                    val updateUrl = "http://43.226.218.98:5000/apk/app-v$latestVersion.apk"

                    if (isUpdateAvailable(BuildConfig.VERSION_NAME, latestVersion)) {
                        runOnUiThread {
                            showUpdateDialog(updateUrl, latestVersion)
                        }
                    } else {
                        runOnUiThread {
                            showOptionDialog()  // Show the mode selection dialog after checking for updates
                        }
                    }
                } else {
                    runOnUiThread {
                        showOptionDialog()
                    }
                }
            }
        })
    }

    private fun isUpdateAvailable(currentVersion: String, latestVersion: String): Boolean {
        val currentVersionParts = currentVersion.split('.').map { it.toInt() }
        val latestVersionParts = latestVersion.split('.').map { it.toInt() }

        for (i in currentVersionParts.indices) {
            if (latestVersionParts[i] > currentVersionParts[i]) {
                return true
            } else if (latestVersionParts[i] < currentVersionParts[i]) {
                return false
            }
        }
        return false
    }

    private fun showUpdateDialog(updateUrl: String, latestVersion: String) {
        val builder = AlertDialog.Builder(this)
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.update_dialog, null)
        builder.setView(dialogView)

        val updateButton = dialogView.findViewById<Button>(R.id.updateButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val versionInfoTextView = dialogView.findViewById<TextView>(R.id.versionInfoTextView)

        versionInfoTextView.text = "A new version $latestVersion is available."

        val dialog = builder.create()
        dialog.setCancelable(false)

        updateButton.setOnClickListener {
            startUpdateProcess(updateUrl)
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
            showOptionDialog()
        }

        dialog.show()
    }

    private fun startUpdateProcess(updateUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse(updateUrl)
        startActivity(intent)
    }

    private fun showOptionDialog() {
        val builder = AlertDialog.Builder(this)
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.mode_selection_dialog, null)
        builder.setView(dialogView)

        val onlineModeButton = dialogView.findViewById<Button>(R.id.onlineModeButton)
        val offlineModeButton = dialogView.findViewById<Button>(R.id.offlineModeButton)
        val whoAmIButton = dialogView.findViewById<Button>(R.id.whoAmIButton)

        onlineModeButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }

        offlineModeButton.setOnClickListener {
            val intent = Intent(this, OfflineActivity::class.java)
            startActivity(intent)
        }

        whoAmIButton.setOnClickListener {
            val aid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Device AID", aid)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Device AID copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        val dialog = builder.create()
        dialog.show()
    }

    private fun startLocationUpdate() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.startLocationUpdates(object : LocationListener {
                override fun onLocationUpdate(location: Location) {
                    latitude = location.latitude
                    longitude = location.longitude
                    bearing = location.bearing
                    speed = location.speed
                    direction = Helper.bearingToDirection(location.bearing)
                }
            })
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                123
            )
        }
    }
}
