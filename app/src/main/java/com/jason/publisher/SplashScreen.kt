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
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
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

    private lateinit var locationManager: LocationManager
    private lateinit var sharedPrefMananger: SharedPrefMananger
    private lateinit var binding: ActivitySplashScreenBinding
    private val client = OkHttpClient()

    var name = ""
    private var aaid = ""
    private var latitude = 0.0
    private var longitude = 0.0
    private var bearing = 0.0F
    private var speed = 0.0F
    private var direction = ""

    /**
     * Overrides the onCreate method to initialize the activity and perform necessary setup.
     * @param savedInstanceState Bundle containing the activity's previously saved state, if any.
     */
    @SuppressLint("HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d("version name", "test version v1.0.2")

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

        // Check for updates and then show the version info dialog
        // checkForUpdates()

        // Show version information after checking for updates
        versionInfo()
    }

    /**
     * Checks for updates by sending a request to the update server. If an update is available,
     * it shows an update dialog; otherwise, it proceeds to the mode selection dialog.
     */
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
                        // Pass the version to MainActivity
                        val intent = Intent(this@SplashScreen, MainActivity::class.java)
                        intent.putExtra("LATEST_VERSION", latestVersion)
                        startActivity(intent)
                        finish()  // End SplashScreen activity
                    }
                } else {
                    runOnUiThread {
                        showOptionDialog()
                    }
                }
            }
        })
    }

    /**
     * Compares the current version of the app with the latest version from the server.
     * @param currentVersion The current version of the app.
     * @param latestVersion The latest version available on the server.
     * @return true if an update is available, false otherwise.
     */
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

    /**
     * Displays a dialog to the user indicating that a new version is available for download.
     * @param updateUrl The URL where the new version can be downloaded.
     * @param latestVersion The latest version available on the server.
     */
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

    /**
     * Initiates the update process by opening the download URL in the device's browser.
     * @param updateUrl The URL where the new version can be downloaded.
     */
    private fun startUpdateProcess(updateUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse(updateUrl)
        startActivity(intent)
    }

    /**
     * Shows the mode selection dialog and handles the selected mode.
     */
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
     * Displays a dialog box with the current app version and the latest version available.
     * This method will be called after checking for updates.
     */
    private fun versionInfo() {
        // Fetch the latest version from the server
        val request = Request.Builder()
            .url("http://43.226.218.98:5000/api/latest-version")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SplashScreen", "Failed to fetch version information", e)
                // Show a failure dialog if the request fails
                runOnUiThread {
                    showFailureDialog("Failed to fetch version information. Please check your connection.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    val json = JSONObject(responseData!!)
                    val latestVersion = json.getString("version")
                    val currentVersion = BuildConfig.VERSION_NAME

                    // Show the version info dialog on the UI thread
                    runOnUiThread {
                        showVersionDialog(currentVersion, latestVersion)
                    }
                } else {
                    // Handle non-200 responses
                    runOnUiThread {
                        showFailureDialog("Unexpected server response.")
                    }
                }
            }
        })
    }

    /**
     * Displays a dialog with the app version information.
     * @param currentVersion The current version of the app.
     * @param latestVersion The latest version available from the server.
     */
    private fun showVersionDialog(currentVersion: String, latestVersion: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Version Information")
        builder.setMessage("Your app version is $currentVersion. The latest version is $latestVersion.")
        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
            // Optionally, you can proceed to the next screen here
        }
        builder.setCancelable(false)
        builder.show()
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
