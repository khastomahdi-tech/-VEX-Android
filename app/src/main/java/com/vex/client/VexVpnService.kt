package com.vex.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.util.Timer
import java.util.TimerTask

class VexVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.vex.client.START"
        const val ACTION_STOP = "com.vex.client.STOP"
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL_ID = "vex_vpn"
        private const val NOTIF_ID = 1

        @Volatile var stateListener: ((String, String) -> Unit)? = null
        @Volatile var statsListener: ((Long, Long, Long, Long) -> Unit)? = null
    }

    private var tun: ParcelFileDescriptor? = null
    private var engine: CoreEngine? = null
    private var timer: Timer? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "VEX VPN", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn(intent?.getStringExtra(EXTRA_CONFIG).orEmpty())
            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn(config: String) {
        // باید سریع فورگراند شویم، وگرنه اندروید سرویس را می‌کشد
        startForeground(NOTIF_ID, notification("در حال اتصال..."))
        try {
            val coreConfig = ConfigBuilder.build(config)

            val fd = Builder()
                .setSession("VEX CLIENT")
                .setMtu(1500)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName) // ترافیک خود برنامه (به سمت سرور) از تونل رد نمی‌شود
                .establish() ?: throw IllegalStateException("ساخت تونل ناموفق بود")
            tun = fd

            // اگر هسته وصل نباشد، اینجا خطا می‌دهد و تونل بسته می‌شود تا اینترنت گوشی قطع نماند
            val eng = CoreEngine(coreConfig, fd)
            eng.start()
            engine = eng

            startStatsTimer()
            startForeground(NOTIF_ID, notification("متصل است"))
            stateListener?.invoke("connected", "")
        } catch (e: Exception) {
            stopVpn(error = e.message ?: "خطا در اتصال")
        }
    }

    private fun stopVpn(error: String? = null) {
        timer?.cancel(); timer = null
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        tun?.close(); tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (error != null) stateListener?.invoke("error", error)
        else stateListener?.invoke("stopped", "")
    }

    private fun startStatsTimer() {
        timer = Timer().also { t ->
            t.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val s = engine?.stats() ?: return
                    statsListener?.invoke(s[0], s[1], s[2], s[3])
                }
            }, 1000, 1000)
        }
    }

    override fun onRevoke() {
        stopVpn()
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VEX CLIENT")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
}
