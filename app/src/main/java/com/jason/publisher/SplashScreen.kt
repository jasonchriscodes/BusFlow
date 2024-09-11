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
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import java.util.UUID
import android.content.SharedPreferences
import java.io.File

@SuppressLint("CustomSplashScreen")
class SplashScreen : AppCompatActivity() {

    private lateinit var client: OkHttpClient
    private lateinit var aid: String
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

        Log.d("version name", "test v1.0.31")

        aid = getOrCreateAid()

        Log.d("Android ID", aid)

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

        // Check for updates based on UUID
        checkForUpdates()

        showOptionDialog()
    }

    /** Retrieve the AID from the external folder or generate a new one */
    @SuppressLint("HardwareIds")
    private fun getOrCreateAid(): String {
        val hiddenDir = File(getExternalFilesDir(null), ".vlrshiddenfolder")
        if (!hiddenDir.exists()) {
            hiddenDir.mkdirs()
        }
        val aidFile = File(hiddenDir, "aid.txt")

        return if (aidFile.exists()) {
            aidFile.readText()
        } else {
            val newAid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            aidFile.writeText(newAid)
            newAid
        }
    }

    /** Check for app updates using the generated UUID. */
    private fun checkForUpdates() {
        val requestCurrent = Request.Builder()
            .url("http://43.226.218.98:5000/api/current-version/$aid")
            .build()

        val requestLatest = Request.Builder()
            .url("http://43.226.218.98:5000/api/latest-version")
            .build()

        client.newCall(requestCurrent).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SplashScreen", "Failed to fetch current version information", e)
                runOnUiThread {
                    showFailureDialog("Failed to fetch current version information. Please check your connection.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    val json = JSONObject(responseData!!)
                    val currentVersion = json.getString("version")

                    client.newCall(requestLatest).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            Log.e("SplashScreen", "Failed to fetch latest version information", e)
                            runOnUiThread {
                                showFailureDialog("Failed to fetch latest version information. Please check your connection.")
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (response.isSuccessful) {
                                val responseData = response.body?.string()
                                val json = JSONObject(responseData!!)
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

    /** Proceed to MainActivity and pass the AID */
    private fun proceedToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("AID", aid)  // Pass the AID to MainActivity
        startActivity(intent)
        finish()
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
            proceedToMainActivity()
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

    /** Show a dialog with Android ID and UUID options to copy to clipboard. */
    private fun showWhoAmIDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Device Information")

        builder.setMessage("Android ID: $aid")

        builder.setPositiveButton("Copy Android ID") { dialog, _ ->
            copyToClipboard("Android ID", aid)
            Toast.makeText(this, "Android ID copied to clipboard", Toast.LENGTH_SHORT).show()
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
            showUninstallPrompt()
        }

        builder.setCancelable(false)
        builder.show()
    }

    /** Show a prompt asking the user to uninstall the current app and download the latest version from the browser. */
    private fun showUninstallPrompt() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Uninstall and Download")
        builder.setMessage("To update to the latest version, please uninstall the current app and download the latest one from the browser.")

        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
            // Redirect to browser to download the latest APK
            val apkDownloadUrl = "http://43.226.218.98:5000/api/download-latest-apk"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkDownloadUrl))
            startActivity(intent)
        }

        builder.setCancelable(false)
        builder.show()
    }

    /** Update the current folder on the server with the latest version for the device UUID. */
    private fun updateCurrentVersionOnServer() {
        val request = Request.Builder()
            .url("http://43.226.218.98:5000/api/update-current-folder/$aid")
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
