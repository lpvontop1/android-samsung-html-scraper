package com.qa.samsungscraper.cdp

import com.qa.samsungscraper.model.LogEntry
import com.qa.samsungscraper.model.NetEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Target (tab) halaman yang terlihat via DevTools. */
data class CdpTarget(
    val id: String,
    val title: String,
    val url: String,
    val type: String,
    val wsPath: String
)

/** Hasil capture satu halaman via CDP. */
data class CdpCaptureResult(
    val snapshotJson: String?,
    val cookiesJson: JSONArray?,
    val logs: List<LogEntry>,
    val network: List<NetEntry>,
    val screenshotPng: ByteArray?,
    val errors: List<String>
)

/**
 * Klien Chrome DevTools Protocol minimal.
 *
 * Prasyarat (sekali per sesi, dijalankan dari PC/lab QA):
 *   adb forward tcp:<port> localabstract:com.sec.android.app.sbrowser_devtools_remote
 *
 * Kemampuan: DOM penuh (Runtime.evaluate), cookie semua (Storage.getCookies,
 * termasuk HttpOnly), console log (Runtime.consoleAPICalled + Log.entryAdded),
 * daftar jaringan (Network events), screenshot (Page.captureScreenshot).
 */
class CdpClient(private val port: Int) : Closeable {

    private var ws: WsClient? = null
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    private val idGen = AtomicInteger(10)

    private val logs = Collections.synchronizedList(mutableListOf<LogEntry>())
    private val net = Collections.synchronizedList(mutableListOf<NetEntry>())
    private val netIndex = ConcurrentHashMap<String, Int>()
    private val errors = Collections.synchronizedList(mutableListOf<String>())

    companion object {
        /** JS snapshot async (dipakai dengan awaitPromise=true). */
        const val SNAPSHOT_JS = """
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  await sleep(250);
  const safeLs = (s) => {
    try {
      const o = {};
      for (let i = 0; i < s.length; i++) { const k = s.key(i); o[k] = s.getItem(k); }
      return o;
    } catch (e) { return { __error: String(e) }; }
  };
  return JSON.stringify({
    url: location.href,
    title: document.title,
    readyState: document.readyState,
    html: document.documentElement.outerHTML,
    documentCookie: document.cookie,
    localStorage: safeLs(window.localStorage),
    sessionStorage: safeLs(window.sessionStorage),
    ua: navigator.userAgent,
    viewport: window.innerWidth + 'x' + window.innerHeight,
    resources: (performance.getEntriesByType('resource') || []).slice(-300).map((e) => ({
      name: e.name, type: e.initiatorType, duration: Math.round(e.duration), size: e.transferSize || 0
    }))
  });
})()
"""

        /** Daftar target halaman via HTTP /json/list. */
        fun listTargets(port: Int): List<CdpTarget> {
            val url = URL("http://127.0.0.1:$port/json/list")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            val body = try {
                conn.inputStream.buffered().reader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val arr = JSONArray(body)
            val out = mutableListOf<CdpTarget>()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val type = t.optString("type", "")
                if (type != "page") continue
                val id = t.optString("id", "")
                if (id.isBlank()) continue
                val wsUrl = t.optString("webSocketDebuggerUrl", "")
                val wsPath = if (wsUrl.isNotBlank()) {
                    "ws://127.0.0.1:$port".let { pfx ->
                        if (wsUrl.startsWith(pfx)) wsUrl.removePrefix(pfx)
                        else wsUrl.substringAfter("://").substringAfter('/').let { p -> "/$p" }
                    }
                } else {
                    "/devtools/page/$id"
                }
                out.add(
                    CdpTarget(
                        id = id,
                        title = t.optString("title", ""),
                        url = t.optString("url", ""),
                        type = type,
                        wsPath = wsPath
                    )
                )
            }
            return out
        }
    }

    /** Capture satu halaman (blocking; panggil dari Dispatchers.IO). */
    fun capturePage(
        target: CdpTarget,
        windowMs: Long,
        reload: Boolean,
        wantScreenshot: Boolean
    ): CdpCaptureResult {
        val client = WsClient("127.0.0.1", port, target.wsPath)
        client.onMessage = ::handleMessage
        client.connect()
        ws = client
        try {
            call("Runtime.enable", JSONObject())
            call("Log.enable", JSONObject())
            call("Page.enable", JSONObject())
            call("Network.enable", JSONObject())

            if (reload) {
                try { call("Page.reload", JSONObject().put("ignoreCache", true)) } catch (e: Exception) { errors.add("reload: $e") }
                // beri waktu page load; event console/network terkumpul pada jendela di bawah
                Thread.sleep(3000)
            } else {
                Thread.sleep(400)
            }

            // Snapshot DOM + storage + cookie document
            var snapshotJson: String? = null
            try {
                val params = JSONObject()
                    .put("expression", SNAPSHOT_JS)
                    .put("returnByValue", true)
                    .put("awaitPromise", true)
                val resp = call("Runtime.evaluate", params, 25_000)
                val inner = resp.optJSONObject("result")?.optJSONObject("result")
                val ex = resp.optJSONObject("result")?.optJSONObject("exceptionDetails")
                if (ex != null) {
                    errors.add("evaluate: " + (ex.optJSONObject("exception")?.optString("description") ?: ex.optString("text")))
                }
                val v = inner?.opt("value")
                if (v is String) snapshotJson = v
            } catch (e: Exception) {
                errors.add("evaluate: $e")
            }

            // Kumpulkan event console/network selama jendela waktu
            val collectMs = if (reload) (windowMs + 2500).coerceIn(3000, 15000) else windowMs
            Thread.sleep(collectMs)

            // Cookie penuh (termasuk HttpOnly)
            var cookiesJson: JSONArray? = null
            try {
                val respCk = call("Storage.getCookies", JSONObject(), 10_000)
                cookiesJson = respCk.optJSONObject("result")?.optJSONArray("cookies")
                    ?: respCk.optJSONArray("cookies")
                if (cookiesJson == null) {
                    val respCk2 = call("Network.getAllCookies", JSONObject(), 10_000)
                    cookiesJson = respCk2.optJSONObject("result")?.optJSONArray("cookies")
                        ?: respCk2.optJSONArray("cookies")
                }
            } catch (e: Exception) {
                errors.add("cookies: $e")
            }

            // Screenshot viewport (opsional)
            var shot: ByteArray? = null
            if (wantScreenshot) {
                try {
                    val data = call("Page.captureScreenshot", JSONObject().put("format", "png"), 15_000)
                        .optJSONObject("result")?.optString("data").orEmpty()
                    if (data.isNotBlank()) shot = Base64.getDecoder().decode(data)
                } catch (e: Exception) {
                    errors.add("screenshot: $e")
                }
            }

            return CdpCaptureResult(
                snapshotJson = snapshotJson,
                cookiesJson = cookiesJson,
                logs = logs.toList(),
                network = net.toList(),
                screenshotPng = shot,
                errors = errors.toList()
            )
        } finally {
            close()
        }
    }

    private fun handleMessage(line: String) {
        val j = try { JSONObject(line) } catch (e: Exception) { return }
        if (j.has("id")) {
            pending.remove(j.optInt("id"))?.complete(j)
            return
        }
        val method = j.optString("method")
        val params = j.optJSONObject("params") ?: JSONObject()
        when (method) {
            "Runtime.consoleAPICalled" -> {
                val type = params.optString("type")
                val args = params.optJSONArray("args") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until args.length()) {
                    val a = args.optJSONObject(i) ?: continue
                    val v = if (a.has("value")) {
                        a.opt("value")?.toString() ?: "null"
                    } else {
                        a.optString("description", a.optString("type", "?"))
                    }
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(v.take(2000))
                }
                val level = when (type) {
                    "error" -> "error"
                    "warning" -> "warn"
                    "info" -> "info"
                    "debug" -> "debug"
                    else -> "log"
                }
                logs.add(LogEntry(level, sb.toString(), System.currentTimeMillis(), "console"))
            }
            "Runtime.exceptionThrown" -> {
                val d = params.optJSONObject("exceptionDetails")
                val exDesc = d?.optJSONObject("exception")?.optString("description").orEmpty()
                val txt = exDesc.ifBlank { d?.optString("text").orEmpty().ifBlank { "exception" } }
                logs.add(LogEntry("error", txt.take(2000), System.currentTimeMillis(), "exception"))
            }
            "Log.entryAdded" -> {
                val e = params.optJSONObject("entry") ?: return
                logs.add(
                    LogEntry(
                        level = e.optString("level", "info"),
                        text = e.optString("text").take(2000),
                        time = System.currentTimeMillis(),
                        source = e.optString("source", "log")
                    )
                )
            }
            "Network.requestWillBeSent" -> {
                val req = params.optJSONObject("request") ?: return
                val entry = NetEntry(
                    url = req.optString("url"),
                    method = req.optString("method").ifBlank { null },
                    kind = "request"
                )
                val idx = synchronized(net) { net.add(entry); net.size - 1 }
                netIndex[params.optString("requestId")] = idx
            }
            "Network.responseReceived" -> {
                val res = params.optJSONObject("response") ?: return
                val idx = netIndex[params.optString("requestId")] ?: return
                val status = res.optInt("status", 0)
                val mime = res.optString("mimeType").ifBlank { null }
                synchronized(net) {
                    if (idx in net.indices) {
                        val e = net[idx]
                        net[idx] = e.copy(status = if (status == 0) null else status, mimeType = mime)
                    }
                }
            }
            "Network.loadingFailed" -> {
                val idx = netIndex[params.optString("requestId")] ?: return
                synchronized(net) {
                    if (idx in net.indices) {
                        val e = net[idx]
                        net[idx] = e.copy(status = -1)
                    }
                }
            }
        }
    }

    private fun call(method: String, params: JSONObject, timeoutMs: Long = 15_000): JSONObject {
        val w = ws ?: throw WsException("Belum terhubung ke DevTools")
        val id = idGen.incrementAndGet()
        val msg = JSONObject().put("id", id).put("method", method).put("params", params)
        val fut = CompletableFuture<JSONObject>()
        pending[id] = fut
        w.sendText(msg.toString())
        val resp = try {
            fut.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw WsException("Timeout menunggu respons $method")
        }
        resp.optJSONObject("error")?.let { err ->
            throw WsException("CDP $method: ${err.optString("message")} (kode ${err.optInt("code")})")
        }
        return resp
    }

    override fun close() {
        try { ws?.close() } catch (_: Exception) {}
        ws = null
    }
}
