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
import androidx.core.content.FileProvider
import com.jason.publisher.databinding.ActivitySplashScreenBinding
import com.jason.publisher.services.LocationManager
import com.jason.publisher.services.SharedPrefMananger
import okhttp3.*
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.File
import java.io.IOException

@SuppressLint("CustomSplashScreen")
class SplashScreen : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var sharedPrefMananger: SharedPrefMananger
    private lateinit var binding: ActivitySplashScreenBinding
    private val client = OkHttpClient()

    private var aaid = ""
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

        Log.d("version name", "test v1.0.13")

        aaid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        sharedPrefMananger = SharedPrefMananger(this)
        locationManager = LocationManager(this)
        startLocationUpdate()

        // Start animation
        val logoExplorer = findViewById<ImageView>(R.id.logoExplorer)
        val logoFullers = findViewById<ImageView>(R.id.logoFullers)
        val animation = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        logoExplorer.startAnimation(animation)
        logoFullers.startAnimation(animation)

        // Check for updates and show the version info dialog if necessary
        checkForUpdates()
    }

    /**
     * Checks for updates by sending a request to the update server.
     * If an update is available, it shows an update dialog; otherwise, it proceeds to the next activity.
     */
    private fun checkForUpdates() {
        val requestLatest = Request.Builder()
            .url("http://43.226.218.98:5000/api/latest-version")
            .build()
        val requestCurrent = Request.Builder()
            .url("http://43.226.218.98:5000/api/current-version")
            .build()

        // Fetch the current version
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

                    // Fetch the latest version after getting the current version
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

                                // If the current version matches the latest version, show that it's up to date
                                if (currentVersion == latestVersion) {
                                    runOnUiThread {
                                        showUpToDateDialog(currentVersion)
                                    }
                                } else {
                                    // Show the version update dialog
                                    runOnUiThread {
                                        showVersionDialog(currentVersion, latestVersion)
                                    }
                                }
                            } else {
                                runOnUiThread {
                                    showFailureDialog("Unexpected server response while fetching latest version.")
                                }
                            }
                        }
                    })
                } else {
                    runOnUiThread {
                        showFailureDialog("Unexpected server response while fetching current version.")
                    }
                }
            }
        })
    }

    /**
     * Displays a dialog indicating that the app is up to date.
     * @param version The version that is up to date.
     */
    private fun showUpToDateDialog(version: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Version Information")
        builder.setMessage("Your version $version is up to date.")
        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
            proceedToNextScreen()
        }
        builder.setCancelable(false)
        builder.show()
    }

    /**
     * Displays a dialog with the app version information and provides options to update.
     * @param currentVersion The current version of the app.
     * @param latestVersion The latest version available from the server.
     */
    private fun showVersionDialog(currentVersion: String, latestVersion: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Version Information")
        builder.setMessage("Your app version is $currentVersion. The latest version is $latestVersion.")

        // Cancel button to dismiss the dialog and proceed to the next screen
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
            proceedToNextScreen()
        }

        // Update button to uninstall the app and trigger the update process
        builder.setPositiveButton("Update") { dialog, _ ->
            dialog.dismiss()
            uninstallApp() // Uninstall the current app
        }

        builder.setCancelable(false)
        builder.show()
    }

    /**
     * Proceeds to the next screen after the version dialog is dismissed.
     */
    private fun proceedToNextScreen() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
    }

    /**
     * Opens the system's uninstallation screen for the current app.
     * This will prompt the user to manually uninstall the app.
     * After the app is uninstalled, the user can proceed with downloading and installing the new version.
     */
    private fun uninstallApp() {
        // Create an Intent to initiate the uninstallation process
        val intent = Intent(Intent.ACTION_DELETE)

        // Set the package URI to the current app's package name (this app)
        intent.data = Uri.parse("package:$packageName")

        // Start the activity to display the system's uninstall prompt
        startActivity(intent)

        // After uninstallation, download and install the updated APK
        downloadAndUpdateApp()
    }

    /**
     * Downloads the latest APK from the server and triggers installation.
     */
    private fun downloadAndUpdateApp() {
        val apkUrl = "http://43.226.218.98:5000/api/download-latest-apk"
        Log.d("SplashScreen", "Attempting to download APK from $apkUrl")

        val request = Request.Builder().url(apkUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SplashScreen", "Failed to download the APK: $e")
                runOnUiThread {
                    showFailureDialog("Failed to download the update. Please check your connection.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e("SplashScreen", "Server responded with code: ${response.code}")
                    runOnUiThread {
                        showFailureDialog("Unexpected server response during APK download.")
                    }
                    return
                }

                // Save the APK file
                val apkFile = File(getExternalFilesDir(null), "update.apk")
                try {
                    val sink = apkFile.sink().buffer()
                    sink.writeAll(response.body!!.source())
                    sink.close()
                    Log.d("SplashScreen", "APK downloaded successfully to ${apkFile.absolutePath}")

                    // Install the downloaded APK
                    runOnUiThread {
                        installApk(apkFile)
                    }
                } catch (e: IOException) {
                    Log.e("SplashScreen", "Failed to save the APK: $e")
                    runOnUiThread {
                        showFailureDialog("Failed to save the update. Please try again.")
                    }
                }
            }
        })
    }

    /**
     * Initiates the installation of the downloaded APK.
     * @param apkFile The file object of the downloaded APK.
     */
    private fun installApk(apkFile: File) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(FileProvider.getUriForFile(this, "$packageName.provider", apkFile), "application/vnd.android.package-archive")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        startActivity(intent)

        // After installation, update the server's current version
        updateCurrentVersionOnServer()
    }

    /**
     * Sends a request to update the "current" folder on the server with the contents of the "latest" folder.
     */
    private fun updateCurrentVersionOnServer() {
        val request = Request.Builder()
            .url("http://43.226.218.98:5000/api/update-current-folder")
            .post(RequestBody.create(null, ByteArray(0)))  // POST request with an empty body
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
                        Toast.makeText(this@SplashScreen, "Current version updated successfully.", Toast.LENGTH_SHORT).show()
                        proceedToNextScreen()
                    }
                } else {
                    runOnUiThread {
                        showFailureDialog("Unexpected server response while updating the current version.")
                    }
                }
            }
        })
    }

    /**
     * Starts location updates if the necessary permissions are granted.
     */
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

    /**
     * Displays a failure dialog when there is an issue with fetching data.
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
}
