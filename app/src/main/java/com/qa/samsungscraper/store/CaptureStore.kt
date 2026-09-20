package com.qa.samsungscraper.store

import android.content.Context
import com.qa.samsungscraper.model.A11ySnapshot
import com.qa.samsungscraper.model.CaptureRecord
import com.qa.samsungscraper.model.CookieEntry
import com.qa.samsungscraper.model.LogEntry
import com.qa.samsungscraper.model.NetEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Penyimpanan capture berbasis file di penyimpanan internal aplikasi:
 * files/captures/<id>/record.json + dom.html + source.html + logs.json + ...
 * Thread-safe: semua operasi disinkronkan pada lock internal.
 */
object CaptureStore {
    private lateinit var baseDir: File
    private val lock = Any()

    fun init(ctx: Context) {
        synchronized(lock) {
            if (!::baseDir.isInitialized) {
                baseDir = File(ctx.filesDir, "captures").apply { mkdirs() }
            }
        }
    }

    fun dirOf(id: String): File = File(baseDir, id)

    fun list(): List<CaptureRecord> {
        synchronized(lock) {
            if (!::baseDir.isInitialized) return emptyList()
            val out = mutableListOf<CaptureRecord>()
            val dirs = baseDir.listFiles() ?: return emptyList()
            for (d in dirs) {
                if (!d.isDirectory) continue
                val meta = File(d, "record.json")
                if (!meta.isFile) continue
                try {
                    out.add(CaptureRecord.fromJsonMeta(JSONObject(meta.readText())))
                } catch (e: Exception) {
                    // lewati record rusak
                }
            }
            return out.sortedByDescending { it.capturedAt }
        }
    }

    fun save(rec: CaptureRecord) {
        synchronized(lock) {
            val d = dirOf(rec.id)
            d.mkdirs()
            // Bagian besar ditulis sebagai file terpisah
            rec.domHtml?.let { File(d, "dom.html").writeText(it) }
            rec.sourceHtml?.let { File(d, "source.html").writeText(it) }
            rec.screenshotPng?.let { File(d, "screenshot.png").writeBytes(it) }
            File(d, "parts.json").writeText(rec.toJsonFullParts().toString(2))
            val storage = JSONObject()
            storage.putOpt("localStorage", rec.localStorage)
            storage.putOpt("sessionStorage", rec.sessionStorage)
            File(d, "storage.json").writeText(storage.toString(2))
            rec.a11y?.let { File(d, "a11y.json").writeText(it.toJson().toString(2)) }
            // record.json terakhir agar selalu konsisten dengan file lain
            File(d, "record.json").writeText(rec.toJsonMeta().toString(2))
        }
    }

    fun load(id: String): CaptureRecord? {
        synchronized(lock) {
            val d = dirOf(id)
            val meta = File(d, "record.json")
            if (!meta.isFile) return null
            return try {
                val rec = CaptureRecord.fromJsonMeta(JSONObject(meta.readText()))
                val dom = File(d, "dom.html")
                if (dom.isFile) rec.domHtml = dom.readText()
                val src = File(d, "source.html")
                if (src.isFile) rec.sourceHtml = src.readText()
                val shot = File(d, "screenshot.png")
                if (shot.isFile) {
                    rec.hasScreenshot = true
                    rec.screenshotPng = shot.readBytes()
                }
                val parts = File(d, "parts.json")
                if (parts.isFile) {
                    val p = JSONObject(parts.readText())
                    val logs = p.optJSONArray("logs") ?: JSONArray()
                    for (i in 0 until logs.length()) {
                        rec.logs.add(LogEntry.fromJson(logs.optJSONObject(i) ?: continue))
                    }
                    val nets = p.optJSONArray("network") ?: JSONArray()
                    for (i in 0 until nets.length()) {
                        rec.network.add(NetEntry.fromJson(nets.optJSONObject(i) ?: continue))
                    }
                    val cks = p.optJSONArray("cookies") ?: JSONArray()
                    for (i in 0 until cks.length()) {
                        rec.cookies.add(CookieEntry.fromJson(cks.optJSONObject(i) ?: continue))
                    }
                }
                val storage = File(d, "storage.json")
                if (storage.isFile) {
                    val s = JSONObject(storage.readText())
                    rec.localStorage = s.optString("localStorage").ifBlank { null }
                    rec.sessionStorage = s.optString("sessionStorage").ifBlank { null }
                }
                val a11y = File(d, "a11y.json")
                if (a11y.isFile) {
                    val a = JSONObject(a11y.readText())
                    val els = mutableListOf<com.qa.samsungscraper.model.A11yElement>()
                    val arr = a.optJSONArray("elements") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        els.add(
                            com.qa.samsungscraper.model.A11yElement(
                                cls = e.optString("cls").ifBlank { null },
                                id = e.optString("id").ifBlank { null },
                                text = e.optString("text"),
                                clickable = e.optBoolean("clickable", false)
                            )
                        )
                    }
                    rec.a11y = A11ySnapshot(a.optInt("nodeCount", els.size), els)
                }
                rec
            } catch (e: Exception) {
                null
            }
        }
    }

    fun delete(id: String): Boolean {
        synchronized(lock) {
            val d = dirOf(id)
            if (!d.exists()) return false
            return d.deleteRecursively()
        }
    }
}
