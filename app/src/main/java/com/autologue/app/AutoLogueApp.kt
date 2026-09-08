package com.autologue.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class AutoLogueApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    private var customImageLoader: ImageLoader? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader {
        val loader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    // 기기 사용 가능 메모리의 15%로 제한 (기본 25% 대비 대폭 절감하여 OOM 원천 방지)
                    .maxSizePercent(0.15)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024) // 50MB
                    .build()
            }
            // RGB_565를 선호하여 ARGB_8888 대비 메모리 사용량 50% 절감
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowRgb565(true)
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()

        customImageLoader = loader
        return loader
    }

    override fun onCreate() {
        super.onCreate()
        setupGlobalCrashHandler()
    }

    /**
     * 안드로이드 OS의 메모리 압박 시그널 수신 시
     * 인메모리 이미지 캐시를 즉시 비워 Low Memory Killer(LMK) 강제 종료를 방어합니다.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                customImageLoader?.memoryCache?.clear()
                System.gc()
            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
                customImageLoader?.memoryCache?.trimMemory(level)
            }
        } catch (e: Throwable) {
            Log.e("AutoLogueApp", "onTrimMemory 처리 중 예외", e)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            customImageLoader?.memoryCache?.clear()
            System.gc()
        } catch (e: Throwable) {
            Log.e("AutoLogueApp", "onLowMemory 처리 중 예외", e)
        }
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
                val logText = "=== CRASH AT $timeStamp ===\nThread: ${thread.name}\nException: ${throwable.javaClass.simpleName} - ${throwable.localizedMessage}\n$stackTrace\n\n"

                val logFile = File(filesDir, "crash_log.txt")
                logFile.appendText(logText)

                // 크래시 플래그 저장 (다음 앱 기동 시 UI 다이얼로그 즉시 팝업용)
                val prefs = getSharedPreferences("app_crash_state", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("has_unhandled_crash", true).apply()

                Log.e("AutoLogueCrash", logText)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}

