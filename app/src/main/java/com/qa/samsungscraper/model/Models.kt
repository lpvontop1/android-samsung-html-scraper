package com.qa.samsungscraper.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Entri cookie (mendukung cookie HttpOnly hasil DevTools). */
data class CookieEntry(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    val httpOnly: Boolean = false,
    val secure: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("value", value)
        .putOpt("domain", domain)
        .putOpt("path", path)
        .put("httpOnly", httpOnly)
        .put("secure", secure)

    companion object {
        fun fromJson(j: JSONObject): CookieEntry = CookieEntry(
            name = j.optString("name"),
            value = j.optString("value"),
            domain = j.optString("domain").ifBlank { null },
            path = j.optString("path").ifBlank { null },
            httpOnly = j.optBoolean("httpOnly", false),
            secure = j.optBoolean("secure", false)
        )

        /** Parse header document.cookie: "a=1; b=2". */
        fun parseHeader(header: String): List<CookieEntry> {
            if (header.isBlank()) return emptyList()
            return header.split(";")
                .map { it.trim() }
                .filter { it.contains('=') }
                .map {
                    val idx = it.indexOf('=')
                    CookieEntry(
                        name = it.substring(0, idx).trim(),
                        value = it.substring(idx + 1).trim()
                    )
                }
                .filter { it.name.isNotBlank() }
        }
    }
}

/** Satu baris log konsol / exception. */
data class LogEntry(
    val level: String,
    val text: String,
    val time: Long,
    val source: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("level", level)
        .put("text", text)
        .put("time", time)
        .putOpt("source", source)

    companion object {
        fun fromJson(j: JSONObject) = LogEntry(
            level = j.optString("level", "log"),
            text = j.optString("text"),
            time = j.optLong("time", 0L),
            source = j.optString("source").ifBlank { null }
        )
    }
}

/** Satu entri jaringan (request/response/resource). */
data class NetEntry(
    val url: String,
    val method: String? = null,
    val status: Int? = null,
    val mimeType: String? = null,
    val kind: String? = null,
    val durationMs: Long? = null,
    val size: Long? = null
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("url", url)
        o.putOpt("method", method)
        status?.let { o.put("status", it) }
        o.putOpt("mimeType", mimeType)
        o.putOpt("kind", kind)
        durationMs?.let { o.put("durationMs", it) }
        size?.let { o.put("size", it) }
        return o
    }

    companion object {
        fun fromJson(j: JSONObject) = NetEntry(
            url = j.optString("url"),
            method = j.optString("method").ifBlank { null },
            status = if (j.has("status")) j.optInt("status") else null,
            mimeType = j.optString("mimeType").ifBlank { null },
            kind = j.optString("kind").ifBlank { null },
            durationMs = if (j.has("durationMs")) j.optLong("durationMs") else null,
            size = if (j.has("size")) j.optLong("size") else null
        )
    }
}

/** Elemen hasil snapshot aksesibilitas (best-effort). */
data class A11yElement(
    val cls: String?,
    val id: String?,
    val text: String,
    val clickable: Boolean
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.putOpt("cls", cls)
        o.putOpt("id", id)
        o.put("text", text)
        o.put("clickable", clickable)
        return o
    }
}

data class A11ySnapshot(
    val nodeCount: Int,
    val elements: List<A11yElement>
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (e in elements) arr.put(e.toJson())
        return JSONObject().put("nodeCount", nodeCount).put("elements", arr)
    }
}

/** Rekam capture lengkap satu halaman. */
data class CaptureRecord(
    var id: String,
    var url: String,
    var finalUrl: String? = null,
    var title: String = "",
    var capturedAt: Long = 0L,
    var browserPackage: String = "",
    val modes: MutableList<String> = mutableListOf(),
    var domHtml: String? = null,
    var sourceHtml: String? = null,
    var cookies: MutableList<CookieEntry> = mutableListOf(),
    var logs: MutableList<LogEntry> = mutableListOf(),
    var network: MutableList<NetEntry> = mutableListOf(),
    var localStorage: String? = null,
    var sessionStorage: String? = null,
    var documentCookie: String? = null,
    var a11y: A11ySnapshot? = null,
    var userAgent: String? = null,
    var viewport: String? = null,
    var readyState: String? = null,
    var hasScreenshot: Boolean = false,
    var screenshotPng: ByteArray? = null
) {
    /** JSON ringkas untuk record.json (tanpa isi besar). */
    fun toJsonMeta(): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("url", url)
            .put("title", title)
            .put("capturedAt", capturedAt)
            .put("browserPackage", browserPackage)
            .put("modes", JSONArray(modes))
        finalUrl?.let { o.put("finalUrl", it) }
        domHtml?.let { o.put("domChars", it.length) }
        sourceHtml?.let { o.put("sourceChars", it.length) }
        o.put("cookieCount", cookies.size)
        o.put("logCount", logs.size)
        o.put("netCount", network.size)
        o.putOpt("documentCookie", documentCookie)
        o.putOpt("userAgent", userAgent)
        o.putOpt("viewport", viewport)
        o.putOpt("readyState", readyState)
        o.put("hasDom", domHtml != null)
        o.put("hasSource", sourceHtml != null)
        o.put("hasStorage", localStorage != null || sessionStorage != null)
        o.put("hasA11y", a11y != null)
        o.put("hasScreenshot", hasScreenshot)
        a11y?.let { o.put("a11yNodes", it.nodeCount) }
        return o
    }

    fun toJsonFullParts(): JSONObject {
        val logsArr = JSONArray()
        for (l in logs) logsArr.put(l.toJson())
        val netArr = JSONArray()
        for (n in network) netArr.put(n.toJson())
        val ckArr = JSONArray()
        for (c in cookies) ckArr.put(c.toJson())
        return JSONObject()
            .put("logs", logsArr)
            .put("network", netArr)
            .put("cookies", ckArr)
    }

    companion object {
        const val MAX_SOURCE_CHARS = 4_000_000
        const val MAX_DOM_CHARS = 8_000_000

        fun newRecord(url: String, title: String, browserPackage: String, mode: String): CaptureRecord {
            val now = System.currentTimeMillis()
            return CaptureRecord(
                id = newId(now),
                url = url,
                title = title,
                capturedAt = now,
                browserPackage = browserPackage,
                modes = mutableListOf(mode)
            )
        }

        fun newId(now: Long = System.currentTimeMillis()): String {
            val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val rand = (100..999).random()
            return "cap_${fmt.format(Date(now))}_$rand"
        }

        fun fromJsonMeta(j: JSONObject): CaptureRecord {
            val modes = mutableListOf<String>()
            val mArr = j.optJSONArray("modes")
            if (mArr != null) for (i in 0 until mArr.length()) modes.add(mArr.optString(i))
            return CaptureRecord(
                id = j.optString("id"),
                url = j.optString("url"),
                finalUrl = j.optString("finalUrl").ifBlank { null },
                title = j.optString("title"),
                capturedAt = j.optLong("capturedAt", 0L),
                browserPackage = j.optString("browserPackage"),
                modes = modes,
                documentCookie = j.optString("documentCookie").ifBlank { null },
                userAgent = j.optString("userAgent").ifBlank { null },
                viewport = j.optString("viewport").ifBlank { null },
                readyState = j.optString("readyState").ifBlank { null },
                hasScreenshot = j.optBoolean("hasScreenshot", false)
            )
        }

        /** Bangun record dari snapshot JSON string hasil Runtime.evaluate / evaluateJavascript. */
        fun fromSnapshotJson(browserPackage: String, mode: String, snapshotJson: String?): CaptureRecord {
            val rec = newRecord("", "", browserPackage, mode)
            if (snapshotJson.isNullOrBlank()) return rec
            return try {
                val j = JSONObject(snapshotJson)
                rec.url = j.optString("url")
                rec.finalUrl = j.optString("url").ifBlank { null }
                rec.title = j.optString("title")
                rec.domHtml = j.optString("html").ifBlank { null }?.take(MAX_DOM_CHARS)
                rec.documentCookie = j.optString("documentCookie").ifBlank { null }
                rec.localStorage = j.opt("localStorage")?.toString()?.takeIf { it != "null" }
                rec.sessionStorage = j.opt("sessionStorage")?.toString()?.takeIf { it != "null" }
                rec.userAgent = j.optString("ua").ifBlank { null }
                rec.viewport = j.optString("viewport").ifBlank { null }
                rec.readyState = j.optString("readyState").ifBlank { null }
                val res = j.optJSONArray("resources")
                if (res != null) {
                    for (i in 0 until res.length()) {
                        val r = res.optJSONObject(i) ?: continue
                        rec.network.add(
                            NetEntry(
                                url = r.optString("name"),
                                method = r.optString("type").ifBlank { null },
                                kind = "resource",
                                durationMs = if (r.has("duration")) r.optLong("duration") else null,
                                size = if (r.has("size")) r.optLong("size") else null
                            )
                        )
                    }
                }
                if (rec.title.isBlank()) rec.title = hostOf(rec.url) ?: "(tanpa judul)"
                rec.cookies.addAll(CookieEntry.parseHeader(rec.documentCookie ?: ""))
                rec
            } catch (e: Exception) {
                rec.title = "(snapshot tidak valid)"
                rec
            }
        }

        /** Judul <title> dari HTML mentah. */
        fun extractTitleFromHtml(html: String?): String? {
            if (html.isNullOrBlank()) return null
            val m = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html.take(200_000))
                ?: return null
            return m.groupValues[1].trim().take(300).ifBlank { null }
        }
    }
}

/** Host dari URL (pure, unit-testable). */
fun hostOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return try {
        var u = url.trim()
        if (!u.contains("://")) u = "https://$u"
        val rest = u.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPart = authority.substringAfterLast('@')
        val host = if (hostPart.contains(':')) hostPart.substringBefore(':') else hostPart
        host.ifBlank { null }
    } catch (e: Exception) {
        null
    }
}
