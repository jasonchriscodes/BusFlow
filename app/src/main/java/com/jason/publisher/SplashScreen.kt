package com.jason.publisher

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jason.publisher.databinding.ActivitySplashScreenBinding
import com.jason.publisher.services.LocationManager
import com.jason.publisher.services.SharedPrefMananger
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

@SuppressLint("CustomSplashScreen")
class SplashScreen : AppCompatActivity() {

    private lateinit var client: OkHttpClient
    private lateinit var imei: String
    private lateinit var aaid: String
    private lateinit var binding: ActivitySplashScreenBinding
    private lateinit var locationManager: LocationManager
    private lateinit var sharedPrefManager: SharedPrefMananger

    var latitude = 0.0
    var longitude = 0.0
    var bearing = 0.0F
    var speed = 0.0F
    var direction = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d("version name", "test v1.0.32")

        // Retrieve the device-specific Android ID (AAID)
        aaid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        Log.d("Android ID", aaid)

        client = OkHttpClient()

        // Initialize shared preference manager and location manager
        sharedPrefManager = SharedPrefMananger(this)
        locationManager = LocationManager(this)
        startLocationUpdate()

        // Start the animation for the splash screen
        val logoExplorer = findViewById<ImageView>(R.id.logoExplorer)
        val logoFullers = findViewById<ImageView>(R.id.logoFullers)
        val animation = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        logoExplorer.startAnimation(animation)
        logoFullers.startAnimation(animation)

        // Request permission and get the IMEI
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 1)
        } else {
            imei = getIMEI()
            Log.d("IMEI", imei)
            checkForUpdates()
        }

        showOptionDialog()
    }

    /**
     * Handle the result of the permission request
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                imei = getIMEI()
                Log.d("IMEI", imei)
                checkForUpdates()
            } else {
                Toast.makeText(this, "Permission denied to read phone state", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Get IMEI from device
     */
    @SuppressLint("HardwareIds")
    private fun getIMEI(): String {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10 and above
            telephonyManager.imei ?: "Unknown"
        } else {
            telephonyManager.deviceId ?: "Unknown"
        }
    }

    /** Check for app updates using IMEI. */
    private fun checkForUpdates() {
        val request = okhttp3.Request.Builder()
            .url("http://43.226.218.98:5000/api/current-version/$imei")
            .build()

        val requestLatest = Request.Builder()
            .url("http://43.226.218.98:5000/api/latest-version")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("SplashScreen", "Failed to fetch version info", e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    Log.d("Version Info", responseData ?: "No response")

                    client.newCall(requestLatest).enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: IOException) {
                            Log.e("SplashScreen", "Failed to fetch latest version information", e)
                            runOnUiThread {
                                showFailureDialog("Failed to fetch latest version information. Please check your connection.")
                            }
                        }

                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            if (response.isSuccessful) {
                                val responseData = response.body?.string()
                                val json = JSONObject(responseData!!)
                                val currentVersion = json.getString("version")
                                val latestVersion = json.getString("version")

                                Log.d("currentVersion", currentVersion)
                                Log.d("latestVersion", latestVersion)

                                if (currentVersion == latestVersion) {
                                    runOnUiThread {
                                        showUpToDateDialog(currentVersion)
                                    }
                                } else {
                                    runOnUiThread {
                                        showVersionDialog(currentVersion, latestVersion)
                                    }
                                }
                            } else {
                                runOnUiThread {
                                    showFailureDialog("Unexpected server response while fetching the latest version.")
                                }
                            }
                        }
                    })
                } else {
                    runOnUiThread {
                        showFailureDialog("Unexpected server response while fetching the current version.")
                    }
                }
            }
        })
    }

    /** Shows a dialog with options for online, offline modes, and device information. */
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
            showWhoAmIDialog()
        }

        val dialog = builder.create()
        dialog.show()
    }

    /** Show a dialog with Android ID and IMEI options to copy to clipboard. */
    private fun showWhoAmIDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Device Information")

        builder.setMessage("Android ID: $aaid\nIMEI: $imei")

        builder.setPositiveButton("Copy Android ID") { dialog, _ ->
            copyToClipboard("Android ID", aaid)
            Toast.makeText(this, "Android ID copied to clipboard", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        builder.setNegativeButton("Copy IMEI") { dialog, _ ->
            copyToClipboard("IMEI", imei)
            Toast.makeText(this, "IMEI copied to clipboard", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        builder.setNeutralButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        builder.setCancelable(false)
        builder.show()
    }

    /** Copy a specific text to the clipboard. */
    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * Show an error dialog if fetching data fails.
     * @param message The error message to display.
     */
    private fun showFailureDialog(message: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Error")
        builder.setMessage(message)
        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
        }
        builder.setCancelable(false)
        builder.show()
    }

    /**
     * Show a dialog when the app is up to date.
     * @param version The version of the app that is up to date.
     */
    private fun showUpToDateDialog(version: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Version Information")
        builder.setMessage("Your version $version is up to date.")
        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
        }
        builder.setCancelable(false)
        builder.show()
    }

    /** Show a dialog with version information and update option if available. */
    private fun showVersionDialog(currentVersion: String, latestVersion: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Version Information")
        builder.setMessage("Your app version is $currentVersion. The latest version is $latestVersion.")

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        builder.setPositiveButton("Update") { dialog, _ ->
            dialog.dismiss()
            updateCurrentVersionOnServer()
        }

        builder.setCancelable(false)
        builder.show()
    }

    /** Update the current folder on the server with the latest version for the device IMEI. */
    private fun updateCurrentVersionOnServer() {
        val request = Request.Builder()
            .url("http://43.226.218.98:5000/api/update-current-folder/$imei")
            .post(RequestBody.create(null, ByteArray(0)))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SplashScreen", "Failed to update the current version on the server", e)
                runOnUiThread {
                    showFailureDialog("Failed to update the current version on the server. Please check your connection.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    runOnUiThread {
                    }
                } else {
                    runOnUiThread {
                        showFailureDialog("Unexpected server response while updating the current version.")
                    }
                }
            }
        })
    }

    /** Start location updates if permissions are granted. */
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
