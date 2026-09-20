package com.qa.samsungscraper

import com.qa.samsungscraper.server.CaptureServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Uji CaptureServer langsung di JVM: server HTTP lokal + klien HttpURLConnection.
 */
class CaptureServerTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun http(
        port: Int,
        path: String,
        method: String,
        body: String? = null
    ): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        if (body != null) {
            conn.doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        val code = conn.responseCode
        val text = try {
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            try {
                conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            } catch (e2: Exception) {
                ""
            }
        }
        conn.disconnect()
        return code to text
    }

    @Test
    fun `post valid diteruskan ke callback`() {
        val port = freePort()
        val received = LinkedBlockingQueue<String>()
        val srv = CaptureServer(port, "tok123", onPayload = { received.add(it) })
        srv.start()
        try {
            assertTrue(srv.isRunning)
            val payload = """{"url":"https://contoh.com/a","title":"Contoh","html":"<html><body>hai</body></html>"}"""
            val (code, text) = http(port, "/cap/tok123", "POST", payload)
            assertEquals(200, code)
            assertEquals("ok", text)
            val got = received.poll(3, TimeUnit.SECONDS)
            assertEquals(payload, got)
        } finally {
            srv.stop()
        }
        assertFalse(srv.isRunning)
    }

    @Test
    fun `token salah ditolak`() {
        val port = freePort()
        val received = LinkedBlockingQueue<String>()
        val srv = CaptureServer(port, "rahasia", onPayload = { received.add(it) })
        srv.start()
        try {
            val (code, _) = http(port, "/cap/salah", "POST", "{}")
            assertEquals(404, code)
            assertEquals(null, received.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `preflight options memuat header private network`() {
        val port = freePort()
        val srv = CaptureServer(port, "tok", onPayload = {})
        srv.start()
        try {
            val conn = URL("http://127.0.0.1:$port/cap/tok").openConnection() as HttpURLConnection
            conn.requestMethod = "OPTIONS"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            assertEquals(204, conn.responseCode)
            assertEquals("*", conn.getHeaderField("Access-Control-Allow-Origin"))
            assertEquals("true", conn.getHeaderField("Access-Control-Allow-Private-Network"))
            assertTrue(
                conn.getHeaderField("Access-Control-Allow-Methods")!!.contains("POST")
            )
            conn.disconnect()
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `ping merespons ok`() {
        val port = freePort()
        val srv = CaptureServer(port, "tok", onPayload = {})
        srv.start()
        try {
            val (code, text) = http(port, "/ping", "GET")
            assertEquals(200, code)
            assertEquals("ok", text)
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `body melebihi batas ditolak`() {
        val port = freePort()
        val received = LinkedBlockingQueue<String>()
        // Server membatasi body 64 byte
        val srv = CaptureServer(port, "tok", onPayload = { received.add(it) }, maxBodyBytes = 64L)
        srv.start()
        try {
            val big = "x".repeat(200)
            val (code, _) = http(port, "/cap/tok", "POST", big)
            // parseRequest mengembalikan null -> respons 400
            assertEquals(400, code)
            assertEquals(null, received.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `callback yang melempar error menghasilkan 500`() {
        val port = freePort()
        val srv = CaptureServer(port, "tok", onPayload = { throw IllegalStateException("rusak") })
        srv.start()
        try {
            val (code, _) = http(port, "/cap/tok", "POST", "{}")
            assertEquals(500, code)
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `beberapa request berurutan semua terlayani`() {
        val port = freePort()
        val received = LinkedBlockingQueue<String>()
        val srv = CaptureServer(port, "tok", onPayload = { received.add(it) })
        srv.start()
        try {
            for (i in 1..5) {
                val (code, text) = http(port, "/cap/tok", "POST", "{\"n\":$i}")
                assertEquals(200, code)
                assertEquals("ok", text)
            }
            assertEquals(5, received.size.toLong())
        } finally {
            srv.stop()
        }
    }
}
