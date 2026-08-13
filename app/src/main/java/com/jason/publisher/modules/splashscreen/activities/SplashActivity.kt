package com.jason.publisher.modules.splashscreen.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.jason.publisher.R
import com.jason.publisher.main.services.background.ClientAttributesService
import com.jason.publisher.main.services.background.ScreenRecordService
import com.jason.publisher.main.loggers.FetchSessionStore
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.TripLog
import com.jason.publisher.main.loggers.UserActionLogger
import com.jason.publisher.modules.battery.ui.hookBatteryToasts
import com.jason.publisher.modules.schedule.activities.ScheduleActivity
import com.jason.publisher.modules.schedule.helpers.OtaUpdateManager
import kotlinx.coroutines.launch
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.GifImageView

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    private lateinit var mpm: MediaProjectionManager
    private var onboardingRunning = false

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        // apply transparent splash theme
        setTheme(R.style.Theme_NavTrack_Splash)
        super.onCreate(savedInstanceState)

        // Initialize BEFORE any FileLogger.d/i/w/e
        FileLogger.init(applicationContext)
        FileLogger.d("SplashActivity", "onCreate")

        FileLogger.d("SplashActivity", "onCreate")
        hookBatteryToasts()

        lifecycleScope.launch {
            val tbHost = "https://thingsboard.cloud" // or from your config
            val deviceToken = "YOUR_DEVICE_TOKEN"    // from config / stored
            val started = OtaUpdateManager(this@SplashActivity, tbHost, deviceToken)
                .checkDownloadAndPromptInstall()

            if (started) {
                FileLogger.d("SplashActivity", "OTA install flow started")
                // Optionally return early or show “Updating…” UI
            }
        }

        if (savedInstanceState == null) { // cold start, not a rotation
            getSharedPreferences("panel_debug_pref", MODE_PRIVATE)
                .edit {
                    putInt("panel_debug_no", 0)
                }
        }

        // ✅ OPTIMIZED: Screen recording permission tetap ditampilkan seperti awal
        // Tapi dengan optimasi di ScreenRecordService agar tidak lag
        // Optimasi: audio disabled by default, lower bitrate, reduced frame rate, dll
        mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            val captureIntent = mpm.createScreenCaptureIntent()
            projectionLauncher.launch(captureIntent)
            Log.d("SplashActivity", "✅ Requesting screen recording permission (optimized for performance)")
            FileLogger.d("SplashActivity", "Screen recording permission requested - service is optimized to prevent lag")
        } catch (e: SecurityException) {
            // Handle security exception (e.g., if permission is denied)
            FileLogger.e("SplashActivity", "Security exception requesting screen capture | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
            Log.e("SplashActivity", "Security exception requesting screen capture", e)
        } catch (e: Exception) {
            // If permission request fails for any other reason, log but don't crash
            FileLogger.e("SplashActivity", "Failed to request screen capture permission | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
            Log.e("SplashActivity", "Failed to request screen capture permission", e)
        }

        setContentView(R.layout.activity_splash)

        // Start the ClientAttributesService to handle attributes-updating when the app is closed
        startService(Intent(baseContext, ClientAttributesService::class.java))

        // full-screen immersive mode
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )

        val gifView     = findViewById<GifImageView>(R.id.openerGif)
        val btnFetch    = findViewById<Button>(R.id.btnFetchRoster)
        val btnUseCache = findViewById<Button>(R.id.btnUseCache)

// play GIF only once
        val drawable = gifView.drawable as GifDrawable
        drawable.loopCount = 1

        drawable.addAnimationListener {
            startOnboardingOnceThenShowButtons()
        }

        btnFetch.setOnClickListener {
            UserActionLogger.click("SplashActivity", "btnFetchRoster")
            FileLogger.startNewSession()
            // Not FetchSessionStore.markFetched() here - ScheduleActivity marks it itself right
            // when it actually consumes EXTRA_FETCH_ROSTER=true. Marking it this early would
            // make ScheduleActivity think a fetch already happened on its own very first,
            // legitimate read of this same intent.
            startScheduleActivity(fetch = true)
        }
        btnUseCache.setOnClickListener {
            FileLogger.resumeSession(this)
            UserActionLogger.click("SplashActivity", "btnUseCache")
            startScheduleActivity(fetch = false)
        }
    }

    /**
     * Normally lets the driver choose Fetch Roster vs Use Cache. But skip straight to the same
     * place "Use Cache" goes - without asking - if either:
     *  - [FetchSessionStore] shows "Fetch Roster" was already chosen earlier this same
     *    continuous app-open (a crash mid-trip cold-starts straight back through this screen;
     *    without this check that restart would silently re-fetch and reset the schedule the
     *    driver never asked to reset), or
     *  - [TripLog] still shows an active trip (belt-and-suspenders: covers a restart landing
     *    here before the fetch flag above was even set).
     * Both are cleared only once the app is judged fully closed (see App.kt), so a genuine
     * close+reopen still shows the normal choice.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun showChoiceButtons() {
        if (FetchSessionStore.hasFetchedThisOpen(this) || TripLog.hasActive(this)) {
            FileLogger.resumeSession(this)
            UserActionLogger.shown("SplashActivity", "Auto-resumed interrupted session", "skipped Fetch Roster/Use Cache choice")
            startScheduleActivity(fetch = false)
            return
        }

        val btnFetch = findViewById<Button>(R.id.btnFetchRoster)
        val btnUseCache = findViewById<Button>(R.id.btnUseCache)

        btnFetch.visibility = View.VISIBLE
        btnUseCache.visibility = View.VISIBLE

        btnFetch.bringToFront()
        btnUseCache.bringToFront()
    }


    @RequiresApi(Build.VERSION_CODES.M)
    private fun continueOnboarding() {
        // 1) Runtime permissions (ONE dialog for both)
        val permsToAsk = mutableListOf<String>()

        // Location (runtime)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permsToAsk.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Notifications (runtime, Android 13+)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permsToAsk.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permsToAsk.isNotEmpty()) {
            runtimePermsLauncher.launch(permsToAsk.toTypedArray())
            return
        }

        // 2) Install unknown apps (Settings, Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = packageManager.canRequestPackageInstalls()
            if (!canInstall) {
                UserActionLogger.shown("SplashActivity", "dialog: Enable app updates")
                AlertDialog.Builder(this)
                    .setTitle("Enable app updates")
                    .setMessage(
                        "To install BusFlow updates, please enable “Install unknown apps” for this app.\n\n" +
                                "You only need to do this once."
                    )
                    .setCancelable(false)
                    .setPositiveButton("Open Settings") { _, _ ->
                        UserActionLogger.click("SplashActivity", "dialog_enableAppUpdates_openSettings")
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        unknownAppsLauncher.launch(intent)
                    }
                    .setNegativeButton("Not now") { _, _ ->
                        // allow app to continue; OTA will simply not auto-install
                        UserActionLogger.click("SplashActivity", "dialog_enableAppUpdates_notNow")
                        showChoiceButtons()
                    }
                    .show()
                return
            }
        }

        // 3) All files access (Settings, Android 11+)
        // Only keep this if you truly need /Documents or broad storage access.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                UserActionLogger.shown("SplashActivity", "dialog: Storage access")
                AlertDialog.Builder(this)
                    .setTitle("Storage access")
                    .setMessage(
                        "To read/write route files in Documents, please allow “All files access”.\n\n" +
                                "You only need to do this once."
                    )
                    .setCancelable(false)
                    .setPositiveButton("Open Settings") { _, _ ->
                        UserActionLogger.click("SplashActivity", "dialog_storageAccess_openSettings")
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        allFilesLauncher.launch(intent)
                    }
                    .setNegativeButton("Not now") { _, _ ->
                        // app can still run, but features needing Documents may fail
                        UserActionLogger.click("SplashActivity", "dialog_storageAccess_notNow")
                        showChoiceButtons()
                    }
                    .show()
                return
            }
        }

        // DONE
        showChoiceButtons()
    }

    // call this after GIF finishes
    private fun startOnboardingOnceThenShowButtons() {
        if (onboardingRunning) return
        onboardingRunning = true
        continueOnboarding()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            FileLogger.d("SplashActivity", "allFilesLauncher: returned")
            continueOnboarding()
        }

    private val runtimePermsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // result[Manifest.permission.ACCESS_FINE_LOCATION] == true/false
            // result[Manifest.permission.POST_NOTIFICATIONS] == true/false
            FileLogger.d("SplashActivity", "runtimePermsLauncher: $result")
            continueOnboarding()
        }

    private val unknownAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            FileLogger.d("SplashActivity", "unknownAppsLauncher: returned")
            continueOnboarding()
        }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureUnknownAppsInstallPermission(): Boolean {
        val canInstall = packageManager.canRequestPackageInstalls()
        FileLogger.d("SplashActivity", "ensureUnknownAppsInstallPermission: canInstall=$canInstall")
        if (canInstall) return true

        UserActionLogger.shown("SplashActivity", "dialog: Enable app updates (ensureUnknownAppsInstallPermission)")
        AlertDialog.Builder(this)
            .setTitle("Enable app updates")
            .setMessage(
                "To install BusFlow updates, please enable “Install unknown apps” for this app.\n\n" +
                        "You only need to do this once."
            )
            .setCancelable(false)
            .setPositiveButton("Open Settings") { _, _ ->
                UserActionLogger.click("SplashActivity", "dialog_enableAppUpdates2_openSettings")
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                unknownAppsLauncher.launch(intent)
            }
            .setNegativeButton("Not now") { _, _ ->
                // Continue anyway, but OTA might need to redirect later
                UserActionLogger.click("SplashActivity", "dialog_enableAppUpdates2_notNow")
                showChoiceButtons()
            }
            .show()

        return false
    }

    /**
     * Launch ScheduleActivity with the user’s choice.
     *
     * @param fetch true to fetch fresh roster data, false to use cached data
     */
    private fun startScheduleActivity(fetch: Boolean) {
        Intent(this, ScheduleActivity::class.java).also {
            it.putExtra("EXTRA_FETCH_ROSTER", fetch)
            startActivity(it)
            finish()
        }
    }

    // Screen-capture consent result
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            // Pass the audio decision to the service
            ScreenRecordService.Companion.start(
                this,
                res.resultCode,
                res.data!!,
                withAudio = false // Default to false to reduce CPU/GPU load during screen recording
            )
        }
    }
}