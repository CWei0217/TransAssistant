package com.example.myapplication

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.d("MyApplication", "App started")
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("Crash", "Uncaught exception in thread=${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
        ThemeAppearance.applyFromPrefs(this)
    }
}
