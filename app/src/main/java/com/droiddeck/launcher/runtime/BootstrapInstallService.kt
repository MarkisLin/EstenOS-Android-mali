package com.droiddeck.launcher.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R

/**
 * Foreground guard for the complete first-run/bootstrap pipeline.
 *
 * The actual work remains owned by SessionActivity and the component installers. This service only
 * keeps the app process at foreground priority and holds a partial wake lock while the screen is
 * off, so downloading the Linux runtime, Mali ICD or Proton does not stop when the user switches
 * apps or locks the phone.
 */
class BootstrapInstallService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${packageName}:bootstrap-download",
        )?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "DroidDeck:bootstrap-wifi",
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {
            wifiLock = null
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wifiLock = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val stage = intent?.getStringExtra(EXTRA_STAGE) ?: "Preparando EstenOS…"
                val percent = intent?.getIntExtra(EXTRA_PERCENT, -1) ?: -1
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, notification(stage, percent))
            }
            else -> {
                val stage = intent?.getStringExtra(EXTRA_STAGE) ?: "Preparando EstenOS…"
                val percent = intent?.getIntExtra(EXTRA_PERCENT, -1) ?: -1
                startForeground(NOTIFICATION_ID, notification(stage, percent))
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Descargas de EstenOS",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantiene las descargas e instalaciones activas con la pantalla apagada"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun notification(stage: String, percent: Int): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle("Preparando EstenOS")
            .setContentText(stage)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bootstrap-downloads"
        private const val NOTIFICATION_ID = 3
        private const val ACTION_START = "com.droiddeck.launcher.bootstrap.START"
        private const val ACTION_UPDATE = "com.droiddeck.launcher.bootstrap.UPDATE"
        private const val ACTION_STOP = "com.droiddeck.launcher.bootstrap.STOP"
        private const val EXTRA_STAGE = "stage"
        private const val EXTRA_PERCENT = "percent"

        fun start(context: Context, stage: String = "Preparando EstenOS…", percent: Int = -1) {
            val intent = Intent(context, BootstrapInstallService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_STAGE, stage)
                .putExtra(EXTRA_PERCENT, percent)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun update(context: Context, stage: String, percent: Int = -1) {
            // Once started as an FGS, normal startService calls can update it while the app is
            // backgrounded without creating another foreground-service launch.
            val intent = Intent(context, BootstrapInstallService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_STAGE, stage)
                .putExtra(EXTRA_PERCENT, percent)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BootstrapInstallService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
