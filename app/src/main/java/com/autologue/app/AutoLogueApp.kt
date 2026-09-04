package com.autologue.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class AutoLogueApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        setupGlobalCrashHandler()
    }

    /**
     * PC와 연결되지 않은 독립 스마트폰에서도 예기치 못한 에러 발생 시
     * crash_log.txt 파일에 스택 트레이스를 영구 기록하여 추적 가능하도록 함.
     */
    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()

                val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
                val logText = "=== CRASH AT $timeStamp ===\nThread: ${thread.name}\nException: ${throwable.localizedMessage}\n$stackTrace\n\n"

                val logFile = File(filesDir, "crash_log.txt")
                logFile.appendText(logText)
                Log.e("AutoLogueCrash", logText)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
