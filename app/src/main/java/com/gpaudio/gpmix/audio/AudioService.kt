package com.gpaudio.gpmix.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.gpaudio.gpmix.MainActivity
import com.gpaudio.gpmix.R

/**
 * Foreground service: menjaga mixer tetap hidup saat layar mati / pindah aplikasi, dan (opsional)
 * memegang MediaProjection untuk menangkap audio aplikasi lain.
 */
class AudioService : Service() {
    private var capture: AppCapture? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> if (intent != null) startCapture(intent)
            ACTION_STOP_CAPTURE -> stopCapture()
            ACTION_STOP -> { stopCapture(); stopSelf() }
            else -> enterForeground(withProjection = false)
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AudioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 23) {
            b.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    getString(R.string.stop), stop,
                ).build()
            )
        }
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun enterForeground(withProjection: Boolean) {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = 0
            if (Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (type != 0) startForeground(NOTIF_ID, n, type) else startForeground(NOTIF_ID, n)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun startCapture(intent: Intent) {
        if (Build.VERSION.SDK_INT < 29) { enterForeground(withProjection = false); captureError = "Butuh Android 10+"; return }
        stopCapture()
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) { enterForeground(withProjection = false); captureError = "Izin tangkap audio tidak lengkap"; return }
        try {
            enterForeground(withProjection = true)  // wajib sebelum getMediaProjection
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection: MediaProjection = mpm.getMediaProjection(code, data)
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture() }
            }, Handler(Looper.getMainLooper()))
            val sr = NativeEngine.info().getOrNull(0)?.toInt()?.takeIf { it >= 8000 } ?: 48000
            val cap = AppCapture(projection, sr)
            if (cap.start()) {
                capture = cap
                captureError = null
                captureActive = true
            } else {
                captureError = cap.lastError ?: "Gagal memulai tangkap audio"
                cap.stop()
                enterForeground(withProjection = false)
            }
        } catch (e: Exception) {
            captureError = e.message ?: e.toString()
            captureActive = false
            try { enterForeground(withProjection = false) } catch (_: Exception) { }
        }
    }

    private fun stopCapture() {
        capture?.stop()
        capture = null
        captureActive = false
    }

    override fun onDestroy() {
        stopCapture()
        NativeEngine.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CAPTURE = "com.gpaudio.gpmix.CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.gpaudio.gpmix.STOP_CAPTURE"
        const val ACTION_STOP = "com.gpaudio.gpmix.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "gpmix_audio"
        private const val NOTIF_ID = 1

        @Volatile var captureActive = false
        @Volatile var captureError: String? = null
    }
}
