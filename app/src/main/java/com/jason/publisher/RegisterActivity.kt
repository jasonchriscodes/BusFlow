package com.jason.publisher

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.services.MqttManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class RegisterActivity : AppCompatActivity() {

    private lateinit var accessTokenEditText: EditText
    private lateinit var backButton: Button
    private lateinit var nextButton: Button
    private var companyName: String? = null
    private var password: String? = null
    private var aid: String? = null
    private lateinit var mqttManager: MqttManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Retrieve data passed from the intent
        companyName = intent.getStringExtra("companyName")
        password = intent.getStringExtra("password")
        aid = intent.getStringExtra("AID")

        // Initialize views
        accessTokenEditText = findViewById(R.id.accessTokenEditText)
        backButton = findViewById(R.id.backButton)
        nextButton = findViewById(R.id.nextButton)

        // Initialize mqttManager
        mqttManager = MqttManager(
            serverUri = MapActivity.SERVER_URI,
            clientId = MapActivity.CLIENT_ID,
            username = ""
        )

        // Set OnClickListener for Back Button
        backButton.setOnClickListener {
            finish() // Go back to LoginActivity
        }

        // Set OnClickListener for Next Button
        nextButton.setOnClickListener {
            val enteredAccessToken = accessTokenEditText.text.toString().trim()
            if (enteredAccessToken.isNotBlank()) {
                verifyAccessToken(enteredAccessToken)
            } else {
                Toast.makeText(this, "Please enter the access token of the company.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Verifies the entered access token using fetchSharedAttributes().
     */
    private fun verifyAccessToken(accessToken: String) {
        mqttManager.fetchSharedAttributes(accessToken) { busConfigList ->
            runOnUiThread {
                if (busConfigList.isNotEmpty()) {
                    Toast.makeText(this, "Access token verified. Registering the company...", Toast.LENGTH_LONG).show()
                    // Proceed to upload/register the data on the server
                    uploadCompanyData(companyName!!, password!!, accessToken)
                } else {
                    Toast.makeText(this, "The access token of the device is not available on ThingsBoard.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Uploads the company data to the server and navigates back to LoginActivity on success.
     */
    private fun uploadCompanyData(companyName: String, password: String, accessToken: String) {
        mqttManager.fetchSharedAttributes(accessToken) { busConfigList ->
            if (busConfigList.isNotEmpty()) {
                val dataToUpload = JSONObject().apply {
                    put("companyName", companyName)
                    put("password", password)
                    put("tokenConfigData", accessToken)

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

                val url = "http://43.226.218.98:5000/api/upload-config"

                val requestBody = dataToUpload.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val client = OkHttpClient()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            Toast.makeText(
                                this@RegisterActivity,
                                "Upload failed: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        runOnUiThread {
                            if (response.isSuccessful) {
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "Company registered successfully. Please log in.",
                                    Toast.LENGTH_LONG
                                ).show()
                                // Navigate to SplashScreen instead of LoginActivity
                                val intent = Intent(this@RegisterActivity, SplashScreen::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                startActivity(intent)
                                finish()
                            } else {
                                val errorMessage = response.body?.string()
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "Upload failed: $errorMessage",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                })
            }
        }
    }
}
