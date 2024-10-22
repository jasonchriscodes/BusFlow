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
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import okio.buffer
import okio.sink
import java.io.File

@SuppressLint("CustomSplashScreen")
class SplashScreen : AppCompatActivity() {

    private lateinit var client: OkHttpClient
    private lateinit var aid: String
    private lateinit var binding: ActivitySplashScreenBinding
    private lateinit var locationManager: LocationManager
    private lateinit var sharedPrefManager: SharedPrefMananger
    private val REQUEST_MANAGE_EXTERNAL_STORAGE = 1001
    private val REQUEST_WRITE_PERMISSION = 1002
    private var optionDialog: AlertDialog? = null

    var latitude = 0.0
    var longitude = 0.0
    var bearing = 0.0F
    var speed = 0.0F
    var direction = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check and request permission
        checkAndRequestStoragePermission()
//        checkLocationPermission()

        Log.d("version name", "v1.0.59")
        requestStoragePermissions()
        aid = getOrCreateAid()
        Log.d("Android ID", aid)

        client = OkHttpClient()

        // Initialize shared preference manager and location manager
        sharedPrefManager = SharedPrefMananger(this)
        locationManager = LocationManager(this)
//        startLocationUpdate()

        // Start the animation for the splash screen
        val logoExplorer = findViewById<ImageView>(R.id.logoExplorer)
        val logoFullers = findViewById<ImageView>(R.id.logoFullers)
        val animation = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        logoExplorer.startAnimation(animation)
        logoFullers.startAnimation(animation)

        checkForUpdates()

        showOptionDialog()
    }

    /**
     * Ensure permissions are requested before accessing external storage
     */
    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
            }
        } else {
            // For Android 10 and below, request WRITE_EXTERNAL_STORAGE permission
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_PERMISSION
            )
        }
    }

    /**
     * Handle the result of the permission request
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_WRITE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, proceed with file operations
                getOrCreateAid()
            } else {
                // Permission denied
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /**
     * The results of the permission request
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    // Permission granted, proceed with file operations
                    getOrCreateAid()
                    locationManager.checkLocationPermission()
                } else {
                    // Permission denied
                    Toast.makeText(this, "Manage external storage permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Requesting external storage permission
     */
    private fun requestStoragePermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    /** Retrieve the AID from the external folder or generate a new one */
    @SuppressLint("HardwareIds")
    private fun getOrCreateAid(): String {
        val documentsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ".vlrshiddenfolder")

        // Check if the directory exists; if not, try to create it
        if (!documentsDir.exists()) {
            val success = documentsDir.mkdirs()
            if (!success) {
                throw RuntimeException("Failed to create directory: ${documentsDir.absolutePath}")
            }
        }

        val aidFile = File(documentsDir, "aid.txt")

        // Check if the aid.txt file exists, if not create and write a default AID
        if (!aidFile.exists()) {
            val aid = generateNewAid() // Assume this is a function that generates your AID
            aidFile.writeText(aid)
            return aid
        }

        // If the file exists, read the AID from the file
        return aidFile.readText().trim()
    }

    // Example function to generate a new AID if needed
    private fun generateNewAid(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
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

    /** Proceed to LoginActivity and pass the AID */
    private fun proceedToMainActivity() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.putExtra("AID", aid)  // Pass the AID to LoginActivity
        startActivity(intent)
        finish()
    }

    /** Shows a dialog with options for online, offline modes, and device information. */
    private fun showOptionDialog() {
        if (isFinishing) return  // Don't show the dialog if the activity is finishing

        val builder = AlertDialog.Builder(this)
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.mode_selection_dialog, null)
        builder.setView(dialogView)

        val onlineModeButton = dialogView.findViewById<Button>(R.id.onlineModeButton)
        val offlineModeButton = dialogView.findViewById<Button>(R.id.offlineModeButton)
        val whoAmIButton = dialogView.findViewById<Button>(R.id.whoAmIButton)

        onlineModeButton.setOnClickListener {
            optionDialog?.dismiss()  // Dismiss the dialog before transitioning
            proceedToMainActivity()
        }

        offlineModeButton.setOnClickListener {
            val intent = Intent(this, OfflineActivity::class.java)
            startActivity(intent)
            optionDialog?.dismiss()  // Dismiss the dialog before transitioning
        }

        whoAmIButton.setOnClickListener {
            showWhoAmIDialog()
        }

        // Create and show the dialog
        optionDialog = builder.create()
        optionDialog?.show()
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

    /**
     * Shows a dialog prompting the user to download and install the latest APK.
     */
    private fun showUninstallPrompt() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Update Available")
        builder.setMessage("A new version is available. Do you want to download and install the update?")

        builder.setPositiveButton("Download and Install") { dialog, _ ->
            dialog.dismiss()
            downloadAndInstallApk("http://43.226.218.98:5000/api/download-latest-apk")
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        builder.setCancelable(false)
        builder.show()
    }

    /**
     * Downloads the APK from the given URL and triggers the installation process.
     *
     * @param apkUrl The URL to download the APK from.
     */
    private fun downloadAndInstallApk(apkUrl: String) {
        // Request the APK file from the server
        val request = Request.Builder()
            .url(apkUrl)
            .build()

        client.newCall(request).enqueue(object : Callback {
            /**
             * Called when the APK download fails.
             */
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@SplashScreen, "Failed to download APK", Toast.LENGTH_SHORT).show()
                }
            }

            /**
             * Called when the APK download succeeds.
             */
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val apkFile = File(getExternalFilesDir(null), "update.apk")
                    val sink = apkFile.sink().buffer()

                    // Write the APK file to disk
                    response.body?.source()?.let {
                        sink.writeAll(it)
                        sink.close()

                        // Once downloaded, trigger the installation
                        runOnUiThread {
                            installApk(apkFile)
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@SplashScreen, "Error downloading APK", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    /**
     * Installs the APK file by launching an Intent to the package installer.
     *
     * @param apkFile The APK file to install.
     */
    private fun installApk(apkFile: File) {
        val intent = Intent(Intent.ACTION_VIEW)
        val apkUri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.provider",
            apkFile
        )

        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK

        startActivity(intent)
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

    override fun onDestroy() {
        optionDialog?.dismiss() // Dismiss the dialog to prevent leaks
        super.onDestroy()
    }
}
