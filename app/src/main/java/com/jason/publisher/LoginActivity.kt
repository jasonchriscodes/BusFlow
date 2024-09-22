package com.jason.publisher

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
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
    private var tokenConfigData = "oRSsbeuqDMSckyckcMyE"
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

            if (companyName.isNotBlank() && password.isNotBlank()) {
                // Pass the AID to MainActivity
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("AID", aid)
                startActivity(intent)
                finish()
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
                fetchConfigAndUpload(companyName, password)
            } else {
                Toast.makeText(this, "Please enter both fields", Toast.LENGTH_SHORT).show()
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
        mqttManager.fetchSharedAttributes(tokenConfigData) { busConfigList ->
            if (busConfigList.isNotEmpty()) {
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
                    Toast.makeText(this, "Failed to fetch config data", Toast.LENGTH_SHORT).show()
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