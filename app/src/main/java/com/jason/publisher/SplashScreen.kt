package com.jason.publisher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

@SuppressLint("CustomSplashScreen")
class SplashScreen : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var sharedPrefManager: SharedPrefMananger
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

        aaid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        sharedPrefManager = SharedPrefMananger(this)
        locationManager = LocationManager(this)
        startLocationUpdate()

        // Start animation
        val logoExplorer = findViewById<ImageView>(R.id.logoExplorer)
        val logoFullers = findViewById<ImageView>(R.id.logoFullers)
        val animation = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        logoExplorer.startAnimation(animation)
        logoFullers.startAnimation(animation)

        checkForUpdates()
    }

    /**
     * Checks for updates by sending a request to the update server. If an update is available,
     * it shows an update dialog; otherwise, it proceeds to the next screen.
     */
    private fun checkForUpdates() {
        val request = Request.Builder()
            .url("http://43.226.218.98:5000/api/latest-version")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SplashScreen", "Failed to check for updates", e)
                runOnUiThread {
                    proceedToNextScreen()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    val json = JSONObject(responseData!!)
                    val latestVersion = json.getString("version")
                    val updateUrl = json.getString("url")
                    val currentVersion = BuildConfig.VERSION_NAME

                    if (isUpdateAvailable(currentVersion, latestVersion)) {
                        runOnUiThread {
                            showUpdateDialog(updateUrl, latestVersion)
                        }
                    } else {
                        runOnUiThread {
                            proceedToNextScreen()
                        }
                    }
                } else {
                    runOnUiThread {
                        proceedToNextScreen()
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
        runOnUiThread {
            val dialog = Dialog(this)
            dialog.setContentView(R.layout.update_dialog)
            dialog.setCancelable(false)

            val updateButton = dialog.findViewById<Button>(R.id.updateButton)
            val cancelButton = dialog.findViewById<Button>(R.id.cancelButton)
            val versionInfoTextView = dialog.findViewById<TextView>(R.id.versionInfoTextView)

            versionInfoTextView.text = "A new version $latestVersion is available."

            updateButton.setOnClickListener {
                startUpdateProcess(updateUrl)
                dialog.dismiss()
            }

            cancelButton.setOnClickListener {
                proceedToNextScreen()
                dialog.dismiss()
            }

            dialog.show()
        }
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
     * Proceeds to the next screen (MainActivity) after a delay.
     */
    private fun proceedToNextScreen() {
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }, 2000)
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
}
