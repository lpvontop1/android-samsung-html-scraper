package com.qa.samsungscraper

import com.qa.samsungscraper.model.CaptureRecord
import com.qa.samsungscraper.model.CookieEntry
import com.qa.samsungscraper.model.hostOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun `parse header cookie dasar`() {
        val list = CookieEntry.parseHeader("session=abc123; theme=dark; =;novalue")
        assertEquals(2, list.size)
        assertEquals("session", list[0].name)
        assertEquals("abc123", list[0].value)
        assertEquals("theme", list[1].name)
        assertEquals("dark", list[1].value)
        assertEquals(false, list[0].httpOnly)
    }

    @Test
    fun `cookie json roundtrip`() {
        val c = CookieEntry("sid", "xyz", ".example.com", "/", true, true)
        val back = CookieEntry.fromJson(c.toJson())
        assertEquals(c, back)
    }

    @Test
    fun `hostOf berbagai bentuk url`() {
        assertEquals("example.com", hostOf("https://example.com/p/a?b=1"))
        assertEquals("sub.example.co.id", hostOf("http://user:pass@sub.example.co.id:8080/x#frag"))
        assertEquals("example.com", hostOf("example.com/page"))
        assertNull(hostOf(null))
        assertNull(hostOf(""))
    }

    @Test
    fun `meta json roundtrip mempertahankan field penting`() {
        val rec = CaptureRecord.newRecord("https://example.com/index", "Contoh", "com.sec.android.app.sbrowser", "a11y")
        rec.modes.add("view-source")
        rec.sourceHtml = "<html>x</html>"
        rec.cookies.add(CookieEntry("a", "1"))
        rec.logs.add(com.qa.samsungscraper.model.LogEntry("error", "boom", 123L, "console"))
        rec.network.add(com.qa.samsungscraper.model.NetEntry("https://example.com/x.js", "GET", 200, "application/javascript", "request", 12L, 345L))
        rec.localStorage = "{\"k\":\"v\"}"

        val meta = rec.toJsonMeta()
        val back = CaptureRecord.fromJsonMeta(meta)

        assertEquals(rec.id, back.id)
        assertEquals(rec.url, back.url)
        assertEquals(rec.title, back.title)
        assertEquals(rec.capturedAt, back.capturedAt)
        assertEquals(rec.browserPackage, back.browserPackage)
        assertEquals(rec.modes, back.modes)
        assertTrue(meta.getBoolean("hasSource"))
        assertTrue(meta.getBoolean("hasStorage"))
        assertEquals(1, meta.getInt("cookieCount"))
        assertEquals(1, meta.getInt("logCount"))
        assertEquals(1, meta.getInt("netCount"))
    }

    @Test
    fun `snapshot json menjadi record lengkap`() {
        val snap = """
        {"url":"https://qa.example.com/index.html","title":"QA Index","readyState":"complete",
         "html":"<html><body><h1>Hai</h1></body></html>",
         "documentCookie":"sid=9; pref=dark",
         "localStorage":{"cart":"2"},"sessionStorage":{"tmp":"x"},
         "ua":"Mozilla/5.0","viewport":"412x915",
         "resources":[{"name":"https://qa.example.com/app.js","type":"script","duration":33,"size":1024}]}
        """.trimIndent()
        val rec = CaptureRecord.fromSnapshotJson("com.sec.android.app.sbrowser", "devtools", snap)
        assertEquals("https://qa.example.com/index.html", rec.url)
        assertEquals("QA Index", rec.title)
        assertEquals("<html><body><h1>Hai</h1></body></html>", rec.domHtml)
        assertEquals("sid=9; pref=dark", rec.documentCookie)
        assertEquals(2, rec.cookies.size)
        assertEquals("sid", rec.cookies[0].name)
        assertEquals("{\"cart\":\"2\"}", rec.localStorage)
        assertEquals(1, rec.network.size)
        assertEquals(1024L, rec.network[0].size)
        assertEquals("script", rec.network[0].method)
        assertEquals("devtools", rec.modes[0])
    }

    @Test
    fun `snapshot tidak valid tidak melempar exception`() {
        val rec = CaptureRecord.fromSnapshotJson("p", "webview", "bukan-json{{{")
        assertEquals("(snapshot tidak valid)", rec.title)
    }

    @Test
    fun `snapshot kosong menghasilkan record kosong`() {
        val rec = CaptureRecord.fromSnapshotJson("p", "webview", null)
        assertEquals("", rec.url)
        assertFalse(rec.hasScreenshot)
        assertNotNull(rec.modes)
    }

    @Test
    fun `extract title dari html`() {
        assertEquals("Halo Dunia", CaptureRecord.extractTitleFromHtml("<html><head><TITLE>Halo Dunia</TITLE></head></html>"))
        assertNull(CaptureRecord.extractTitleFromHtml("<html><body>tanpa judul</body></html>"))
        assertNull(CaptureRecord.extractTitleFromHtml(null))
    }

    @Test
    fun `meta json valid dan dapat diparse ulang`() {
        val rec = CaptureRecord.newRecord("https://a.b/c", "T", "p", "a11y")
        val meta = rec.toJsonMeta()
        // memastikan tidak melempar
        JSONObject(meta.toString())
    }
}
