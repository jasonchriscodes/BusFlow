// App.kt
package com.jason.publisher

import android.app.Application
import com.jason.publisher.main.utils.FileLogger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
    }
}

