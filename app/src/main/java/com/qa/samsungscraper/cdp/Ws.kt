package com.qa.samsungscraper.cdp

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class WsException(message: String) : IOException(message)

/**
 * Klien WebSocket minimal (RFC 6455) tanpa dependensi eksternal,
 * khusus untuk Chrome DevTools Protocol di 127.0.0.1 (via adb forward).
 * Mendukung: handshake, frame teks (masked untuk klien), ping/pong, close,
 * dan fragmentasi frame dari server.
 */
class WsClient(
    private val host: String,
    private val port: Int,
    private val path: String,
    private val connectTimeoutMs: Int = 8000
) : Closeable {

    companion object {
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val MAX_PAYLOAD = 64L * 1024 * 1024

        data class DecodedFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray, val consumed: Int)

        /** Fungsi murni (unit-testable): encode frame klien — selalu di-mask sesuai RFC 6455. */
        fun encodeClientFrame(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
            require(mask.size == 4) { "mask harus 4 byte" }
            val out = ByteArrayOutputStream()
            out.write(0x80 or opcode)
            when {
                payload.size < 126 -> out.write(0x80 or payload.size)
                payload.size < 65536 -> {
                    out.write(0x80 or 126)
                    out.write((payload.size shr 8) and 0xFF)
                    out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(0x80 or 127)
                    val l = payload.size.toLong()
                    for (i in 7 downTo 0) out.write(((l shr (8 * i)) and 0xFFL).toInt())
                }
            }
            out.write(mask)
            for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
            return out.toByteArray()
        }

        /** Fungsi murni (unit-testable): decode satu frame server (tanpa mask). */
        fun decodeServerFrame(data: ByteArray): DecodedFrame {
            var off = 0
            val b0 = data[off].toInt() and 0xFF; off++
            val b1 = data[off].toInt() and 0xFF; off++
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                len = ((data[off].toInt() and 0xFF).toLong() shl 8) or (data[off + 1].toInt() and 0xFF).toLong()
                off += 2
            } else if (len == 127L) {
                var l = 0L
                for (i in 0 until 8) l = (l shl 8) or (data[off + i].toInt() and 0xFF).toLong()
                off += 8
                len = l
            }
            require(len <= MAX_PAYLOAD) { "payload terlalu besar" }
            val maskKey = if (masked) {
                val m = data.copyOfRange(off, off + 4); off += 4; m
            } else null
            val payload = data.copyOfRange(off, off + len.toInt())
            if (maskKey != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
            return DecodedFrame(fin, opcode, payload, off + len.toInt())
        }
    }

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: BufferedOutputStream
    private val closed = AtomicBoolean(false)
    private val sendLock = Any()

    var onMessage: ((String) -> Unit)? = null
    var onClose: ((code: Int, reason: String) -> Unit)? = null

    fun connect() {
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = 30_000
        input = DataInputStream(socket.getInputStream().buffered())
        output = BufferedOutputStream(socket.getOutputStream())

        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        output.write(req.toByteArray(Charsets.US_ASCII))
        output.flush()

        val status = readLine()
        if (!status.contains("101")) throw WsException("Handshake WebSocket gagal: $status")
        while (true) {
            val l = readLine()
            if (l.isEmpty()) break
        }
        thread(name = "ws-reader", isDaemon = true) { readLoop() }
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) throw WsException("Koneksi tertutup saat handshake")
            if (c == 10) break
            if (c != 13) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private data class RawFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readRaw(): RawFrame {
        val b0 = input.read(); if (b0 == -1) throw WsException("EOF")
        val b1 = input.read(); if (b1 == -1) throw WsException("EOF")
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            val hi = input.read(); val lo = input.read()
            if (hi == -1 || lo == -1) throw WsException("EOF")
            len = ((hi and 0xFF).toLong() shl 8) or (lo and 0xFF).toLong()
        } else if (len == 127L) {
            var l = 0L
            for (i in 0 until 8) {
                val v = input.read()
                if (v == -1) throw WsException("EOF")
                l = (l shl 8) or (v and 0xFF).toLong()
            }
            len = l
        }
        if (len > MAX_PAYLOAD) throw WsException("Payload terlalu besar: $len byte")
        val maskKey = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(len.toInt())
        if (len > 0) input.readFully(payload)
        if (maskKey != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        return RawFrame(fin, opcode, payload)
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val first = readRaw()
                var payload = first.payload
                var fin = first.fin
                val opcode = first.opcode
                while (!fin) {
                    val cont = readRaw()
                    if (payload.size + cont.payload.size > MAX_PAYLOAD) throw WsException("Payload terlalu besar (fragmentasi)")
                    payload += cont.payload
                    fin = cont.fin
                }
                when (opcode) {
                    OP_TEXT -> onMessage?.invoke(String(payload, Charsets.UTF_8))
                    OP_PING -> sendFrame(OP_PONG, payload)
                    OP_CLOSE -> {
                        val code = if (payload.size >= 2)
                            ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                        else 1005
                        try { sendFrame(OP_CLOSE, payload.take(2).toByteArray()) } catch (_: Exception) {}
                        closed.set(true)
                        onClose?.invoke(code, "")
                        return
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                closed.set(true)
                try { onClose?.invoke(1006, e.message ?: "koneksi terputus") } catch (_: Exception) {}
            }
        }
    }

    fun sendText(text: String) = synchronized(sendLock) { sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8)) }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        if (closed.get()) throw WsException("WebSocket sudah tertutup")
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        output.write(encodeClientFrame(opcode, payload, mask))
        output.flush()
    }

    override fun close() {
        if (closed.getAndSet(true)) return
        try { sendFrame(OP_CLOSE, ByteArray(2)) } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }
}
