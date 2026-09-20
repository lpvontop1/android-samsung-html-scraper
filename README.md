# Samsung HTML Scraper QA

Aplikasi Android native (Kotlin, **Android 9 / API 28 ke atas**) untuk kebutuhan **testing & QA**:
mengambil dan menyimpan **HTML/DOM, log konsol, sesi & cookies** dari halaman yang sedang dibuka di
**Samsung Internet Browser** — per halaman (tekan tombol → ekstrak halaman aktif saja).

> ⚠️ Gunakan hanya untuk QA/testing pada situs milik Anda sendiri atau yang Anda miliki izinnya.
> Data capture berisi konten halaman dan cookie sesi — simpan dan bagikan secara bertanggung jawab.

---

## Cara Kerja (Ringkas)

Android menyandera (sandbox) aplikasi satu sama lain — tidak ada API publik untuk membaca isi tab
aplikasi lain. Setelah riset mendalam, solusi yang dipakai adalah **5 mode capture berlapis**,
dengan **Mode Bookmarklet sebagai mode utama yang 100% bebas ADB (tanpa PC, tanpa kabel USB)**:

| Mode | Sumber Data | Sesi/Cookies | Konsol Log | Butuh ADB? |
|------|-------------|--------------|------------|------------|
| **1. Bookmarklet ⭐** | DOM **hasil render JS** + `document.cookie` + localStorage/sessionStorage + daftar resource, dikirim bookmarklet → server lokal aplikasi | ✅ sesi browser asli (halaman dibuka di Samsung Internet sendiri) | — | **TIDAK** |
| **2. Bubble A11y** | URL address bar + teks/struktur halaman via AccessibilityService | — | — | Tidak |
| **3. view-source** | HTML sumber lengkap dibaca dari tab `view-source:` di Samsung Internet | ✅ memakai sesi browser (request dilakukan browser) | — | Tidak |
| **4. DevTools (CDP)** | Chrome DevTools Protocol via `adb forward` — DOM penuh, cookies (termasuk **HttpOnly**), console log, network, screenshot | ✅ penuh | ✅ penuh | Ya (atau wireless debugging Android 11+ tanpa kabel) |
| **5. WebView internal** | DOM, cookie, storage, log via WebView aplikasi | Cookie manual/impor per-host | ✅ | Tidak |

**Alur utama harian QA (Mode Bookmarklet — sekali pasang, terus pakai):**
1. Di aplikasi: **Mulai Server** → **Salin Kode Bookmarklet**.
2. Di Samsung Internet: buat bookmark baru, tempel kode bookmarklet di kolom URL (langkah rinci
   ada di tombol **Cara Pasang** di aplikasi).
3. Buka halaman target (login bila perlu) → buka bookmark **"Ambil HTML"** → halaman langsung
   terkirim ke aplikasi: DOM hasil render + cookie yang terbaca + storage + daftar resource.
4. Ingin halaman lain? Navigasikan di browser → jalankan bookmark lagi. Satu tekan = satu halaman.
5. Jika pengiriman langsung diblokir halaman (kasus http tanpa TLS), bookmarklet otomatis
   menyalin data ke clipboard → di aplikasi tekan **Tempel & Simpan**.

---

## Instalasi TANPA PC dan TANPA Kabel USB

1. Di HP, buka halaman **Releases** repo GitHub ini
   (`https://github.com/lpvontop1/android-samsung-html-scraper/releases`).
2. Unduh `app-debug.apk` dari rilis terbaru.
3. Buka file APK (via notifikasi unduhan atau aplikasi Files) → bila diminta, izinkan
   **"Install aplikasi dari sumber ini"** → Install.
4. Selesai — tidak perlu `adb`, tidak perlu PC, tidak perlu kabel.

> CI GitHub Actions repo ini juga membangun APK otomatis pada setiap push (tab *Actions* →
> artefak `app-debug-apk`) bila Anda ingin build terbaru.

### Build sendiri (opsional)

```bash
./gradlew :app:assembleDebug
# hasil: app/build/outputs/apk/debug/app-debug.apk
```

### Persiapan di aplikasi (satu kali)

1. **Mode Bookmarklet** — Mulai Server → Salin Kode Bookmarklet → pasang di Samsung Internet
   (tombol **Cara Pasang** memandu langkah demi langkah).
2. **Mode Bubble (opsional)** — *Aktifkan* Layanan Aksesibilitas *"Layanan Capture QA"* + izinkan
   bubble melayang.
3. Samsung Internet otomatis terdeteksi bila terpasang.

### Mode DevTools tanpa kabel (opsional, Android 11+)

Mode DevTools (paling lengkap) tetap memerlukan `adb forward`, tetapi **tidak harus via PC/kabel**:

1. Aktifkan *Developer options* → **Wireless debugging** → *Pair device with pairing code*.
2. Install **Termux** (F-Droid) → `pkg install android-tools`.
3. Di Termux (bisa di HP yang sama):
   ```bash
   adb pair 127.0.0.1:<PORT_PAIRING> <KODE_PAIRING>
   adb connect 127.0.0.1:<PORT_WIRELESS_DEBUG>
   adb forward tcp:9333 localabstract:com.sec.android.app.sbrowser_devtools_remote
   ```
4. Di aplikasi: **Tes** → **Capture via DevTools**.

Dengan PC cara lamanya tetap berlaku: `adb forward tcp:9333 localabstract:com.sec.android.app.sbrowser_devtools_remote`.

---

## Format Hasil Capture

Setiap capture disimpan sebagai satu folder di penyimpanan internal aplikasi:

```
files/captures/cap_20260920_101530_123/
├── record.json      # metadata: url, judul, waktu, mode, jumlah item
├── dom.html         # DOM saat itu (mode bookmarklet / devtools / webview)
├── source.html      # HTML sumber (mode view-source)
├── parts.json       # logs[], network[], cookies[]
├── storage.json     # localStorage, sessionStorage
├── a11y.json        # snapshot aksesibilitas (mode a11y)
└── screenshot.png   # bila diaktifkan (mode devtools)
```

Di layar **detail capture**: lihat per bagian (DOM / SOURCE / COOKIES / LOGS / NETWORK / STORAGE /
A11Y / META), **Salin** per bagian, **Bagikan ZIP**, atau **Hapus**.

---

## Struktur Proyek

```
app/src/main/java/com/qa/samsungscraper/
├── App.kt                      # inisialisasi store + notifikasi
├── activity/
│   ├── MainActivity.kt         # dashboard status, mode bookmarklet, DevTools/WebView, riwayat
│   ├── CaptureDetailActivity.kt# penampil 8 bagian + salin/ZIP/hapus
│   └── WebViewCaptureActivity.kt
├── adapter/CaptureAdapter.kt
├── cdp/
│   ├── Ws.kt                   # klien WebSocket minimal (RFC 6455) — tanpa dependensi
│   └── CdpClient.kt            # Chrome DevTools Protocol: evaluate, cookies, log, network
├── model/Models.kt             # model data + JSON (org.json, murni JVM, unit-tested)
├── server/
│   ├── CaptureServer.kt        # server HTTP 127.0.0.1 minimal (CORS + PNA) — murni JVM, unit-tested
│   └── CaptureServerService.kt # foreground service agar server tetap hidup saat pindah browser
├── service/ScraperAccessibilityService.kt  # bubble melayang + capture a11y + view-source
├── store/CaptureStore.kt       # penyimpanan berkas + indeks
├── util/BookmarkletPayload.kt  # generator kode bookmarklet — murni JVM, unit-tested
└── util/Util.kt                # prefs, token, notifikasi, ZIP, clipboard
```

---

## Pengujian

- **Unit test** (29 kasus): model & JSON round-trip, parser cookie, snapshot → record,
  codec frame WebSocket (encode/decode, panjang 7/16/64-bit, ping/close), generator bookmarklet
  (format, endpoint, field payload, fallback clipboard), server HTTP lokal (POST valid, token
  salah, preflight PNA, ping, batas body, error callback, multi-request).
  ```bash
  ./gradlew :app:testDebugUnitTest
  ```
- **Build**: `assembleDebug` lulus (lihat CI hijau di repo).
- **Analisis alur & edge case**: lihat [docs/ANALISIS-ALUR.md](docs/ANALISIS-ALUR.md).

## Batasan Teknis (jujur & transparan)

- Bookmarklet **tidak bisa** membaca cookie **HttpOnly** (fitur keamanan browser untuk semua situs).
  Untuk cookie HttpOnly + log konsol penuh, gunakan Mode DevTools.
- Log konsol halaman hanya tersedia di Mode DevTools & WebView (mode bookmarklet/a11y tidak membaca konsol).
- Tidak ada cara tanpa root untuk membaca *storage* internal Samsung Internet langsung; namun Mode
  Bookmarklet tetap "ter-session" karena kode dijalankan di dalam halaman browser itu sendiri.
- `view-source:` menampilkan HTML sumber; DOM hasil eksekusi JS didapat dari Mode Bookmarklet/
  DevTools/WebView.
- Samsung Internet kadang menyembunyikan address bar saat scroll — tekan bubble saat address bar
  terlihat, atau andalkan Mode Bookmarklet.
- Server tangkap hanya mendengarkan `127.0.0.1` (tidak terekspos ke jaringan) dan memverifikasi
  token rahasia di URL; token dibuat acak sekali per instalasi.

## Lisensi

MIT — lihat [LICENSE](LICENSE).
