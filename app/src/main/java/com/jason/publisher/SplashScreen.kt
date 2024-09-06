package com.jason.publisher

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

@SuppressLint("CustomSplashScreen")
class SplashScreen : AppCompatActivity() {

    /** Declare variables for network client, Android ID (AAID), and view binding */
    private lateinit var client: OkHttpClient
    private lateinit var aaid: String
    private lateinit var binding: ActivitySplashScreenBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d("version name", "test v1.0.28")

        // Dynamically retrieve the device-specific Android ID (AAID)
        aaid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        Log.d("Android ID", aaid)  // Log the Android ID for debugging purposes

        client = OkHttpClient()

        // Start the animation for the splash screen
        val logoExplorer = findViewById<ImageView>(R.id.logoExplorer)
        val logoFullers = findViewById<ImageView>(R.id.logoFullers)
        val animation = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        logoExplorer.startAnimation(animation)
        logoFullers.startAnimation(animation)

        // Check for updates dynamically based on the device-specific aid (Android ID)
        checkForUpdates()

        showOptionDialog()
    }

    /**
     * Function to check for app updates dynamically using the tablet's Android ID (aaid).
     */
    private fun checkForUpdates() {
        // Fetch the current version for the specific device based on the Android ID
        val requestCurrent = Request.Builder()
            .url("http://43.226.218.98:5000/api/current-version/$aaid")
            .build()

        // Fetch the latest version available on the server
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
                    val currentVersion = json.getString("version")  // Current version for the device

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
                                val latestVersion = json.getString("version")  // Latest version from the server
                                Log.d("currentVersion", currentVersion)
                                Log.d("latestVersion", latestVersion)

                                // Compare the current version with the latest version
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
     * Show a dialog if an update is available, allowing the user to update the app.
     * @param currentVersion The current version of the app.
     * @param latestVersion The latest version available on the server.
     */
    private fun showVersionDialog(currentVersion: String, latestVersion: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Version Information")
        builder.setMessage("Your app version is $currentVersion. The latest version is $latestVersion.")

        /** Cancel button to dismiss the dialog */
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        /** Update button to initiate the update process */
        builder.setPositiveButton("Update") { dialog, _ ->
            dialog.dismiss()
            updateCurrentVersionOnServer()  // Update the current folder for this aid
        }

        builder.setCancelable(false)
        builder.show()
    }

    /**
     * Function to update the server's current folder for the specific AAID.
     */
    private fun updateCurrentVersionOnServer() {
        val request = Request.Builder()
            .url("http://43.226.218.98:5000/api/update-current-folder/$aaid")
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
                        uninstallApp()
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
     * Uninstall the current app version and prompt the user to install the latest version.
     */
    private fun uninstallApp() {
        val intent = Intent(Intent.ACTION_DELETE)
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
        showDownloadPrompt()
    }

    /**
     * Show a dialog prompting the user to download the latest version of the app.
     */
    private fun showDownloadPrompt() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Download Latest Version")
        builder.setMessage("Please uninstall the app first and visit http://43.226.218.98:5000/api/download-latest-apk to download the latest version.")
        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
            openDownloadUrl()
        }
        builder.setCancelable(false)
        builder.show()
    }

    /**
     * Open the URL to download the latest APK in the browser.
     */
    private fun openDownloadUrl() {
        val downloadUrl = "http://43.226.218.98:5000/api/download-latest-apk"
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
        startActivity(browserIntent)
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
}
