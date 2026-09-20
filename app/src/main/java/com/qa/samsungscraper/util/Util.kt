package com.qa.samsungscraper.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qa.samsungscraper.R
import com.qa.samsungscraper.activity.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Konstanta & helper umum aplikasi. */
object Prefs {
    const val NAME = "smsc_prefs"
    const val VIEW_SOURCE = "autoViewSource"
    const val CDP_RELOAD = "cdpReload"
    const val CDP_SCREENSHOT = "cdpScreenshot"
    const val PORT = "devtoolsPort"
    const val COOKIES_MANUAL = "manualCookies"
    const val BUBBLE_X = "bubbleX"
    const val BUBBLE_Y = "bubbleY"
    const val AUTO_COOKIE_PREFIX = "autock_"

    /** Kunci cookie otomatis per-host. */
    fun autoCookieKey(host: String) = AUTO_COOKIE_PREFIX + host
}

object Util {
    const val CHANNEL_CAPTURE = "capture_done"
    const val PKG_SAMSUNG = "com.sec.android.app.sbrowser"
    const val PKG_SAMSUNG_LITE = "com.sec.android.app.sbrowser.lite"

    fun isSamsungInstalled(ctx: Context): Boolean {
        val pm = ctx.packageManager
        return try {
            pm.getPackageInfo(PKG_SAMSUNG, 0)
            true
        } catch (e: Exception) {
            try {
                pm.getPackageInfo(PKG_SAMSUNG_LITE, 0)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    fun toastMain(ctx: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { toast(ctx, msg) }
    }

    fun fmtTime(ms: Long): String {
        if (ms <= 0) return "-"
        val fmt = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault())
        return fmt.format(Date(ms))
    }

    fun copyClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("capture", text))
    }

    fun hasNotificationPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CAPTURE, "Hasil Capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun notifyCapture(ctx: Context, title: String, text: String) {
        if (!hasNotificationPermission(ctx)) return
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(ctx, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(title.take(64))
            .setContentText(text.take(120))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
        } catch (e: Exception) {
            // notifikasi bersifat opsional
        }
    }

    /** Zip seluruh isi folder [srcDir] ke [outFile]. */
    fun zipDir(srcDir: File, outFile: File) {
        outFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            val files = srcDir.walkTopDown().filter { it.isFile }.toList()
            for (f in files) {
                val rel = f.relativeTo(srcDir).path.replace(File.separatorChar, '/')
                zos.putNextEntry(ZipEntry("capture/$rel"))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            if (files.isEmpty()) {
                zos.putNextEntry(ZipEntry("capture/EMPTY"))
                zos.closeEntry()
            }
        }
    }

    /** Ekspor ZIP ke cache dan kembalikan uri FileProvider untuk dibagikan. */
    fun shareZip(ctx: Context, captureDir: File, captureId: String) {
        val out = File(ctx.cacheDir, "exports/export_$captureId.zip")
        zipDir(captureDir, out)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", out
        )
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Capture $captureId")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Bagikan ZIP"))
    }
}
