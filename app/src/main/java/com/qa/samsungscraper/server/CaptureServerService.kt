package com.qa.samsungscraper.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.qa.samsungscraper.R
import com.qa.samsungscraper.activity.MainActivity
import com.qa.samsungscraper.model.CaptureRecord
import com.qa.samsungscraper.model.hostOf
import com.qa.samsungscraper.store.CaptureStore
import com.qa.samsungscraper.util.Prefs
import com.qa.samsungscraper.util.Util

/**
 * Foreground service yang menjalankan [CaptureServer] agar server lokal
 * tetap hidup ketika pengguna berpindah ke Samsung Internet.
 *
 * Alur: user menekan "Mulai Server" di MainActivity -> service start ->
 * server mendengarkan 127.0.0.1:port -> user menjalankan bookmarklet di
 * Samsung Internet -> payload masuk -> record disimpan -> notifikasi.
 */
class CaptureServerService : Service() {

    private var server: CaptureServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val port = prefs.getInt(Prefs.SERVER_PORT, Prefs.DEFAULT_SERVER_PORT)
        val token = Prefs.ensureToken(prefs)

        // Tampilkan notifikasi foreground SEGERA (wajib < 5 detik)
        startAsForeground(port)

        if (server?.isRunning == true) {
            // Sudah berjalan (mis. restart sticky) — cukup perbarui notifikasi
            running = true
            return START_STICKY
        }

        val s = CaptureServer(
            port = port,
            token = token,
            onPayload = { body -> handlePayload(body) },
            onError = { msg ->
                Util.toastMain(this, msg)
                Util.notifyCapture(this, getString(R.string.notif_server_error_title), msg)
                running = false
                stopSelf()
            }
        )
        s.start()
        server = s
        running = s.isRunning
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        running = false
        super.onDestroy()
    }

    /** Bangun record dari payload JSON bookmarklet lalu simpan. */
    private fun handlePayload(body: String) {
        try {
            val rec = CaptureRecord.fromSnapshotJson(
                browserPackage = Util.PKG_SAMSUNG,
                mode = MODE_BOOKMARKLET,
                snapshotJson = body
            )
            if (rec.domHtml.isNullOrBlank() && rec.url.isBlank()) {
                Util.toastMain(this, getString(R.string.bm_payload_invalid))
                return
            }
            CaptureStore.save(rec)

            // Simpan cookie yang terbaca per-host untuk dipakai mode WebView
            val host = hostOf(rec.url)
            if (host != null && rec.cookies.isNotEmpty()) {
                val joined = rec.cookies.joinToString("; ") { "${it.name}=${it.value}" }
                getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                    .edit()
                    .putString(Prefs.autoCookieKey(host), joined)
                    .apply()
            }

            Util.toastMain(this, getString(R.string.toast_saved, rec.title))
            Util.notifyCapture(
                this,
                getString(R.string.notif_capture_title),
                rec.title + " — " + rec.url
            )
        } catch (e: Exception) {
            Util.toastMain(this, getString(R.string.bm_payload_fail, e.message ?: "?"))
        }
    }

    private fun startAsForeground(port: Int) {
        val n = buildNotification(port)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, CaptureServerService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVER)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(getString(R.string.notif_server_title))
            .setContentText(getString(R.string.notif_server_text, port.toString()))
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.notif_stop), stopPi)
            .build()
    }

    private fun ensureChannel(ctx: android.content.Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_SERVER) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_SERVER,
                        getString(R.string.notif_channel_server),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.qa.samsungscraper.server.STOP"
        const val CHANNEL_SERVER = "capture_server"
        const val NOTIF_ID = 4242
        const val MODE_BOOKMARKLET = "bookmarklet"

        /** Status berjalan (per proses). */
        @Volatile
        var running: Boolean = false
            private set

        @JvmStatic
        fun isRunning(): Boolean = running
    }
}
