# Analisis Alur Program — Samsung HTML Scraper QA

Dokumen ini merangkum analisis mendalam alur program, keputusan desain, edge case yang ditangani,
dan hasil pengujian. Disusun sebagai bagian dari proses QA aplikasi ini sendiri.

---

## 1. Analisis Batasan Platform (kenapa arsitekturnya seperti ini)

| Pendekatan | Status | Alasan |
|---|---|---|
| API publik Samsung Internet untuk membaca tab | ❌ Tidak ada | Tidak disediakan oleh Samsung |
| Ekstensi resmi Samsung Internet (Add-ons) | ❌ Tidak praktis | Berbasis APK yang **wajib divalidasi Samsung** & didistribusikan via Galaxy Store (dok. resmi: *All third-party extension apps are validated and approved by Samsung*) |
| Membaca `/data/data/com.sec.android.app.sbrowser` | ❌ | Sandbox Android; butuh root |
| `view-source:` URL | ✅ | Didukung Samsung Internet (Chromium) — HTML sumber penuh, sesi browser ikut karena request dilakukan browser |
| Chrome DevTools Protocol (CDP) | ✅ | Samsung Internet mendukung remote debugging (`*_devtools_remote`); cukup `adb forward` — standar di lab QA |
| AccessibilityService | ✅ | Boleh membaca pohon jendela aplikasi aktif; best-effort untuk URL & teks |

Kesimpulan: **kombinasi 4 mode** memberi cakupan terbaik tanpa root, tanpa persetujuan Samsung,
tanpa distribusi khusus.

---

## 2. Alur Utama

### 2.1 Mode Bubble (A11y + view-source)

```
[Peristiwa TYPE_WINDOW_STATE_CHANGED]
        │ pkg == com.sec.android.app.sbrowser(.lite)?
        ├── ya → tampilkan bubble melayang (TYPE_APPLICATION_OVERLAY)
        └── tidak → sembunyikan bubble

[Bubble ditekan] (busy? → toast "masih memproses"; bukan Samsung? → toast)
        │ busy = true; toast "mengekstrak…"
        ▼
[rootInActiveWindow]  ── null ──► toast gagal, busy=false
        │
        ├─ findAddressBarUrl(): BFS ≤1200 node, skor (EditText +6, terlihat +4,
        │   posisi atas/bawah layar +3, ber-scheme +3) → normalisasi https://
        ├─ buildA11ySnapshot(): DFS ≤2500 node / ≤2,5 s → A11yElement[]
        ▼
[prefs.autoViewSource == true dan URL terbaca?]
        ├── ya → viewSourceCapture(url)
        │        ├─ startActivity(view-source:<url>) di Samsung Internet  (gagal → batal, return null)
        │        ├─ poll ≤10 s: kumpulkan seluruh teks jendela
        │        │    berhenti bila tampak <!DOCTYPE / <html / tag HTML
        │        ├─ tunggu stabilisasi 600 ms, ambil teks yang lebih panjang
        │        └─ finally: GLOBAL_ACTION_BACK (hanya bila navigasi sukses)
        └── tidak → lewati
        ▼
[rekam CaptureRecord] → CaptureStore.save() → toast + notifikasi → busy=false
```

**Poin anti-overlap & anti-race:**
- Flag `busy @Volatile` mencegah capture ganda beririsan.
- Bubble dibuat idempoten (`if (bubble != null) return`), dihapus aman di `hideBubble()` (guard null + try).
- `findAddressBarUrl` & snapshot memakai batas node/waktu → UI browser tidak pernah dibekukan.
- Setelah view-source, `BACK` hanya dikirim bila `navigated == true` (tidak menekan back sembarangan
  bila intent gagal).

### 2.2 Mode DevTools (CDP)

```
[Tombol Capture via DevTools]
        │ persist port & cookie manual
        ▼
[GET http://127.0.0.1:<port>/json/list]  ── gagal ──► dialog instruksi adb forward
        │ filter type == "page"
        ▼
[1 tab? langsung] ── [banyak? dialog pilih tab]
        ▼
[WebSocket ws://127.0.0.1:<port>/devtools/page/<id>]
        ├─ Runtime.enable, Log.enable, Page.enable, Network.enable
        ├─ (opsional) Page.reload + tunggu 3 s   ← log konsol lengkap sejak awal load
        ├─ Runtime.evaluate(SNAPSHOT_JS, awaitPromise) → html, document.cookie,
        │   localStorage, sessionStorage, ua, viewport, resources
        ├─ kumpulkan event 4–7,5 s:
        │    Runtime.consoleAPICalled / Runtime.exceptionThrown / Log.entryAdded → logs[]
        │    Network.requestWillBeSent / responseReceived / loadingFailed → network[]
        ├─ Storage.getCookies (fallback Network.getAllCookies) → cookie termasuk HttpOnly
        └─ Page.captureScreenshot (opsional)
        ▼
[CdpCaptureResult] → CaptureRecord (jar CDP menggantikan parse document.cookie bila ada,
agar tidak duplikat) → CaptureStore.save() → simpan cookie per-host untuk WebView mode
```

**Ketahanan WebSocket:**
- Handshake HTTP/1.1 + Sec-WebSocket-Key acak; frame klien **selalu masked** (RFC 6455 wajib).
- Membaca frame: panjang 7-bit / 16-bit / 64-bit, **fragmentasi** digabung, ping dijawab pong,
  close di-echo; payload dibatasi 64 MB.
- Timeout respons per-perintah (10–25 s) → tidak pernah menggantung tanpa batas.
- Koneksi dibatasi `soTimeout` 30 s → socket mati terdeteksi, bukan menggantung selamanya.

### 2.3 Mode WebView

```
[URL input] → normalisasi scheme → applyCookies(host):
     manual cookie string (prioritas) → auto cookie per-host (dari capture DevTools/WebView)
     → CookieManager.setCookie + flush
→ loadUrl → onPageFinished → tunggu 1,5 s (JS settle)
→ evaluateJavascript(SNAPSHOT_JS sinkron) → JSON → CaptureRecord
→ screenshot bitmap → CaptureStore.save → simpan cookie auto per-host → finish
Pengaman: timeout 45 s; onReceivedError (main frame) → toast; flag saved mencegah dobel.
```

---

## 3. Model Data & Penyimpanan

- `CaptureRecord` = metadata (record.json) + bagian besar terpisah (dom.html, source.html,
  parts.json, storage.json, a11y.json, screenshot.png). Manfaat: daftar riwayat cepat
  (hanya baca metadata), beban memori rendah.
- Semua operasi berkas di `CaptureStore` **disinkronkan** (lock objek) → aman dari akses paralel
  service vs activity.
- JSON murni `org.json` (tanpa Gson) → model dapat di-unit-test di JVM biasa tanpa Android.

## 4. Edge Case yang Ditangani

| # | Edge Case | Penanganan |
|---|---|---|
| 1 | Aksesibilitas dimatikan saat aplikasi jalan | Status di dashboard; bubble tidak muncul; toast panduan |
| 2 | Izin overlay belum diberikan | `showBubble()` return aman; tombol Izinkan di dashboard |
| 3 | Address bar tersembunyi / teks bukan URL | Heuristik skor; gagal → capture tetap disimpan (mode a11y) + toast penjelas |
| 4 | Address bar bawah (default SI terbaru) | Skor posisi `top > screenH-400` ikut dihitung |
| 5 | `view-source` tidak didukung / gagal buka | `navigated=false` → batal tanpa BACK; fallback: record a11y tetap tersimpan |
| 6 | Halaman sangat besar | Batas: node a11y 2500, teks sumber 4 MB, DOM 8 MB, frame WS 64 MB, teks tampilan 1 MB |
| 7 | Port CDP salah / adb forward belum jalan | Pesan dialog berisi perintah persis yang harus dijalankan |
| 8 | Banyak tab terbuka | Dialog pemilih tab (judul + URL) |
| 9 | Cookie duplikat (document.cookie vs jar) | Jar CDP (superset) menggantikan parse header |
| 10 | Respons CDP error (mis. storage dibatasi) | Fallback Storage.getCookies → Network.getAllCookies → catat ke errors[] |
| 11 | Capture dobel / tombol spam | Flag `busy` + flag `saved` (WebView) + debounce bubble |
| 12 | Halaman mati saat capture CDP | Frame close/EOF → exception → dialog gagal; record sebagian tidak setengah-jalan |
| 13 | Rotasi layar saat capture WebView | `configChanges` mencegah recreate; handler dibersihkan di onDestroy |
| 14 | Notifikasi Android 13+ | Runtime permission POST_NOTIFICATIONS, degradasi ke toast bila ditolak |
| 15 | Record rusak di penyimpanan | `CaptureStore.list/load` menangkap exception & melewati record rusak |
| 16 | Aktivitas layar detail tanpa data | load null → finish() otomatis |
| 17 | Aplikasi QA dipakai di browser lain | Bubble hanya muncul untuk paket Samsung Internet |

## 5. Hasil Pengujian

| Uji | Hasil |
|---|---|
| `gradlew :app:assembleDebug` (JDK 21, AGP 8.7.3, Gradle 8.10.2, SDK 34) | ✅ BUILD SUCCESSFUL — APK 12,4 MB |
| `gradlew :app:testDebugUnitTest` — 14 kasus (Models 9 + WsFrame 5) | ✅ 14 lulus, 0 gagal |
| Validasi well-formed 18 file XML resource | ✅ semua OK |
| Review manual alur (state machine bubble, CDP handshake, penanganan timeout) | ✅ lulus |

## 6. Yang Disarankan Diuji di Perangkat Fisik

1. Instal APK → aktifkan aksesibilitas + overlay → buka Samsung Internet → bubble muncul.
2. Tekan bubble di halaman index → cek toast + riwayat: record `A11Y(+VIEW-SOURCE)`.
3. Aktifkan "view-source otomatis" → pastikan browser kembali ke halaman semula setelah capture.
4. Jalankan `adb forward tcp:9333 localabstract:com.sec.android.app.sbrowser_devtools_remote`
   → Tes → Capture via DevTools → verifikasi bagian DOM/COOKIES/LOGS/NETWORK terisi.
5. Capture via WebView dengan cookie manual → muat halaman yang butuh login → verifikasi konten
   ter-autentikasi.
6. Detail → Salin / Bagikan ZIP / Hapus.
