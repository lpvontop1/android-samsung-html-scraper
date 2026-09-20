# Samsung HTML Scraper QA

Aplikasi Android native (Kotlin, **Android 9 / API 28 ke atas**) untuk kebutuhan **testing & QA**:
mengambil dan menyimpan **HTML/DOM, log konsol, sesi & cookies** dari halaman yang sedang dibuka di
**Samsung Internet Browser** — per halaman (tekan tombol → ekstrak halaman aktif saja).

> ⚠️ Gunakan hanya untuk QA/testing pada situs milik Anda sendiri atau yang Anda miliki izinnya.
> Data capture berisi konten halaman dan cookie sesi — simpan dan bagikan secara bertanggung jawab.

---

## Cara Kerja (Ringkas)

Android menyandera (sandbox) aplikasi satu sama lain — tidak ada API publik untuk membaca isi tab
aplikasi lain. Setelah riset mendalam, solusi yang dipakai adalah **4 mode capture berlapis**:

| Mode | Sumber Data | Sesi/Cookies | Konsol Log | Kualitas |
|------|-------------|--------------|------------|----------|
| **1. Bubble A11y** | URL address bar + teks/struktur halaman via AccessibilityService | — | — | Best-effort, selalu tersedia |
| **2. view-source** | HTML sumber lengkap dibaca dari tab `view-source:` di Samsung Internet | ✅ memakai sesi browser (request dilakukan browser) | — | HTML sumber penuh (server-side) |
| **3. DevTools (CDP)** | Chrome DevTools Protocol via `adb forward` — DOM penuh, cookies (termasuk **HttpOnly**), console log, network, screenshot | ✅ penuh | ✅ penuh | **Paling lengkap (ala F12)** |
| **4. WebView internal** | DOM, cookie, storage, log via WebView aplikasi | Cookie manual/impor per-host | ✅ | Mandiri, tanpa PC |

**Alur utama harian QA:**
1. Buka halaman `index` di Samsung Internet → tekan **bubble melayang** → aplikasi menyimpan
   URL + snapshot + (opsional otomatis) HTML sumber via `view-source:` → otomatis kembali ke halaman.
2. Ingin halaman lain? Navigasikan di browser → tekan bubble lagi. Satu tekan = satu halaman.
3. Untuk audit penuh ala F12 (log + cookie HttpOnly + network), pakai **Mode DevTools**.

---

## Instalasi

### A. Build APK

```bash
./gradlew :app:assembleDebug
# hasil: app/build/outputs/apk/debug/app-debug.apk
```

Atau gunakan **GitHub Actions** (tab *Actions* di repo ini) yang otomatis membangun APK setiap push.
Install APK dengan `adb install app-debug.apk` atau salin ke perangkat.

### B. Persiapan di aplikasi (satu kali)

1. **Layanan Aksesibilitas** — buka aplikasi → tekan *Aktifkan* → cari *"Layanan Capture QA"* → ON.
2. **Izin Bubble Melayang** — tekan *Izinkan* (tampil di atas aplikasi lain).
3. Samsung Internet otomatis terdeteksi bila terpasang.

### C. Mode DevTools (opsional, untuk capture ala F12)

Prasyarat: PC/laptop dengan `adb` (Android platform-tools), USB debugging aktif di perangkat.

```bash
# Perangkat terhubung via USB (atau adb over Wi-Fi):
adb forward tcp:9333 localabstract:com.sec.android.app.sbrowser_devtools_remote
```

Lalu di aplikasi: isi port `9333` → **Tes** → **Capture via DevTools** → pilih tab → selesai.
(`adb forward` cukup dijalankan sekali per sesi debugging; port bisa diganti sesuai kebutuhan.)

---

## Format Hasil Capture

Setiap capture disimpan sebagai satu folder di penyimpanan internal aplikasi:

```
files/captures/cap_20260920_101530_123/
├── record.json      # metadata: url, judul, waktu, mode, jumlah item
├── dom.html         # DOM saat itu (mode devtools / webview)
├── source.html      # HTML sumber (mode view-source)
├── parts.json       # logs[], network[], cookies[]
├── storage.json     # localStorage, sessionStorage
├── a11y.json        # snapshot aksesibilitas (mode a11y)
└── screenshot.png   # bila diaktifkan
```

Di layar **detail capture**: lihat per bagian (DOM / SOURCE / COOKIES / LOGS / NETWORK / STORAGE /
A11Y / META), **Salin** per bagian, **Bagikan ZIP**, atau **Hapus**.

---

## Struktur Proyek

```
app/src/main/java/com/qa/samsungscraper/
├── App.kt                      # inisialisasi store + notifikasi
├── activity/
│   ├── MainActivity.kt         # dashboard status, capture DevTools/WebView, riwayat
│   ├── CaptureDetailActivity.kt# penampil 8 bagian + salin/ZIP/hapus
│   └── WebViewCaptureActivity.kt
├── adapter/CaptureAdapter.kt
├── cdp/
│   ├── Ws.kt                   # klien WebSocket minimal (RFC 6455) — tanpa dependensi
│   └── CdpClient.kt            # Chrome DevTools Protocol: evaluate, cookies, log, network
├── model/Models.kt             # model data + JSON (org.json, murni JVM, unit-tested)
├── service/ScraperAccessibilityService.kt  # bubble melayang + capture a11y + view-source
├── store/CaptureStore.kt       # penyimpanan berkas + indeks
└── util/Util.kt                # prefs, notifikasi, ZIP, clipboard
```

---

## Pengujian

- **Unit test** (14 kasus): model & JSON round-trip, parser cookie, snapshot → record,
  codec frame WebSocket (encode/decode, panjang 7-bit/16-bit/64-bit, ping/close).
  ```bash
  ./gradlew :app:testDebugUnitTest
  ```
- **Build**: `assembleDebug` lulus (lihat CI hijau di repo).
- **Analisis alur & edge case**: lihat [docs/ANALISIS-ALUR.md](docs/ANALISIS-ALUR.md).

## Batasan Teknis (jujur & transparan)

- Tidak ada cara tanpa root untuk membaca *storage* internal Samsung Internet langsung; karenanya
  cookie **penuh** hanya via Mode DevTools. Mode view-source tetap "ter-session" karena request
  dilakukan oleh browser itu sendiri.
- Log konsol halaman hanya tersedia di Mode DevTools & WebView (mode a11y tidak bisa membaca konsol).
- `view-source:` menampilkan HTML sumber; DOM hasil eksekusi JS didapat dari Mode DevTools/WebView.
- Samsung Internet kadang menyembunyikan address bar saat scroll — tekan bubble saat address bar
  terlihat, atau andalkan Mode DevTools.

## Lisensi

MIT — lihat [LICENSE](LICENSE).
