import android.content.Context
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private var logFile: File? = null
    private var fileObserver: FileObserver? = null

    /**
     * Initialize the logger:
     * - Creates a folder named "Log" inside the public Documents folder.
     * - Creates (or clears) the file "app_logs.txt" in that folder.
     * - Starts a FileObserver that monitors the log file for modifications
     *   and automatically "exports" it (copies it) to the same folder.
     */
    fun init(context: Context) {
        // Get the public Documents folder.
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        // Create the "Log" folder.
        val logDir = File(documentsDir, "Log")
        if (!logDir.exists()) {
            val created = logDir.mkdirs()
            Log.d("FileLogger", "Log folder created: $created, path: ${logDir.absolutePath}")
        }
        // Create the log file inside the "Log" folder.
        logFile = File(logDir, "app_logs.txt")
        // Optionally clear the file on initialization.
        if (logFile!!.exists()) {
            logFile!!.writeText("")
        }
        // Start watching the log file for modifications.
        fileObserver = object : FileObserver(logFile!!.absolutePath, MODIFY) {
            override fun onEvent(event: Int, path: String?) {
                if (event == MODIFY) {
                    // Automatically export (copy) the log file when it is modified.
                    exportLogFile(context, logFile!!)
                }
            }
        }
        fileObserver?.startWatching()
    }

    // Log a debug message.
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        appendLog("DEBUG", tag, message)
    }

    // Log an error message.
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val extra = throwable?.let { "\n${it.localizedMessage}" } ?: ""
        appendLog("ERROR", tag, message + extra)
    }

    // Append a log entry to the file.
    private fun appendLog(level: String, tag: String, message: String) {
        try {
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val logLine = "$timeStamp $level/$tag: $message\n"
            logFile?.appendText(logLine)
        } catch (e: Exception) {
            Log.e("FileLogger", "Error writing log to file: ${e.localizedMessage}")
        }
    }

    /**
     * Exports the log file.
     *
     * In this example, it simply copies the log file to the same "Log" folder.
     * You can change the destination or launch a share intent if desired.
     */
    private fun exportLogFile(context: Context, file: File) {
        try {
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val exportDir = File(documentsDir, "Log")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            val destFile = File(exportDir, file.name)
            file.copyTo(destFile, overwrite = true)
            Log.d("FileLogger", "Log file exported to ${destFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("FileLogger", "Error exporting log file: ${e.localizedMessage}")
        }
    }

    // Retrieve the current log file.
    fun getLogFile(): File? = logFile
}
