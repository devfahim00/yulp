package com.devfahim00.yulp

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps the download engine alive while the app is
 * in the background. The engine auto-resumes persisted downloads when the
 * system recreates this service (START_STICKY).
 */
class DownloadService : Service() {

    override fun onCreate() {
        super.onCreate()
        val engine = DownloadEngine.init(this, autoResume = false)
        engine.attachService(this)
        ServiceCompat.startForeground(
            this, 1001, engine.summaryNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        DownloadEngine.detachService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
