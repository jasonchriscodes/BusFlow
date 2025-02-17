package com.jason.publisher

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.jason.publisher.databinding.ActivityTimetableBinding
import com.jason.publisher.model.Bus
import com.jason.publisher.model.BusDataCache
import com.jason.publisher.model.BusItem
import com.jason.publisher.model.BusRoute
import com.jason.publisher.model.BusStop
import com.jason.publisher.model.RouteData
import com.jason.publisher.services.MqttManager
import com.jason.publisher.utils.NetworkStatusHelper
import org.json.JSONObject
import org.osmdroid.config.Configuration
import java.io.File

class TimeTableActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimetableBinding
    private lateinit var mqttManagerConfig: MqttManager
    private lateinit var mqttManager: MqttManager
    private lateinit var connectionStatusTextView: TextView
    private val REQUEST_MANAGE_EXTERNAL_STORAGE = 1001
    private val REQUEST_WRITE_PERMISSION = 1002

    private var latitude = 0.0
    private var longitude = 0.0
    private var aid = ""
    private var jsonString = ""
    private var token = ""
    private var tokenConfigData = "oRSsbeuqDMSckyckcMyE"
    private var config: List<BusItem>? = emptyList()
    private var route: List<BusRoute> = emptyList()
    private var stops: List<BusStop> = emptyList()
    private var busRouteData: List<RouteData> = emptyList()
    private var durationBetweenStops: List<Double> = emptyList()
    private var arrBusData: List<BusItem> = emptyList()

    companion object {
        const val SERVER_URI = "tcp://43.226.218.97:1883"
        const val CLIENT_ID = "jasonAndroidClientId"
        const val PUB_POS_TOPIC = "v1/devices/me/telemetry"
        private const val SUB_MSG_TOPIC = "v1/devices/me/attributes/response/+"
        private const val PUB_MSG_TOPIC = "v1/devices/me/attributes/request/1"
        private const val REQUEST_PERIODIC_TIME = 5000L
        private const val PUBLISH_POSITION_TIME = 5000L
        private const val LAST_MSG_KEY = "lastMessageKey"
        private const val MSG_KEY = "messageKey"
        private const val SOUND_FILE_NAME = "notif.wav"
    }

    @SuppressLint("LongLogTag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimetableBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check and request permission
        requestAllFilesAccessPermission()

        // Fetch AID from the device
        aid = getAndroidId()
        Log.d("TimeTableActivity", "Fetched AID: $aid")

        // Initialize mqttManager before using it
        mqttManagerConfig = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID, username = tokenConfigData)

        // Set up network status UI
        NetworkStatusHelper.setupNetworkStatus(this, binding.connectionStatusTextView, binding.networkStatusIndicator)

        // Initialize MQTT manager
        mqttManager = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID)

        // Load configuration
        Configuration.getInstance().load(this, getSharedPreferences(getString(R.string.app_name), MODE_PRIVATE))

        // Connect and subscribe to MQTT
        connectAndSubscribe()

        // Check internet connection
        if (!NetworkStatusHelper.isNetworkAvailable(this)) {
            // **Offline Mode: Load cached data**
            Toast.makeText(this, "You are disconnected from the internet. Loading data from tablet cache.", Toast.LENGTH_LONG).show()
            loadBusDataFromCache()

            Log.d("MainActivity onCreate NetworkStatusHelper", "Loaded cached config: $config")
            Log.d("MainActivity onCreate NetworkStatusHelper", "Loaded cached busRoute: $route")
            Log.d("MainActivity onCreate NetworkStatusHelper", "Loaded cached busStop: $stops")
            Log.d("MainActivity onCreate NetworkStatusHelper", "Loaded cached busStop: $stops")

            // Load bus route information from offline data
            // Note in getBusRoutesOffline() the route is already contain bus stop in it
            jsonString = OfflineData.getBusRoutesOffline().toString()
            Log.d("MainActivity onCreate NetworkStatusHelper jsonString", jsonString)
            val (route, stops, durationBetweenStops) = RouteData.fromJson(jsonString)

            Log.d("MainActivity onCreate NetworkStatusHelper offline", "Updated busRoute: $route")
            Log.d("MainActivity onCreate  NetworkStatusHelperoffline", "Updated busStop: $stops")
            Log.d("MainActivity onCreate  NetworkStatusHelperoffline", "Updated durationBetweenStops: $durationBetweenStops")
        } else {
            // **Online Mode: Fetch data from ThingsBoard**
            Toast.makeText(this, "Online mode: Receiving data from Thingsboard.", Toast.LENGTH_LONG).show()

            fetchConfig { success ->
                if (success) {
                    getAccessToken()
                    Log.d("MainActivity onCreate Token", token)
                    mqttManager = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID, username = token)
//                    getDefaultConfigValue()
                    requestAdminMessage()
                    connectAndSubscribe()
//                Log.d("MainActivity oncreate fetchConfig config", config.toString())
//                Log.d("MainActivity oncreate fetchConfig busRoute", route.toString())
//                Log.d("MainActivity oncreate fetchConfig busStop", stops.toString())
                } else {
                    Log.e("MainActivity onCreate", "Failed to fetch config, running in offline mode.")
                }
            }
        }

//        busDataCache = getOrCreateAid()
        val file = File("/storage/emulated/0/Documents/.vlrshiddenfolder/busDataCache.txt")
        if (file.exists()) {
            Log.d("MainActivity onCreate", "✅ File exists: ${file.absolutePath}")
        } else {
            Log.e("MainActivity onCreate", "❌ File creation failed!")
        }

        // Set up the "Start Route" button
        binding.startRouteButton.setOnClickListener {
            val intent = Intent(this, MapActivity::class.java).apply {
                putExtra("AID", aid)
                putExtra("CONFIG", ArrayList(config))
                putExtra("JSON_STRING", jsonString)
                putExtra("ROUTE", ArrayList(route)) // Send list as ArrayList
                putExtra("STOPS", ArrayList(stops)) // Send list as ArrayList
                putExtra("DURATION_BETWEEN_BUS_STOP", ArrayList(durationBetweenStops)) // Send list as ArrayList
                putExtra("BUS_ROUTE_DATA", ArrayList(busRouteData))
            }
            startActivity(intent)
        }

        // Set up the "Start Route" button
        binding.testStartRouteButton.setOnClickListener {
            val intent = Intent(this, TestMapActivity::class.java).apply {
                putExtra("AID", aid)
                putExtra("CONFIG", ArrayList(config))
                putExtra("JSON_STRING", jsonString)
                putExtra("ROUTE", ArrayList(route)) // Send list as ArrayList
                putExtra("STOPS", ArrayList(stops)) // Send list as ArrayList
                putExtra("DURATION_BETWEEN_BUS_STOP", ArrayList(durationBetweenStops)) // Send list as ArrayList
                putExtra("BUS_ROUTE_DATA", ArrayList(busRouteData))
            }
            startActivity(intent)
        }
    }

    /**
     * Retrieves the access token for the current device's Android ID from the configuration list.
     */
    @SuppressLint("HardwareIds", "LongLogTag")
    private fun getAccessToken() {
        val listConfig = config
        Log.d("MainActivity getAccessToken config", config.toString())
        for (configItem in listConfig.orEmpty()) {
            if (configItem.aid == aid) {
                token = configItem.accessToken
                break
            }
        }
    }

    /**
     * Requests admin messages periodically.
     */
    private fun requestAdminMessage() {
        val jsonObject = JSONObject()
        jsonObject.put("sharedKeys","message,busRoute,busStop,config,busRouteData")
        val jsonStringSharedKeys = jsonObject.toString()
        val handler = Handler(Looper.getMainLooper())
        mqttManager.publish(PUB_MSG_TOPIC, jsonStringSharedKeys)
//        mqttManagerConfig.publish(PUB_MSG_TOPIC, jsonStringSharedKeys)
        handler.post(object : Runnable {
            override fun run() {
                mqttManager.publish(PUB_MSG_TOPIC, jsonStringSharedKeys)
//                mqttManagerConfig.publish(PUB_MSG_TOPIC, jsonStringSharedKeys)
                handler.postDelayed(this, REQUEST_PERIODIC_TIME)
            }
        })
    }

    /** Fetches the configuration data and initializes the config variable. */
    @SuppressLint("LongLogTag")
    private fun fetchConfig(callback: (Boolean) -> Unit) {
        Log.d("MainActivity fetchConfig", "Fetching config...")

        mqttManagerConfig.fetchSharedAttributes(tokenConfigData) { listConfig ->
            runOnUiThread {
                if (listConfig.isNotEmpty()) {
                    config = listConfig
                    Log.d("MainActivity fetchConfig", "✅ Config received: $config")
                    subscribeSharedData()
                    callback(true)
                } else {
                    Log.e("MainActivity fetchConfig", "❌ Failed to initialize config. Running in offline mode.")
//                    Toast.makeText(this@MainActivity, "Running in offline mode. No bus information available.", Toast.LENGTH_SHORT).show()
                    callback(false)
                }
            }
        }
    }

    /**
     * Loads bus data from the cache file if it exists and extracts route-related data.
     */
    @SuppressLint("LongLogTag")
    private fun loadBusDataFromCache() {
        val cacheFile = File(getHiddenFolder(), "busDataCache.txt")

        if (cacheFile.exists()) {
            try {
                val jsonContent = cacheFile.readText()
                val cachedData = Gson().fromJson(jsonContent, BusDataCache::class.java)

                // Ensure all values are updated correctly
                aid = cachedData.aid
                config = cachedData.config ?: emptyList()
                busRouteData = cachedData.busRouteData ?: emptyList()

                Log.d("MainActivity loadBusDataFromCache", "Loaded cached AID: $aid")
                Log.d("MainActivity loadBusDataFromCache", "Loaded cached Config: $config")
                Log.d("MainActivity loadBusDataFromCache", "Loaded cached BusRouteData: $busRouteData")

                // Extract `route`, `stops`, and `durationBetweenStops` from `busRouteData`
                val extractedData = processBusRouteData(busRouteData)
                route = extractedData.first
                stops = extractedData.second
                durationBetweenStops = extractedData.third

                Log.d("MainActivity loadBusDataFromCache", "Extracted Route: $route")
                Log.d("MainActivity loadBusDataFromCache", "Extracted Stops: $stops")
                Log.d("MainActivity loadBusDataFromCache", "Extracted Duration Between Stops: $durationBetweenStops")

            } catch (e: Exception) {
                Log.e("MainActivity loadBusDataFromCache", "Error reading bus data cache: ${e.message}")
            }
        } else {
            Log.e("MainActivity loadBusDataFromCache", "No cached bus data found.")
        }
    }

    /**
     * Processes `busRouteData` and extracts `route`, `stops`, and `durationBetweenStops`.
     *
     * @param busRouteData List of RouteData received from ThingsBoard.
     * @return Triple containing:
     *   - List<BusRoute>: All coordinates forming the route.
     *   - List<BusStop>: All stops along the route.
     *   - List<Double>: Durations between stops.
     */
    private fun processBusRouteData(busRouteData: List<RouteData>): Triple<List<BusRoute>, List<BusStop>, List<Double>> {
        val newRoute = mutableListOf<BusRoute>()
        val newStops = mutableListOf<BusStop>()
        val newDurations = mutableListOf<Double>()

        for (routeData in busRouteData) {
            // Add starting point as the first stop
            newStops.add(BusStop(latitude = routeData.startingPoint.latitude, longitude = routeData.startingPoint.longitude))

            for (nextPoint in routeData.nextPoints) {
                // Add stops
                newStops.add(BusStop(latitude = nextPoint.latitude, longitude = nextPoint.longitude))

                // Add route coordinates
                for (coord in nextPoint.routeCoordinates) {
                    newRoute.add(BusRoute(latitude = coord[1], longitude = coord[0]))
                }

                // Extract duration
                val durationValue = nextPoint.duration.split(" ")[0].toDoubleOrNull() ?: 0.0
                newDurations.add(durationValue)
            }
        }

        return Triple(newRoute, newStops, newDurations)
    }

    /**
     * Gets the hidden folder directory where cached bus data is stored.
     * Creates the folder if it does not exist.
     *
     * @return File representing the hidden directory.
     */
    private fun getHiddenFolder(): File {
        val externalDocumentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val hiddenFolder = File(externalDocumentsDir, ".vlrshiddenfolder")

        if (!hiddenFolder.exists()) {
            hiddenFolder.mkdirs()
        }

        return hiddenFolder
    }

    /** Connects to the MQTT broker and subscribes to the required topics. */
    @SuppressLint("LongLogTag")
    private fun connectAndSubscribe() {
        mqttManager.connect { isConnected ->
            if (isConnected) {
                Log.d("MainActivity connectAndSubscribe", "✅ Connected to MQTT broker successfully.")
                subscribeSharedData()
//                Log.d("MainActivity connectAndSubscribe config", config.toString())
//                Log.d("MainActivity connectAndSubscribe busRoute", route.toString())
//                Log.d("MainActivity connectAndSubscribe busStop", stops.toString())
            } else {
                Log.e("MainActivity connectAndSubscribe", "❌ Failed to connect to MQTT broker. Running in offline mode.")
                runOnUiThread {
                    Toast.makeText(this@TimeTableActivity, "Running in offline mode. No connection to server.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Subscribes to shared data from the server.
     * Validates configuration, AID matching, and updates the route, stops, and durationBetweenStops.
     */
    @SuppressLint("LongLogTag")
    private fun subscribeSharedData() {
        mqttManager.subscribe(SUB_MSG_TOPIC) { message ->
            runOnUiThread {
                try {
                    // Parse incoming message
                    val gson = Gson()
                    val data = gson.fromJson(message, Bus::class.java)

                    // Update configuration
                    config = data.shared?.config?.busConfig
                    arrBusData = config.orEmpty()

                    Log.d("MainActivity subscribeSharedData", "Config: $config")

                    if (config.isNullOrEmpty()) {
//                        clearBusData()
                        return@runOnUiThread
                    }

                    // Check if AID matches any entry in config
                    val matchingAid = config!!.any { it.aid == aid }
                    if (!matchingAid) {
                        Toast.makeText(this, "AID does not match.", Toast.LENGTH_SHORT).show()
//                        clearBusData()
                        return@runOnUiThread
                    }

                    // Retrieve `busRouteData` from ThingsBoard
                    busRouteData = data.shared?.busRouteData1 ?: emptyList()
                    Log.d("MainActivity subscribeSharedData", "busRouteData: $busRouteData")

                    // **Rewrite cache when online**
                    if (NetworkStatusHelper.isNetworkAvailable(this@TimeTableActivity)) {
                        // Save data **only after all values are updated**
                        if (config!!.isNotEmpty() && route.isNotEmpty() && stops.isNotEmpty()) {
                            saveBusDataToCache()
                        }
                    }

                    // Extract `route`, `stops`, and `durationBetweenStops` from `busRouteData`
                    val extractedData = processBusRouteData(busRouteData)
                    route = extractedData.first
                    stops = extractedData.second
                    durationBetweenStops = extractedData.third

                    Log.d("MainActivity subscribeSharedData", "Extracted Route: $route")
                    Log.d("MainActivity subscribeSharedData", "Extracted Stops: $stops")
                    Log.d("MainActivity subscribeSharedData", "Extracted Duration Between Stops: $durationBetweenStops")

                    if (route.isNotEmpty()) {
                        // Convert route to BusStopInfo and update ProximityManager
//                        val busStopInfoList = convertRouteToBusStopInfo(route)
//                        BusStopProximityManager.setBusStopList(busStopInfoList)

                        // Generate polyline and markers on first-time initialization
//                        if (firstTime) {
//                            firstTime = false
//                        }
                    } else {
                        Log.d("MainActivity subscribeSharedData", "No route data available.")
//                        clearBusData()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity subscribeSharedData", "Error processing shared data: ${e.message}", e)
                    Toast.makeText(this, "Error processing shared data.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Saves the latest bus data to the cache file.
     */
    private var isCacheUpdated = false // Flag to ensure cache is updated only once
    @SuppressLint("LongLogTag")
    private fun saveBusDataToCache() {
        if (!NetworkStatusHelper.isNetworkAvailable(this)) {
            Log.d("MainActivity saveBusDataToCache", "❌ No internet connection. Skipping cache update.")
            return
        }
        // ✅ Check if cache has already been updated
        if (isCacheUpdated) {
            Log.d("MainActivity saveBusDataToCache", "🚫 Skipping cache update (already updated).")
            return
        }
        val cacheFile = File(getHiddenFolder(), "busDataCache.txt") // Change extension to .txt
//        Log.d("MainActivity saveBusDataToCache aid", aid.toString())
//        Log.d("MainActivity saveBusDataToCache config", config.toString())
//        Log.d("MainActivity saveBusDataToCache busRoute", routeCache.toString())
//        Log.d("MainActivity saveBusDataToCache busStop", stopsCache.toString())

        try {
            val busData = mapOf(
                "aid" to aid,
                "busRouteData" to busRouteData,
                "config" to config
            )

            val jsonStringBusData = Gson().toJson(busData) // Convert data to JSON
            cacheFile.writeText(jsonStringBusData) // Overwrite cache file
            Log.d("MainActivity saveBusDataToCache", "✅ Bus data cache updated successfully in busDataCache.txt.")
            Toast.makeText(this, "Bus data cache updated successfully in busDataCache.txt.", Toast.LENGTH_SHORT).show()
            // ✅ Set flag to true after successful update
            isCacheUpdated = true
        } catch (e: Exception) {
            Log.e("MainActivity saveBusDataToCache", "❌ Error saving bus data cache: ${e.message}")
        }
    }


    /**
     * All Files Access Permission (MANAGE_EXTERNAL_STORAGE).
     */
    @SuppressLint("LongLogTag")
    private fun requestAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
                } catch (e: Exception) {
                    Log.e("TimeTableActivity requestAllFilesAccessPermission", "Error requesting storage permission: ${e.message}")
                }
            }
        }
    }

    /**
     * onActivityResult to handle permission grant
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.d("MainActivity", "All files access granted.")
                } else {
                    Log.e("MainActivity", "User denied all files access.")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NetworkStatusHelper.unregisterReceiver(this)
    }

    /** Fetches the Android ID (AID) of the device. */
    private fun getAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }
}
