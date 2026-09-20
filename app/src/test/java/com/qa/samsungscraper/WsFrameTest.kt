package com.qa.samsungscraper

import com.qa.samsungscraper.cdp.WsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WsFrameTest {

    @Test
    fun `encode frame kecil masked dan decode kembali`() {
        val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val payload = "hello CDP".toByteArray(Charsets.UTF_8)
        val frame = WsClient.encodeClientFrame(WsClient.OP_TEXT, payload, mask)

        // byte pertama: FIN + opcode text
        assertEquals(0x81, frame[0].toInt() and 0xFF)
        // byte kedua: masked + len 9
        assertEquals(0x89, frame[1].toInt() and 0xFF)
        // 4 byte mask
        for (i in 0 until 4) assertEquals(mask[i].toInt(), frame[2 + i].toInt() and 0xFF)

        // decode seperti server (unmasked input untuk server tidak berlaku — kita simulasi decode
        // dengan men-encode ulang sebagai frame server tanpa mask)
        val serverFrame = byteArrayOf(0x81.toByte(), payload.size.toByte()) + payload
        val dec = WsClient.decodeServerFrame(serverFrame)
        assertTrue(dec.fin)
        assertEquals(WsClient.OP_TEXT, dec.opcode)
        assertEquals("hello CDP", String(dec.payload, Charsets.UTF_8))
        assertEquals(serverFrame.size, dec.consumed)
    }

    @Test
    fun `encode frame 16bit length`() {
        val mask = byteArrayOf(1, 2, 3, 4)
        val payload = ByteArray(300) { it.toByte() }
        val frame = WsClient.encodeClientFrame(WsClient.OP_TEXT, payload, mask)
        assertEquals(0x81, frame[0].toInt() and 0xFF)
        assertEquals(0xFE, frame[1].toInt() and 0xFF) // masked + 126
        assertEquals(300, ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF))
    }

    @Test
    fun `encode frame 64bit length`() {
        val mask = byteArrayOf(1, 2, 3, 4)
        val size = 70_000
        val payload = ByteArray(size) { (it % 251).toByte() }
        val frame = WsClient.encodeClientFrame(WsClient.OP_TEXT, payload, mask)
        assertEquals(0xFF, frame[1].toInt() and 0xFF) // masked + 127 (indikator panjang 64-bit)
        var len = 0L
        for (i in 0 until 8) len = (len shl 8) or (frame[2 + i].toInt() and 0xFF).toLong()
        assertEquals(size.toLong(), len)
        // ukuran total = header 2 + 8 + mask 4 + payload
        assertEquals(size + 14, frame.size)
    }

    @Test
    fun `decode frame dengan 16bit length dan unmask server tidak dipakai`() {
        val payload = "x".repeat(200).toByteArray(Charsets.UTF_8)
        val serverFrame = byteArrayOf(0x81.toByte(), 126.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload
        val dec = WsClient.decodeServerFrame(serverFrame)
        assertTrue(dec.fin)
        assertEquals(200, dec.payload.size)
        assertEquals(serverFrame.size, dec.consumed)
    }

    @Test
    fun `opcode close dan ping terenkode benar`() {
        val mask = byteArrayOf(9, 9, 9, 9)
        val close = WsClient.encodeClientFrame(WsClient.OP_CLOSE, byteArrayOf(0x03.toByte(), 0xE8.toByte()), mask)
        assertEquals(0x88, close[0].toInt() and 0xFF)
        val ping = WsClient.encodeClientFrame(WsClient.OP_PING, ByteArray(0), mask)
        assertEquals(0x89, ping[0].toInt() and 0xFF)
    }
}
