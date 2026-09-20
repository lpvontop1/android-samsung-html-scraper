package com.qa.samsungscraper.server

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server HTTP lokal minimal (hanya 127.0.0.1) yang menerima payload JSON
 * dari bookmarklet di Samsung Internet.
 *
 * Fitur protokol yang wajib didukung agar fetch() dari halaman https
 * di Chromium (basis Samsung Internet) tidak diblokir:
 *  - Preflight OPTIONS dengan header "Access-Control-Allow-Private-Network: true"
 *    (Private Network Access, Chromium 94+).
 *  - Header CORS standar pada semua respons.
 *  - Body POST text/plain berukuran hingga [maxBodyBytes].
 *
 * Kelas ini murni JVM (tanpa API Android) sehingga dapat di-unit-test langsung.
 */
class CaptureServer(
    val port: Int,
    private val token: String,
    /** Dipanggil di thread worker untuk setiap POST valid. */
    private val onPayload: (String) -> Unit,
    /** Dipanggil saat server gagal start / error fatal. */
    private val onError: (String) -> Unit = {},
    /** Batas ukuran body (byte); default 32 MB. */
    val maxBodyBytes: Long = 32L * 1024 * 1024
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    val isRunning: Boolean get() = running.get()

    /** Buka port dan mulai menerima koneksi. Aman dipanggil dua kali. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
            serverSocket = ss
            val t = Thread({ acceptLoop(ss) }, "capture-server-accept")
            t.isDaemon = true
            t.start()
        } catch (e: Exception) {
            running.set(false)
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            onError("Gagal membuka port $port: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Hentikan server dan lepaskan port. Instansi tidak bisa dipakai ulang. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdown()
        try { pool.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val sock: Socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (running.get()) onError("Server berhenti: ${e.message ?: e.javaClass.simpleName}")
                return
            }
            try {
                pool.execute { handle(sock) }
            } catch (_: Exception) {
                // pool sudah shutdown — tutup koneksi
                try { sock.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 30_000
                val ins = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val req = parseRequest(ins)
                if (req == null) {
                    writeResponse(out, 400, "bad request", corsHeaders(null))
                    return
                }
                val path = req.path.substringBefore('?')
                when {
                    path == "/ping" && req.method == "GET" ->
                        writeResponse(out, 200, "ok", corsHeaders(null))

                    path == "/cap/$token" && req.method == "OPTIONS" ->
                        writeResponse(out, 204, "", corsHeaders(req.headers["access-control-request-headers"]))

                    path == "/cap/$token" && req.method == "POST" -> {
                        val ok = try {
                            onPayload(req.body)
                            true
                        } catch (e: Exception) {
                            false
                        }
                        if (ok) writeResponse(out, 200, "ok", corsHeaders(null))
                        else writeResponse(out, 500, "gagal memproses", corsHeaders(null))
                    }

                    path == "/cap/$token" ->
                        writeResponse(out, 405, "method not allowed", corsHeaders(null))

                    else -> writeResponse(out, 404, "not found", null)
                }
            }
        } catch (_: Exception) {
            // klien menutup koneksi lebih dulu / timeout — bukan error fatal
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun parseRequest(ins: InputStream): Request? {
        // 1) Baca header sampai CRLFCRLF (batas 32 KB)
        val head = StringBuilder()
        var ch: Int
        while (ins.read().also { ch = it } != -1) {
            head.append(ch.toChar())
            if (head.length > 32_000) return null
            if (head.endsWith("\r\n\r\n")) break
        }
        if (!head.endsWith("\r\n\r\n")) return null
        val lines = head.dropLast(4).split("\r\n")
        if (lines.isEmpty()) return null
        val first = lines[0].split(" ")
        if (first.size < 2) return null

        // 2) Parse header (kunci huruf kecil)
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                headers[lines[i].substring(0, idx).trim().lowercase(Locale.US)] =
                    lines[i].substring(idx + 1).trim()
            }
        }

        // 3) Baca body sesuai Content-Length (batas maxBodyBytes)
        val len = headers["content-length"]?.toLongOrNull() ?: 0L
        if (len < 0 || len > maxBodyBytes) return null
        val buf = ByteArray(len.toInt())
        var read = 0
        while (read < len) {
            val r = ins.read(buf, read, (len - read).toInt())
            if (r == -1) break
            read += r
        }
        val body = String(buf, 0, read, Charsets.UTF_8)
        return Request(first[0].uppercase(Locale.US), first[1], headers, body)
    }

    private fun corsHeaders(requestedHeaders: String?): List<Pair<String, String>> = listOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "POST, GET, OPTIONS",
        "Access-Control-Allow-Headers" to (requestedHeaders?.takeIf { it.isNotBlank() } ?: "Content-Type"),
        "Access-Control-Allow-Private-Network" to "true",
        "Access-Control-Max-Age" to "86400"
    )

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun writeResponse(out: OutputStream, code: Int, bodyText: String, cors: List<Pair<String, String>>?) {
        val body = bodyText.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        sb.append("Content-Type: text/plain; charset=utf-8\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        cors?.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (code != 204 && body.isNotEmpty()) out.write(body)
        out.flush()
    }
}
