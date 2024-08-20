package com.jason.publisher

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class FeedbackActivity : AppCompatActivity() {

    // Declare UI components
    private lateinit var nameEditText: EditText
    private lateinit var feedbackEditText: EditText
    private lateinit var submitFeedbackButton: Button
    private lateinit var backButton: Button

    // Loading Dialog
    private lateinit var loadingDialog: LoadingDialogFragment

    /**
     * Called when the activity is first created.
     * Initializes the UI components and sets up click listeners.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback)

        // Initialize UI components
        nameEditText = findViewById(R.id.nameEditText)
        feedbackEditText = findViewById(R.id.feedbackEditText)
        submitFeedbackButton = findViewById(R.id.submitFeedbackButton)
        backButton = findViewById(R.id.backButton)

        // Initialize Loading Dialog
        loadingDialog = LoadingDialogFragment()

        // Handle send feedback button click
        submitFeedbackButton.setOnClickListener {
            val name = nameEditText.text.toString()
            val feedback = feedbackEditText.text.toString()

            if (name.isNotEmpty() && feedback.isNotEmpty()) {
                showLoading(true)
                sendFeedback(name, feedback)
            } else {
                showCustomToast("Please fill in all fields", false)
            }
        }

        // Handle back button click
        backButton.setOnClickListener {
            finish() // Closes FeedbackActivity and goes back to MainActivity
        }
    }

    /**
     * Sends feedback by email.
     * @param name The name of the user providing feedback.
     * @param feedback The feedback content.
     */
    private fun sendFeedback(name: String, feedback: String) {
        val subject = "Feedback from $name"
        val message = "Hi Admin,\n\nYou received feedback from $name:\n$feedback\n\nThanks."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendEmail(subject, message)
                runOnUiThread {
                    showLoading(false)
                    showCustomToast("Your message has been successfully sent.", true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showLoading(false)
                    showCustomToast("Your message failed to send.", false)
                }
            }
        }
    }

    /**
     * Sends an email using Gmail SMTP.
     * @param subject The subject of the email.
     * @param message The body of the email.
     */
    private fun sendEmail(subject: String, message: String) {
        val username = "systhingsboard@gmail.com"
        val password = BuildConfig.GOOGLE_APP_PASS

        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(username, password)
            }
        })

        val mimeMessage = MimeMessage(session).apply {
            setFrom(InternetAddress(username, "VLRS Thingsboard"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse("systhingsboard@gmail.com"))
            setSubject(subject)
            setText(message)
        }

        Transport.send(mimeMessage)
    }

    /**
     * Displays a custom Toast message at the bottom of the screen.
     * @param message The message to display.
     * @param success Indicates whether the operation was successful (true) or failed (false).
     */
    private fun showCustomToast(message: String, success: Boolean) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_LONG)
        val toastView = toast.view
        val text = toastView?.findViewById<TextView>(android.R.id.message)

        toast.setGravity(Gravity.BOTTOM, 0, 100)

        if (success) {
            text?.setTextColor(Color.GREEN)
        } else {
            text?.setTextColor(Color.RED)
        }

        toast.show()
    }

    /**
     * Shows or hides the loading spinner dialog.
     * @param show Boolean indicating whether to show (true) or hide (false) the loading UI.
     */
    private fun showLoading(show: Boolean) {
        if (show) {
            loadingDialog.showLoading(supportFragmentManager)
        } else {
            loadingDialog.hideLoading()
        }
    }
}
