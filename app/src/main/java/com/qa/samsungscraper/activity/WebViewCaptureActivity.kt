package com.qa.samsungscraper.activity

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.qa.samsungscraper.R
import com.qa.samsungscraper.databinding.ActivityWebviewCaptureBinding
import com.qa.samsungscraper.model.CaptureRecord
import com.qa.samsungscraper.model.CookieEntry
import com.qa.samsungscraper.model.LogEntry
import com.qa.samsungscraper.model.hostOf
import com.qa.samsungscraper.store.CaptureStore
import com.qa.samsungscraper.util.Prefs
import com.qa.samsungscraper.util.Util
import kotlinx.coroutines.DelicateCoroutinesApi
import org.json.JSONTokener
import java.io.ByteArrayOutputStream

/**
 * Capture via WebView internal: DOM, document.cookie, localStorage/sessionStorage,
 * console log (onConsoleMessage), dan screenshot.
 * Cookie dapat berasal dari input manual atau hasil capture DevTools per-host.
 */
class WebViewCaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewCaptureBinding
    private lateinit var prefs: android.content.SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val logs = mutableListOf<LogEntry>()

    @Volatile
    private var saved = false
    private var finalUrl = ""

    private val snapshotJs = """
    (function () {
      function ls(s) {
        try {
          var o = {};
          for (var i = 0; i < s.length; i++) { var k = s.key(i); o[k] = s.getItem(k); }
          return o;
        } catch (e) { return { __error: String(e) }; }
      }
      return JSON.stringify({
        url: location.href,
        title: document.title,
        readyState: document.readyState,
        html: document.documentElement.outerHTML,
        documentCookie: document.cookie,
        localStorage: ls(window.localStorage),
        sessionStorage: ls(window.sessionStorage),
        ua: navigator.userAgent,
        viewport: window.innerWidth + 'x' + window.innerHeight,
        resources: (performance.getEntriesByType('resource') || []).slice(-300).map(function (e) {
          return { name: e.name, type: e.initiatorType, duration: Math.round(e.duration), size: e.transferSize || 0 };
        })
      });
    })()
    """

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        var url = intent.getStringExtra("url")?.trim().orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                finalUrl = url
                binding.txtProgress.text = getString(R.string.webview_saving)
                handler.postDelayed({ saveCapture() }, 1500)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    Util.toast(this@WebViewCaptureActivity, getString(R.string.webview_fail, error.description ?: "error"))
                }
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                logs.add(
                    LogEntry(
                        level = message.messageLevel()?.name?.lowercase() ?: "log",
                        text = "[${message.sourceId() ?: "?"}:${message.lineNumber()}] ${message.message()}".take(2000),
                        time = System.currentTimeMillis(),
                        source = "webview"
                    )
                )
                return true
            }
        }

        applyCookies(url)
        binding.webView.loadUrl(url)

        // Pengaman: jika memuat > 45 detik, batalkan
        handler.postDelayed({
            if (!isFinishing && !saved) {
                Util.toast(this, getString(R.string.webview_fail, "timeout memuat halaman"))
                finish()
            }
        }, 45_000)
    }

    /** Isi cookie: prioritas input manual, lalu hasil capture sebelumnya untuk host yang sama. */
    private fun applyCookies(url: String) {
        try {
            val host = hostOf(url) ?: return
            val manual = prefs.getString(Prefs.COOKIES_MANUAL, "").orEmpty().trim()
            val auto = prefs.getString(Prefs.autoCookieKey(host), "").orEmpty().trim()
            val src = if (manual.isNotBlank()) manual else auto
            if (src.isBlank()) return
            val cm = CookieManager.getInstance()
            for (c in src.split(";")) {
                val pair = c.trim()
                if (pair.contains('=')) {
                    try { cm.setCookie(url, pair) } catch (_: Exception) {}
                }
            }
            cm.flush()
        } catch (_: Exception) {}
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveCapture() {
        if (saved) return
        saved = true
        binding.webView.evaluateJavascript(snapshotJs) { result ->
            try {
                val unquoted = if (!result.isNullOrBlank() && result.startsWith("\"")) {
                    JSONTokener(result).nextValue() as? String ?: ""
                } else result.orEmpty()

                val rec = CaptureRecord.fromSnapshotJson(
                    browserPackage = "android.webkit.WebView",
                    mode = "webview",
                    snapshotJson = unquoted
                )
                rec.finalUrl = finalUrl.ifBlank { rec.url }
                rec.logs.addAll(logs)

                // Screenshot
                try {
                    val w = binding.webView.width.coerceAtLeast(1)
                    val h = binding.webView.height.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    binding.webView.draw(android.graphics.Canvas(bmp))
                    val png = ByteArrayOutputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 85, out)
                        out.toByteArray()
                    }
                    rec.screenshotPng = png
                    rec.hasScreenshot = true
                    bmp.recycle()
                } catch (_: Exception) {}

                CaptureStore.save(rec)

                // Simpan cookie otomatis per-host untuk capture berikutnya
                if (rec.cookies.isNotEmpty()) {
                    val host = hostOf(rec.url)
                    if (host != null) {
                        prefs.edit()
                            .putString(
                                Prefs.autoCookieKey(host),
                                rec.cookies.joinToString("; ") { "${it.name}=${it.value}" }
                            )
                            .apply()
                    }
                }

                Util.toast(this, getString(R.string.webview_saved) + ": " + rec.title)
                finish()
            } catch (e: Exception) {
                Util.toast(this, getString(R.string.webview_fail, e.message ?: "?"))
                finish()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }
}
