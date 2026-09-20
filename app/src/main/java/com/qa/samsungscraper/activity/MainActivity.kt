package com.qa.samsungscraper.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qa.samsungscraper.R
import com.qa.samsungscraper.adapter.CaptureAdapter
import com.qa.samsungscraper.cdp.CdpClient
import com.qa.samsungscraper.cdp.CdpTarget
import com.qa.samsungscraper.databinding.ActivityMainBinding
import com.qa.samsungscraper.model.CaptureRecord
import com.qa.samsungscraper.service.ScraperAccessibilityService
import com.qa.samsungscraper.store.CaptureStore
import com.qa.samsungscraper.util.Prefs
import com.qa.samsungscraper.util.Util
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var adapter: CaptureAdapter

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshStatus() }

    private val a11yLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        // Muat pengaturan tersimpan
        binding.edtPort.setText(prefs.getInt(Prefs.PORT, 9333).toString())
        binding.edtCookies.setText(prefs.getString(Prefs.COOKIES_MANUAL, ""))
        binding.swViewSource.isChecked = prefs.getBoolean(Prefs.VIEW_SOURCE, true)
        binding.swCdpReload.isChecked = prefs.getBoolean(Prefs.CDP_RELOAD, false)
        binding.swCdpScreenshot.isChecked = prefs.getBoolean(Prefs.CDP_SCREENSHOT, true)

        adapter = CaptureAdapter(
            items = emptyList(),
            onClick = { rec ->
                startActivity(
                    Intent(this, CaptureDetailActivity::class.java).putExtra("id", rec.id)
                )
            },
            onLongClick = { rec -> confirmDelete(rec) }
        )
        binding.recyclerCaptures.layoutManager = LinearLayoutManager(this)
        binding.recyclerCaptures.adapter = adapter

        // Ijin notifikasi (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.btnA11y.setOnClickListener {
            a11yLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnOverlay.setOnClickListener {
            overlayLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        binding.btnTestDevtools.setOnClickListener { testDevtools() }
        binding.btnDevtoolsCapture.setOnClickListener { startDevtoolsCapture() }
        binding.btnWebViewCapture.setOnClickListener { startWebViewCapture() }
        binding.btnGuide.setOnClickListener { showGuide() }
        binding.btnRefresh.setOnClickListener { refreshList() }

        // Simpan pengaturan saat berubah
        binding.edtPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistSettings()
        }
        binding.edtCookies.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistSettings()
        }
        binding.swViewSource.setOnCheckedChangeListener { _, _ -> persistSettings() }
        binding.swCdpReload.setOnCheckedChangeListener { _, _ -> persistSettings() }
        binding.swCdpScreenshot.setOnCheckedChangeListener { _, _ -> persistSettings() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshList()
    }

    private fun persistSettings() {
        val port = binding.edtPort.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 9333
        prefs.edit()
            .putInt(Prefs.PORT, port)
            .putString(Prefs.COOKIES_MANUAL, binding.edtCookies.text?.toString()?.trim().orEmpty())
            .putBoolean(Prefs.VIEW_SOURCE, binding.swViewSource.isChecked)
            .putBoolean(Prefs.CDP_RELOAD, binding.swCdpReload.isChecked)
            .putBoolean(Prefs.CDP_SCREENSHOT, binding.swCdpScreenshot.isChecked)
            .apply()
    }

    // ---------------- Status ----------------

    private fun refreshStatus() {
        val a11yOk = ScraperAccessibilityService.isServiceEnabled(this)
        setStatus(binding.valA11y, a11yOk, getString(R.string.value_ok), getString(R.string.value_not_yet))
        binding.btnA11y.visibility = if (a11yOk) View.GONE else View.VISIBLE

        val overlayOk = Settings.canDrawOverlays(this)
        setStatus(binding.valOverlay, overlayOk, getString(R.string.value_ok), getString(R.string.value_not_yet))
        binding.btnOverlay.visibility = if (overlayOk) View.GONE else View.VISIBLE

        val browserOk = Util.isSamsungInstalled(this)
        setStatus(binding.valBrowser, browserOk, getString(R.string.value_yes), getString(R.string.value_no))

        // status DevTools diisi oleh tes eksplisit
    }

    private fun setStatus(v: TextView, ok: Boolean, okText: String, badText: String) {
        v.text = if (ok) okText else badText
        v.setTextColor(
            MaterialColors.getColor(
                v,
                if (ok) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorError
            )
        )
    }

    // ---------------- DevTools ----------------

    private fun devtoolsPort(): Int {
        persistSettings()
        return prefs.getInt(Prefs.PORT, 9333)
    }

    private fun testDevtools() {
        val port = devtoolsPort()
        binding.valDevtools.text = "..."
        lifecycleScope.launch {
            try {
                val n = withContext(Dispatchers.IO) { CdpClient.listTargets(port).size }
                setStatus(binding.valDevtools, true, "$n tab", "")
                Util.toast(this@MainActivity, getString(R.string.cdp_ok, n))
            } catch (e: Exception) {
                setStatus(binding.valDevtools, false, "", getString(R.string.value_not_yet))
                binding.valDevtools.text = getString(R.string.value_not_yet)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.status_devtools)
                    .setMessage(getString(R.string.cdp_fail, e.message ?: "?", port.toString()))
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show()
            }
        }
    }

    private fun startDevtoolsCapture() {
        val port = devtoolsPort()
        lifecycleScope.launch {
            var dlg: AlertDialog? = null
            try {
                dlg = showProgress(getString(R.string.cdp_connecting, port))
                val targets = withContext(Dispatchers.IO) { CdpClient.listTargets(port) }
                if (targets.isEmpty()) {
                    throw IllegalStateException("Tidak ada tab (type=page) yang terbuka di Samsung Internet")
                }
                dlg.dismiss()
                val target = pickTarget(targets) ?: return@launch
                val reload = prefs.getBoolean(Prefs.CDP_RELOAD, false)
                val wantShot = prefs.getBoolean(Prefs.CDP_SCREENSHOT, true)
                val dlg2 = showProgress(getString(R.string.cdp_capturing))
                val result = withContext(Dispatchers.IO) {
                    CdpClient(port).use {
                        it.capturePage(
                            target = target,
                            windowMs = if (reload) 1500 else 5000,
                            reload = reload,
                            wantScreenshot = wantShot
                        )
                    }
                }
                dlg2.dismiss()
                val rec = buildCdpRecord(target, result)
                withContext(Dispatchers.IO) { CaptureStore.save(rec) }
                saveAutoCookies(rec)
                refreshList()
                Util.toast(this@MainActivity, getString(R.string.toast_saved, rec.title))
            } catch (ce: CancellationException) {
                dlg?.dismiss()
            } catch (e: Exception) {
                dlg?.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.btn_devtools_capture)
                    .setMessage(getString(R.string.cdp_fail, e.message ?: "?", port.toString()))
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show()
            }
        }
    }

    private suspend fun pickTarget(targets: List<CdpTarget>): CdpTarget? {
        if (targets.size == 1) return targets[0]
        return suspendCancellableCoroutine { cont ->
            val labels = targets.map {
                (it.title.ifBlank { "(tanpa judul)" }).take(50) + " — " + it.url.take(70)
            }.toTypedArray()
            val d = AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.cdp_pick_target)
                .setItems(labels) { _, which -> cont.resume(targets[which]) }
                .setNegativeButton(R.string.dialog_cancel) { dlg, _ ->
                    dlg.dismiss()
                    cont.cancel()
                }
                .create()
            d.show()
            cont.invokeOnCancellation { try { d.dismiss() } catch (_: Exception) {} }
        }
    }

    private fun buildCdpRecord(target: CdpTarget, result: com.qa.samsungscraper.cdp.CdpCaptureResult): CaptureRecord {
        val rec = CaptureRecord.fromSnapshotJson(
            browserPackage = "com.sec.android.app.sbrowser",
            mode = "devtools",
            snapshotJson = result.snapshotJson
        )
        if (rec.url.isBlank()) rec.url = target.url
        if (rec.title.isBlank()) rec.title = target.title.ifBlank { target.url }
        rec.browserPackage = "com.sec.android.app.sbrowser"

        result.cookiesJson?.let { arr ->
            if (arr.length() > 0) {
                // Jar CDP mencakup semua cookie (termasuk HttpOnly) —
                // ganti hasil parse document.cookie agar tidak ganda.
                rec.cookies.clear()
            }
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                rec.cookies.add(
                    com.qa.samsungscraper.model.CookieEntry(
                        name = c.optString("name"),
                        value = c.optString("value"),
                        domain = c.optString("domain").ifBlank { null },
                        path = c.optString("path").ifBlank { null },
                        httpOnly = c.optBoolean("httpOnly", false),
                        secure = c.optBoolean("secure", false)
                    )
                )
            }
        }
        rec.logs.addAll(result.logs)
        rec.network.addAll(result.network)
        result.screenshotPng?.let {
            rec.screenshotPng = it
            rec.hasScreenshot = true
        }
        if (rec.title.isBlank()) rec.title = hostOrNull(rec.url) ?: "(tanpa judul)"
        return rec
    }

    private fun hostOrNull(url: String): String? = com.qa.samsungscraper.model.hostOf(url)

    /** Simpan cookie (yang dapat dibaca) per-host untuk dipakai capture WebView berikutnya. */
    private fun saveAutoCookies(rec: CaptureRecord) {
        if (rec.cookies.isEmpty()) return
        val host = hostOrNull(rec.url) ?: return
        val joined = rec.cookies.joinToString("; ") { "${it.name}=${it.value}" }
        prefs.edit().putString(Prefs.autoCookieKey(host), joined).apply()
    }

    // ---------------- Capture WebView ----------------

    private fun startWebViewCapture() {
        persistSettings()
        var url = binding.edtUrl.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            Util.toast(this, getString(R.string.hint_url))
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        startActivity(
            Intent(this, WebViewCaptureActivity::class.java).putExtra("url", url)
        )
    }

    // ---------------- Lainnya ----------------

    private fun showProgress(msg: String): AlertDialog =
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setCancelable(false)
            .create()
            .also { it.show() }

    private fun confirmDelete(rec: CaptureRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_msg)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { CaptureStore.delete(rec.id) }
                    refreshList()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun refreshList() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { CaptureStore.list() }
            adapter.submit(items)
            binding.txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showGuide() {
        val msg = buildString {
            append("CARA KERJA APLIKASI\n\n")
            append("1) MODE BUBBLE (disarankan)\n")
            append("• Aktifkan Layanan Aksesibilitas + izinkan bubble.\n")
            append("• Buka Samsung Internet → halaman apa pun.\n")
            append("• Tekan bubble melayang: aplikasi membaca URL + konten, lalu (jika diaktifkan) membuka view-source: untuk menyalin HTML sumber lengkap dengan sesi browser, otomatis kembali.\n")
            append("• Untuk halaman lain, navigasikan dulu di browser, lalu tekan bubble lagi.\n\n")
            append("2) MODE DEVTOOLS (paling lengkap, ala F12)\n")
            append("• Di PC: aktifkan USB debugging, lalu jalankan:\n")
            append("adb forward tcp:9333 localabstract:com.sec.android.app.sbrowser_devtools_remote\n")
            append("• Tekan Capture via DevTools: DOM penuh, console log, cookies (termasuk HttpOnly), daftar network, screenshot.\n\n")
            append("3) MODE WEBVIEW\n")
            append("• Masukkan URL → capture via WebView internal. Cookie dapat diisi manual atau dipakai dari hasil DevTools per-host.\n\n")
            append("DATA: tersimpan di penyimpanan internal aplikasi, dapat dilihat/dibagikan sebagai ZIP di layar detail.")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.guide_title)
            .setMessage(msg)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }
}
