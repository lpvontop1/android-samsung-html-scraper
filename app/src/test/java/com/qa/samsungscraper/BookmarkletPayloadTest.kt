package com.qa.samsungscraper

import com.qa.samsungscraper.util.BookmarkletPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkletPayloadTest {

    @Test
    fun `diawali javascript dan hanya satu baris`() {
        val code = BookmarkletPayload.build(8777, "abc123def456")
        assertTrue("harus diawali javascript:", code.startsWith("javascript:(function()"))
        assertFalse("tidak boleh mengandung newline", code.contains('\n'))
        assertFalse("tidak boleh mengandung carriage return", code.contains('\r'))
        assertFalse("tidak boleh mengandung tab", code.contains('\t'))
    }

    @Test
    fun `memuat endpoint port dan token`() {
        val code = BookmarkletPayload.build(8777, "abc123def456")
        assertTrue(code.contains("http://127.0.0.1:8777/cap/abc123def456"))
    }

    @Test
    fun `memuat semua field payload inti`() {
        val code = BookmarkletPayload.build(8777, "t")
        assertTrue(code.contains("outerHTML"))
        assertTrue(code.contains("documentCookie"))
        assertTrue(code.contains("localStorage"))
        assertTrue(code.contains("sessionStorage"))
        assertTrue(code.contains("navigator.userAgent"))
        assertTrue(code.contains("readyState"))
        assertTrue(code.contains("performance.getEntriesByType"))
    }

    @Test
    fun `menggunakan fetch dengan fallback clipboard`() {
        val code = BookmarkletPayload.build(8777, "t")
        assertTrue("harus memakai fetch", code.contains("fetch("))
        assertTrue("harus memakai POST", code.contains("'POST'"))
        assertTrue("harus memakai text/plain agar tanpa preflight CORS biasa", code.contains("text/plain"))
        assertTrue("fallback clipboard harus ada", code.contains("execCommand('copy')"))
    }

    @Test
    fun `placeholder tidak tersisa`() {
        val code = BookmarkletPayload.build(8999, "tok")
        assertFalse(code.contains(BookmarkletPayload.PLACEHOLDER_PORT))
        assertFalse(code.contains(BookmarkletPayload.PLACEHOLDER_TOKEN))
    }

    @Test
    fun `kode konsisten untuk input sama`() {
        val a = BookmarkletPayload.build(9000, "tok")
        val b = BookmarkletPayload.build(9000, "tok")
        assertEquals(a, b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `port terlalu kecil ditolak`() {
        BookmarkletPayload.build(80, "tok")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `token kosong ditolak`() {
        BookmarkletPayload.build(8777, "")
    }
}
