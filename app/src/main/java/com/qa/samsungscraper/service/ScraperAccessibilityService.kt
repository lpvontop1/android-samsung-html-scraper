package com.qa.samsungscraper.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.qa.samsungscraper.R
import com.qa.samsungscraper.model.A11yElement
import com.qa.samsungscraper.model.A11ySnapshot
import com.qa.samsungscraper.model.CaptureRecord
import com.qa.samsungscraper.model.hostOf
import com.qa.samsungscraper.store.CaptureStore
import com.qa.samsungscraper.util.Prefs
import com.qa.samsungscraper.util.Util
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Layanan aksesibilitas yang:
 * 1. Menampilkan bubble melayang ketika Samsung Internet berada di depan.
 * 2. Saat bubble ditekan: membaca URL address bar + snapshot teks/struktur halaman (mode a11y),
 *    lalu (opsional) membuka view-source:<url> untuk membaca HTML sumber lengkap
 *    (dengan sesi/cookies browser, karena request dilakukan oleh browser sendiri),
 *    kemudian kembali ke halaman.
 */
class ScraperAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScraperA11y"
        val SAMSUNG_PACKAGES = setOf(
            Util.PKG_SAMSUNG,
            Util.PKG_SAMSUNG_LITE
        )
        private const val MAX_URL_NODES = 1200
        private const val MAX_SNAP_NODES = 2500
        private const val SNAP_BUDGET_MS = 2500L
        private const val MAX_TEXT_PER_NODE = 200

        fun isServiceEnabled(ctx: android.content.Context): Boolean {
            val s = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val comp = "${ctx.packageName}/com.qa.samsungscraper.service.ScraperAccessibilityService"
            return s.split(':').any { it.equals(comp, ignoreCase = true) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bubble: View? = null
    private var wm: WindowManager? = null
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }

    @Volatile
    private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Config layanan diambil dari XML (a11y_service_config.xml):
        // canRetrieveWindowContent=true, typeWindowStateChanged, dll.
        // JANGAN menimpa serviceInfo di sini — default programatik akan
        // menonaktifkan canRetrieveWindowContent dan merusak capture.
        if (isSamsungForeground()) showBubble()
    }

    override fun onDestroy() {
        hideBubble()
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (pkg in SAMSUNG_PACKAGES) showBubble() else hideBubble()
    }

    private fun isSamsungForeground(): Boolean =
        rootInActiveWindow?.packageName?.toString() in SAMSUNG_PACKAGES

    // ---------------- Bubble melayang ----------------

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return
        if (bubble != null) return
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val v = LayoutInflater.from(this).inflate(R.layout.view_bubble, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = prefs.getInt(Prefs.BUBBLE_X, 32)
            params.y = prefs.getInt(Prefs.BUBBLE_Y, 320)

            val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false

            v.setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY
                        startX = params.x; startY = params.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (moved || abs(dx) > slop || abs(dy) > slop) {
                            moved = true
                            params.x = (startX + dx).toInt().coerceAtLeast(0)
                            params.y = (startY + dy).toInt().coerceAtLeast(0)
                            try { wm?.updateViewLayout(view, params) } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            prefs.edit()
                                .putInt(Prefs.BUBBLE_X, params.x)
                                .putInt(Prefs.BUBBLE_Y, params.y)
                                .apply()
                        } else {
                            triggerCapture()
                        }
                        view.performClick()
                        true
                    }
                    else -> true
                }
            }

            wm?.addView(v, params)
            bubble = v
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menampilkan bubble", e)
        }
    }

    private fun hideBubble() {
        val v = bubble ?: return
        bubble = null
        try { wm?.removeView(v) } catch (_: Exception) {}
    }

    // ---------------- Alur capture ----------------

    fun triggerCapture() {
        if (busy) {
            Util.toastMain(this, getString(R.string.toast_busy))
            return
        }
        if (!isSamsungForeground()) {
            Util.toastMain(this, getString(R.string.toast_not_samsung))
            return
        }
        busy = true
        Util.toastMain(this, getString(R.string.toast_capturing))
        scope.launch {
            try {
                doCapture()
            } catch (e: Exception) {
                Log.e(TAG, "capture gagal", e)
                Util.toastMain(this@ScraperAccessibilityService, getString(R.string.toast_fail, e.message ?: "?"))
            } finally {
                busy = false
            }
        }
    }

    private suspend fun doCapture() {
        val root = withContext(Dispatchers.Main) { rootInActiveWindow }
            ?: throw IllegalStateException("Tidak dapat membaca jendela aktif")
        val pkg = root.packageName?.toString().orEmpty()
        val url = findAddressBarUrl(root)
        val snapshot = buildA11ySnapshot(root)

        val rec = CaptureRecord.newRecord(
            url = url ?: "",
            title = "",
            browserPackage = pkg,
            mode = "a11y"
        )
        rec.a11y = snapshot

        var urlNotice = false
        if (url != null) {
            if (prefs.getBoolean(Prefs.VIEW_SOURCE, true)) {
                val src = viewSourceCapture(url, pkg)
                if (!src.isNullOrBlank()) {
                    rec.sourceHtml = src
                    rec.modes.add("view-source")
                    CaptureRecord.extractTitleFromHtml(src)?.let { rec.title = it }
                }
            }
        } else {
            urlNotice = true
        }

        if (rec.title.isBlank()) rec.title = hostOf(rec.url) ?: "(tanpa judul)"

        withContext(Dispatchers.IO) { CaptureStore.save(rec) }
        Util.toastMain(
            this,
            getString(R.string.toast_saved, rec.title) +
                if (urlNotice) " | " + getString(R.string.toast_no_url) else ""
        )
        Util.notifyCapture(this, rec.title, rec.url.ifBlank { "-" })
    }

    /**
     * Buka view-source:<url> di Samsung Internet, baca seluruh teks sumber
     * lewat pohon aksesibilitas, lalu kembali (BACK) ke halaman sebelumnya.
     */
    private suspend fun viewSourceCapture(url: String, pkg: String): String? {
        var navigated = false
        try {
            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("view-source:$url"))
                        .setPackage(pkg.ifBlank { Util.PKG_SAMSUNG })
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    navigated = true
                } catch (e: Exception) {
                    Log.w(TAG, "Tidak bisa membuka view-source", e)
                }
            }
            if (!navigated) return null

            val deadline = System.currentTimeMillis() + 10_000
            var text = ""
            while (System.currentTimeMillis() < deadline) {
                delay(350)
                val r = withContext(Dispatchers.Main) { rootInActiveWindow } ?: continue
                val t = collectAllText(r, 1200)
                if (looksLikeHtmlSource(t)) {
                    delay(600) // tunggu stabilisasi render teks sumber
                    val r2 = withContext(Dispatchers.Main) { rootInActiveWindow }
                    val t2 = if (r2 != null) collectAllText(r2, 1200) else t
                    text = if (t2.length > t.length) t2 else t
                    break
                }
            }
            return if (looksLikeHtmlSource(text)) text.take(CaptureRecord.MAX_SOURCE_CHARS) else null
        } catch (e: Exception) {
            Log.w(TAG, "viewSource gagal", e)
            return null
        } finally {
            if (navigated) {
                delay(200)
                withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_BACK) }
            }
        }
    }

    private fun looksLikeHtmlSource(t: String): Boolean {
        if (t.length < 300) return false
        val head = t.take(3000)
        return head.contains("<!DOCTYPE", ignoreCase = true) ||
            head.contains("<html", ignoreCase = true) ||
            Regex("<[a-z][^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(head)
    }

    // ---------------- Pembacaan pohon aksesibilitas ----------------

    private fun urlLike(s: String): Boolean {
        val t = s.trim()
        if (t.length < 6 || t.length > 2048) return false
        if (t.contains(' ')) return false
        val body = t.removePrefix("http://").removePrefix("https://")
        if (!body.contains('.')) return false
        val re = Regex("^(https?://)?[A-Za-z0-9\\-._~]+(:\\d+)?(/[^\\s]*)?(\\?[^\\s]*)?(#[^\\s]*)?$")
        return re.matches(t)
    }

    /** Cari teks URL di address bar (mendukung address bar atas maupun bawah). */
    private fun findAddressBarUrl(root: AccessibilityNodeInfo): String? {
        var best: Pair<Int, String>? = null
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var visited = 0
        val screenH = resources.displayMetrics.heightPixels
        while (q.isNotEmpty() && visited < MAX_URL_NODES) {
            val n = try { q.removeFirst() } catch (e: Exception) { break }
            visited++
            val candidates = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
            for (cand in candidates) {
                if (!urlLike(cand)) continue
                var score = 0
                val cls = n.className?.toString().orEmpty()
                if (cls.contains("EditText")) score += 6
                if (n.isVisibleToUser) score += 4
                val b = android.graphics.Rect()
                n.getBoundsInScreen(b)
                val top = b.top
                if (top < 400 || top > screenH - 400) score += 3 // address bar atas/bawah
                if (cand.startsWith("http")) score += 3
                if (cand.length > 8) score += 1
                if (best == null || score > best.first) best = score to cand.trim()
            }
            for (i in 0 until n.childCount) {
                try { n.getChild(i)?.let { q.add(it) } } catch (_: Exception) {}
            }
        }
        val raw = best?.second ?: return null
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    /** Snapshot struktur/teks halaman via a11y (best-effort, terbatas node & waktu). */
    private fun buildA11ySnapshot(root: AccessibilityNodeInfo): A11ySnapshot {
        val elements = mutableListOf<A11yElement>()
        var total = 0
        val start = System.currentTimeMillis()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty() && total < MAX_SNAP_NODES) {
            if (System.currentTimeMillis() - start > SNAP_BUDGET_MS) break
            val (n, depth) = stack.removeFirst()
            total++
            val text = (n.text?.toString() ?: "").trim()
            val desc = (n.contentDescription?.toString() ?: "").trim()
            val shown = (text.ifBlank { desc }).take(MAX_TEXT_PER_NODE)
            val clickable = n.isClickable
            if (shown.isNotBlank() || clickable) {
                val cls = n.className?.toString()?.substringAfterLast('.')
                val id = n.viewIdResourceName?.substringAfterLast('/')
                elements.add(A11yElement(cls, id, shown, clickable))
            }
            if (depth < 60) {
                for (i in 0 until n.childCount) {
                    try { n.getChild(i)?.let { stack.add(it to depth + 1) } } catch (_: Exception) {}
                }
            }
        }
        return A11ySnapshot(nodeCount = total, elements = elements)
    }

    /** Kumpulkan seluruh teks jendela (untuk membaca halaman view-source). */
    private fun collectAllText(root: AccessibilityNodeInfo, budgetMs: Long): String {
        val sb = StringBuilder()
        val start = System.currentTimeMillis()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var count = 0
        while (stack.isNotEmpty() && count < 4000) {
            if (System.currentTimeMillis() - start > budgetMs) break
            val n = stack.removeFirst()
            count++
            val t = n.text?.toString()
            if (!t.isNullOrBlank()) {
                sb.append(t)
                sb.append('\n')
            }
            for (i in 0 until n.childCount) {
                try { n.getChild(i)?.let { stack.add(it) } } catch (_: Exception) {}
            }
        }
        return sb.toString()
    }
}
