package com.jason.publisher

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var companyNameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var backButton: Button
    private var aid: String? = null  // Variable to hold the received AID

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
    }
}

