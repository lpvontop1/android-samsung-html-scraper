package com.qa.samsungscraper.util

/**
 * Generator kode bookmarklet "Ambil HTML" untuk Samsung Internet.
 *
 * Cara kerja: bookmarklet dijalankan PADA halaman target di dalam Samsung Internet,
 * sehingga data yang diambil adalah kondisi halaman asli milik browser (session
 * sudah terpasang, DOM sudah hasil render JavaScript, cookie yang dapat dibaca
 * skrip ikut terbaca). Payload dikirim ke server lokal aplikasi di 127.0.0.1:port
 * lewat fetch(); jika gagal (mis. halaman http biasa yang diblokir Chromium),
 * payload otomatis disalin ke clipboard untuk ditempel di aplikasi.
 *
 * Murni Kotlin tanpa dependensi Android agar mudah di-unit-test.
 */
object BookmarkletPayload {

    /** Placeholder yang diganti saat build(). */
    const val PLACEHOLDER_PORT = "__PORT__"
    const val PLACEHOLDER_TOKEN = "__TOKEN__"

    /**
     * Template JavaScript. Satu pernyataan per baris (tanpa komentar //),
     * lalu diratakan menjadi SATU baris oleh build() agar aman ditempel
     * ke kolom URL bookmark.
     */
    private val TEMPLATE = """
        (function(){
        var d=document;
        var P={url:location.href,title:d.title,html:d.documentElement.outerHTML,documentCookie:d.cookie,localStorage:(function(){try{return JSON.stringify(localStorage)}catch(e){return null}})(),sessionStorage:(function(){try{return JSON.stringify(sessionStorage)}catch(e){return null}})(),ua:navigator.userAgent,viewport:(window.innerWidth+'x'+window.innerHeight),readyState:d.readyState,resources:(function(){try{return performance.getEntriesByType('resource').map(function(r){return{name:r.name,type:r.initiatorType,duration:Math.round(r.duration),size:(r.transferSize||0)}})}catch(e){return[]}})()};
        var J=JSON.stringify(P);
        var EP='http://127.0.0.1:__PORT__/cap/__TOKEN__';
        var fb=function(){try{var ta=d.createElement('textarea');ta.value=J;ta.setAttribute('readonly','readonly');ta.style.position='fixed';ta.style.left='-9999px';d.body.appendChild(ta);ta.select();var ok=false;try{ok=d.execCommand('copy')}catch(e1){}d.body.removeChild(ta);alert(ok?'Kirim langsung gagal. Data HTML DISALIN ke clipboard. Buka aplikasi lalu tekan Tempel dan Simpan.':'Kirim dan salin gagal. Gunakan tombol bubble atau mode lain.')}catch(e2){alert('Gagal memproses: '+e2)}};
        try{fetch(EP,{method:'POST',headers:{'Content-Type':'text/plain;charset=UTF-8'},body:J}).then(function(r){if(r&&r.ok){alert('Berhasil. Halaman terkirim ke aplikasi.')}else{fb()}},function(){fb()}).catch(function(){fb()})}catch(e3){fb()}
        })()
    """.trimIndent()

    /**
     * Bangun kode bookmarklet lengkap (diawali "javascript:") untuk
     * server lokal pada [port] dengan [token] keamanan.
     */
    fun build(port: Int, token: String): String {
        require(port in 1024..65535) { "port di luar rentang aman: $port" }
        require(token.isNotBlank()) { "token tidak boleh kosong" }
        val js = TEMPLATE
            .replace(PLACEHOLDER_PORT, port.toString())
            .replace(PLACEHOLDER_TOKEN, token)
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return "javascript:$js"
    }
}
