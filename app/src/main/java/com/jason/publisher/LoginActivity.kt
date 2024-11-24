package com.jason.publisher

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.services.MqttManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    private lateinit var companyNameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var backButton: Button
    private lateinit var registerButton: Button
    private var tokenConfigData = ""
    private var aid: String? = null  // Variable to hold the received AID
    private val client = OkHttpClient()
    private lateinit var mqttManager: MqttManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Retrieve AID from intent
        aid = intent.getStringExtra("AID")

        // Initialize views
        companyNameEditText = findViewById(R.id.companyNameEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        backButton = findViewById(R.id.backButton)
        registerButton = findViewById(R.id.registerButton)
        val showPasswordCheckbox = findViewById<CheckBox>(R.id.showPasswordCheckbox)
        val passwordEditText = findViewById<EditText>(R.id.passwordEditText)

        // Initialize mqttManager before using it
        mqttManager = MqttManager(
            serverUri = MainActivity.SERVER_URI,
            clientId = MainActivity.CLIENT_ID,
            username = tokenConfigData
        )

        showPasswordCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Show Password
                passwordEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                // Hide Password
                passwordEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            // Move cursor to the end of the text
            passwordEditText.setSelection(passwordEditText.text.length)
        }

        // Set onClickListener for Login button
        loginButton.setOnClickListener {
            val companyName = companyNameEditText.text.toString()
            val password = passwordEditText.text.toString()
            aid = intent.getStringExtra("AID")

            if (companyName.isNotBlank() && password.isNotBlank()) {
                // Disable the login button to prevent multiple clicks
                loginButton.isEnabled = false
                checkLoginCredentials(companyName, password)
            } else {
                Toast.makeText(this, "Please enter both fields", Toast.LENGTH_SHORT).show()
            }
        }

        // Set onClickListener for Back button
        backButton.setOnClickListener {
            val intent = Intent(this, SplashScreen::class.java)
            startActivity(intent)
            finish()
        }

        // Set onClickListener for Register button
        registerButton.setOnClickListener {
            val companyName = companyNameEditText.text.toString()
            val password = passwordEditText.text.toString()
            if (companyName.isNotBlank() && password.isNotBlank()) {
                checkAndRegisterCompany(companyName, password)
            } else {
                Toast.makeText(this, "Please enter both fields", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Checks if the given companyName and password exist on the server for login.
     */
    private fun checkLoginCredentials(companyName: String, password: String) {
        val url = "http://43.226.218.98:5000/api/config-files"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    loginButton.isEnabled = true
                    Log.e("checkLoginCredentials", "Failed to connect to the server: ${e.message}")
                    Toast.makeText(this@LoginActivity, "Failed to connect to the server", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    val configFiles = JSONArray(responseData)
                    Log.d("checkLoginCredentials", "Config Files Response: $responseData")

                    var foundMatch = false
                    var aidFound = false
                    val trimmedAid = aid?.trim()

                    Log.d("checkLoginCredentials", "Trimmed AID: $trimmedAid")

                    for (i in 0 until configFiles.length()) {
                        val configFile = configFiles.getJSONObject(i)
                        val fileCompanyName = configFile.getString("companyName")
                        val filePassword = configFile.getString("password")
                        val busConfigArray = configFile.getJSONArray("busConfig")

                        Log.d("checkLoginCredentials", "Checking companyName: $fileCompanyName, password: $filePassword")

                        if (companyName == fileCompanyName && password == filePassword) {
                            if (!foundMatch) {
                                tokenConfigData = configFile.optString("tokenConfigData", "")
                                Log.d("checkLoginCredentials", "Token Config Data: $tokenConfigData")
                                foundMatch = true
                            }

                            for (j in 0 until busConfigArray.length()) {
                                val busConfigObject = busConfigArray.getJSONObject(j)
                                val currentAid = busConfigObject.getString("aid").trim()

                                Log.d(
                                    "checkLoginCredentials",
                                    "Checking busConfig AID: $currentAid against Trimmed AID: $trimmedAid"
                                )

                                if (currentAid.equals(trimmedAid, ignoreCase = true)) {
                                    Log.d("checkLoginCredentials", "AID match found: $currentAid")
                                    aidFound = true
                                    break
                                }
                            }
                        }

                        if (aidFound) break // Break the outer loop only if AID is found
                    }

                    runOnUiThread {
                        if (!foundMatch) {
                            loginButton.isEnabled = true
                            Log.e("checkLoginCredentials", "No matching companyName and password found.")
                            Toast.makeText(this@LoginActivity, "Invalid credentials. Please try again or register.", Toast.LENGTH_SHORT).show()
                        } else if (!aidFound) {
                            loginButton.isEnabled = true
                            Log.e("checkLoginCredentials", "AID not found in the company's configuration.")
                            Toast.makeText(this@LoginActivity, "AID not found in the company's configuration.", Toast.LENGTH_LONG).show()
                        } else {
                            Log.d("checkLoginCredentials", "Login successful! Redirecting to MainActivity...")
                            val intent = Intent(this@LoginActivity, MainActivity::class.java)
                            intent.putExtra("AID", aid)
                            startActivity(intent)
                            finish()
                        }
                    }
                } else {
                    runOnUiThread {
                        loginButton.isEnabled = true
                        Log.e("checkLoginCredentials", "Server returned unsuccessful response: ${response.code}")
                        Toast.makeText(this@LoginActivity, "Failed to fetch config files", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    /**
     * Checks if the given companyName is already registered on the server.
     * If not, registers the company by uploading the details to the server.
     * Also checks if the AID is already registered under a different company.
     */
    private fun checkAndRegisterCompany(companyName: String, password: String) {
        val url = "http://43.226.218.98:5000/api/config-files"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@LoginActivity, "Failed to connect to the server", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    val configFiles = JSONArray(responseData)

                    var aidFoundInDifferentCompany = false
                    var existingCompanyName: String? = null

                    for (i in 0 until configFiles.length()) {
                        val configFile = configFiles.getJSONObject(i)
                        val fileCompanyName = configFile.getString("companyName")
                        val busConfigArray = configFile.getJSONArray("busConfig")

                        for (j in 0 until busConfigArray.length()) {
                            val busConfigObject = busConfigArray.getJSONObject(j)
                            val registeredAid = busConfigObject.getString("aid")
                            if (registeredAid == aid) {
                                if (fileCompanyName != companyName) {
                                    aidFoundInDifferentCompany = true
                                    existingCompanyName = fileCompanyName
                                }
                                break
                            }
                        }

                        if (aidFoundInDifferentCompany) break
                    }

                    runOnUiThread {
                        if (aidFoundInDifferentCompany) {
                            Toast.makeText(
                                this@LoginActivity,
                                "The AID '$aid' is already registered under the company name '$existingCompanyName'. Please use a different AID.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            // Go to RegisterActivity for a new registration
                            val intent = Intent(this@LoginActivity, RegisterActivity::class.java)
                            intent.putExtra("companyName", companyName)
                            intent.putExtra("password", password)
                            intent.putExtra("AID", aid)
                            startActivity(intent)
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@LoginActivity, "Error: Unable to fetch configuration data. Please check your network connection or contact support.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    /**
     * Prompts the user to enter tokenConfigData and verifies the aid registration on ThingsBoard.
     */
    private fun promptForTokenConfigData(companyName: String, password: String) {
        // Prompt the user to enter the tokenConfigData
        val tokenConfigDataInput = EditText(this)
        tokenConfigDataInput.hint = "Enter Token Config Data"

        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter Token Config Data")
            .setView(tokenConfigDataInput)
            .setPositiveButton("Submit") { _, _ ->
                val enteredTokenConfigData = tokenConfigDataInput.text.toString().trim()
                if (enteredTokenConfigData.isNotBlank()) {
                    // Now verify the entered tokenConfigData
                    verifyTokenConfigData(enteredTokenConfigData, companyName, password)
                } else {
                    Toast.makeText(this, "Token Config Data cannot be empty.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    /**
     * Verifies if the entered tokenConfigData contains the current AID.
     */
    private fun verifyTokenConfigData(enteredTokenConfigData: String, companyName: String, password: String) {
        mqttManager.fetchSharedAttributes(enteredTokenConfigData) { busConfigList ->
            var aidFound = false

            // Check if the aid is registered in the fetched bus config list
            for (busItem in busConfigList) {
                if (busItem.aid == aid) {
                    aidFound = true
                    break
                }
            }

            runOnUiThread {
                if (aidFound) {
                    // Save the tokenConfigData and proceed to register
                    tokenConfigData = enteredTokenConfigData
                    fetchConfigAndUpload(companyName, password)
                } else {
                    Toast.makeText(this, "The entered tokenConfigData does not contain the AID. Please check and try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Checks if the given companyName and password match any file within the config folder on the server.
     */
    private fun checkCompanyNameAndPassword(companyName: String, password: String) {
        val url = "http://43.226.218.98:5000/api/config-files" // Replace with the actual endpoint to list config files

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@LoginActivity, "Failed to connect to the server", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    val configFiles = JSONArray(responseData)

                    var foundMatch = false
                    for (i in 0 until configFiles.length()) {
                        val configFile = configFiles.getJSONObject(i)
                        val fileCompanyName = configFile.getString("companyName")
                        val filePassword = configFile.getString("password")

                        if (companyName == fileCompanyName && password == filePassword) {
                            tokenConfigData = configFile.getString("tokenConfigData")
                            fetchSharedAttributes(tokenConfigData) // Fetch shared attributes
                            foundMatch = true
                            break
                        }
                    }

                    if (!foundMatch) {
                        runOnUiThread {
                            Toast.makeText(this@LoginActivity, "Company not found. Please register.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@LoginActivity, "Failed to fetch config files", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    /**
     * Fetches shared attributes from ThingsBoard using the provided tokenConfigData.
     */
    private fun fetchSharedAttributes(tokenConfigData: String) {
        mqttManager.fetchSharedAttributes(tokenConfigData) { busConfigList ->
            if (busConfigList.isNotEmpty()) {
                // Create a JSON object with companyName, password, tokenConfigData, and busConfig
                val dataToUpload = JSONObject().apply {
                    put("companyName", companyNameEditText.text.toString())
                    put("password", passwordEditText.text.toString())
                    put("tokenConfigData", tokenConfigData)

                    // Convert busConfigList to JSONArray
                    val busConfigArray = JSONArray()
                    for (busItem in busConfigList) {
                        val busConfigObject = JSONObject().apply {
                            put("aid", busItem.aid)
                            put("bus", busItem.bus)
                            put("accessToken", busItem.accessToken)
                        }
                        busConfigArray.put(busConfigObject)
                    }
                    put("busConfig", busConfigArray)
                }

                // Upload the updated configuration data to the OTA server
                uploadConfigFile(dataToUpload)

                // After successful update, start MainActivity and pass the AID
                runOnUiThread {
                    val intent = Intent(this, MainActivity::class.java)
                    intent.putExtra("AID", aid) // Passing the AID to MainActivity
                    startActivity(intent)
                    finish()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Failed to fetch shared attributes", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Fetches the bus configuration data from ThingsBoard using the MqttManager instance
     * and then uploads it to the OTA server.
     *
     * @param companyName The company name for the config file.
     * @param password The password for the config file.
     */
    private fun fetchConfigAndUpload(companyName: String, password: String) {
        // Check if tokenConfigData is correct
        runOnUiThread {
            Toast.makeText(
                this@LoginActivity,
                "tokenConfigData: $tokenConfigData",
                Toast.LENGTH_LONG
            ).show()
        }
        mqttManager.fetchSharedAttributes(tokenConfigData) { busConfigList ->
            // Add this line to debug the busConfigList
            runOnUiThread {
                Toast.makeText(
                    this@LoginActivity,
                    "Fetched busConfigList size: ${busConfigList.size}",
                    Toast.LENGTH_LONG
                ).show()
            }

            if (busConfigList.isNotEmpty()) {
                // Check if the AID is already associated with a different company name
                val existingAid = busConfigList.find { it.aid == aid }
                if (existingAid != null) {
                    runOnUiThread {
                        Toast.makeText(
                            this@LoginActivity,
                            "The AID '$aid' is already registered under a different company name. Please use a unique AID.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@fetchSharedAttributes
                }

                // Create the JSON object with companyName, password, tokenConfigData, and busConfig
                val dataToUpload = JSONObject().apply {
                    put("companyName", companyName)
                    put("password", password)
                    put("tokenConfigData", tokenConfigData)

                    // Convert busConfigList to JSONArray
                    val busConfigArray = JSONArray()
                    for (busItem in busConfigList) {
                        val busConfigObject = JSONObject().apply {
                            put("aid", busItem.aid)
                            put("bus", busItem.bus)
                            put("accessToken", busItem.accessToken)
                        }
                        busConfigArray.put(busConfigObject)
                    }
                    put("busConfig", busConfigArray)
                }

                // Upload the configuration data to the OTA server
                uploadConfigFile(dataToUpload)
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Error: No configuration data found. Please contact support.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Uploads the given configuration data as a JSON file to the OTA server under the "config" folder.
     *
     * @param jsonData The configuration data in JSON format to be uploaded.
     */
    private fun uploadConfigFile(jsonData: JSONObject) {
        // Convert JSONObject to a JSON string
        val jsonString = jsonData.toString()

        // Define the URL for the upload API
        val url =
            "http://43.226.218.98:5000/api/upload-config"  // Make sure this URL matches your Flask server

        // Create the request body using the extension function
        val requestBody = jsonString.toRequestBody("application/json".toMediaTypeOrNull())

        // Create the HTTP POST request
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        // Make the network call
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Handle request failure
                runOnUiThread {
                    Toast.makeText(
                        this@LoginActivity,
                        "Upload failed: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                // Handle the server response
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(
                            this@LoginActivity,
                            "Config uploaded successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val errorMessage = response.body?.string()
                        Toast.makeText(
                            this@LoginActivity,
                            "Upload failed: $errorMessage",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }
}