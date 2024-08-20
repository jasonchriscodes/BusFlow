package com.jason.publisher

import MailerSendService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.services.ApiServiceBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Data classes to map the JSON structure
data class EmailRecipient(val email: String, val name: String)
data class EmailData(
    val from: EmailRecipient,
    val to: List<EmailRecipient>,
    val subject: String,
    val text: String
)
data class ResponseData(val messageId: String)

class FeedbackActivity : AppCompatActivity() {

    private lateinit var nameEditText: EditText
    private lateinit var feedbackEditText: EditText
    private lateinit var submitFeedbackButton: Button
    private lateinit var backButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback)

        // Initialize UI components
        nameEditText = findViewById(R.id.nameEditText)
        feedbackEditText = findViewById(R.id.feedbackEditText)
        submitFeedbackButton = findViewById(R.id.submitFeedbackButton)
        backButton = findViewById(R.id.backButton)

        // Initialize the MailerSend service
        val mailerSendService = ApiServiceBuilder.buildEmailService(MailerSendService::class.java)

        submitFeedbackButton.setOnClickListener {
            val name = nameEditText.text.toString()
            val feedback = feedbackEditText.text.toString()

            if (name.isNotEmpty() && feedback.isNotEmpty()) {
                sendFeedback(mailerSendService, name, feedback)
            } else {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            }
        }

        backButton.setOnClickListener {
            finish() // Closes FeedbackActivity and goes back to MainActivity
        }
    }

    private fun sendFeedback(service: MailerSendService, name: String, feedback: String) {
        val emailData = EmailData(
            from = EmailRecipient("admin@thingsboard.io", "Admin"),  // Replace with your sender email
            to = listOf(EmailRecipient("vlrs13542@gmail.com", "Admin")),  // Replace with your recipient email
            subject = "Feedback from $name",
            text = feedback
        )

        CoroutineScope(Dispatchers.IO).launch {
            service.sendEmail(emailData).enqueue(object : Callback<ResponseData> {
                override fun onResponse(call: Call<ResponseData>, response: Response<ResponseData>) {
                    if (response.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(this@FeedbackActivity, "Email sent successfully! ID: ${response.body()?.messageId}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@FeedbackActivity, "Failed to send email. Response code: ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onFailure(call: Call<ResponseData>, t: Throwable) {
                    runOnUiThread {
                        Toast.makeText(this@FeedbackActivity, "Error: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }
}
