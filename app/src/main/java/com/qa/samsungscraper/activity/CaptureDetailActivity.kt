package com.qa.samsungscraper.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qa.samsungscraper.R
import com.qa.samsungscraper.databinding.ActivityCaptureDetailBinding
import com.qa.samsungscraper.model.CaptureRecord
import com.qa.samsungscraper.store.CaptureStore
import com.qa.samsungscraper.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Penampil detail capture: DOM, SOURCE, COOKIES, LOGS, NETWORK, STORAGE, A11Y, META.
 * Mendukung salin per-bagian, ekspor ZIP, dan hapus.
 */
class CaptureDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureDetailBinding
    private var rec: CaptureRecord? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra("id").orEmpty()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnCopy.setOnClickListener { copySection() }
        binding.btnZip.setOnClickListener { shareZip() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        binding.webContent.settings.javaScriptEnabled = false
        binding.webContent.settings.loadWithOverviewMode = true

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { CaptureStore.load(id) }
            if (r == null) {
                finish()
                return@launch
            }
            rec = r
            binding.txtTitle.text = r.title.ifBlank { "(tanpa judul)" }
            binding.txtUrl.text = buildString {
                append(r.url.ifBlank { "-" })
                append("  •  ")
                append(r.modes.joinToString("+").uppercase())
                append("  •  ")
                append(Util.fmtTime(r.capturedAt))
            }
            // Sembunyikan tab yang tidak punya data
            binding.tabDom.visibility = if (r.domHtml != null) View.VISIBLE else View.GONE
            binding.tabSource.visibility = if (r.sourceHtml != null) View.VISIBLE else View.GONE
            binding.tabCookies.visibility = if (r.cookies.isNotEmpty() || !r.documentCookie.isNullOrBlank()) View.VISIBLE else View.GONE
            binding.tabLogs.visibility = if (r.logs.isNotEmpty()) View.VISIBLE else View.GONE
            binding.tabNetwork.visibility = if (r.network.isNotEmpty()) View.VISIBLE else View.GONE
            binding.tabStorage.visibility = if (r.localStorage != null || r.sessionStorage != null) View.VISIBLE else View.GONE
            binding.tabA11y.visibility = if (r.a11y != null) View.VISIBLE else View.GONE

            val first = listOf(
                binding.tabDom, binding.tabSource, binding.tabCookies, binding.tabLogs,
                binding.tabNetwork, binding.tabStorage, binding.tabA11y, binding.tabMeta
            ).firstOrNull { it.visibility == View.VISIBLE } ?: binding.tabMeta
            binding.toggleSections.check(first.id)
            render(first.id)
        }

        binding.toggleSections.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) render(checkedId)
        }
    }

    private fun currentSection(): Int {
        val checked = binding.toggleSections.checkedButtonId
        return if (checked != View.NO_ID) checked else binding.tabMeta.id
    }

    private fun render(sectionId: Int) {
        val r = rec ?: return
        when (sectionId) {
            binding.tabDom.id -> renderHtml(r.domHtml)
            binding.tabSource.id -> renderHtml(r.sourceHtml)
            else -> {
                binding.webContent.visibility = View.GONE
                binding.scrollText.visibility = View.VISIBLE
                binding.txtContent.text = when (sectionId) {
                    binding.tabCookies.id -> renderCookies(r)
                    binding.tabLogs.id -> renderLogs(r)
                    binding.tabNetwork.id -> renderNetwork(r)
                    binding.tabStorage.id -> renderStorage(r)
                    binding.tabA11y.id -> renderA11y(r)
                    else -> renderMeta(r)
                }
            }
        }
    }

    private fun renderHtml(html: String?) {
        val content = html ?: return
        binding.scrollText.visibility = View.GONE
        binding.webContent.visibility = View.VISIBLE
        try {
            binding.webContent.loadDataWithBaseURL(
                rec?.url, content, "text/html", "utf-8", null
            )
        } catch (e: Exception) {
            binding.webContent.visibility = View.GONE
            binding.scrollText.visibility = View.VISIBLE
            binding.txtContent.text = content.take(200_000)
        }
    }

    private fun renderCookies(r: CaptureRecord): String {
        val arr = JSONArray()
        for (c in r.cookies) arr.put(c.toJson())
        val o = JSONObject()
            .put("cookies", arr)
            .putOpt("documentCookie_raw", r.documentCookie)
            .put("count", r.cookies.size)
        return o.toString(2)
    }

    private fun renderLogs(r: CaptureRecord): String {
        if (r.logs.isEmpty()) return "(tidak ada log)"
        val sb = StringBuilder()
        for (l in r.logs) {
            sb.append('[')
            sb.append(Util.fmtTime(l.time))
            sb.append("][")
            sb.append(l.level.uppercase())
            l.source?.let { sb.append("]["); sb.append(it) }
            sb.append("] ")
            sb.append(l.text)
            sb.append('\n')
        }
        return sb.toString().take(1_000_000)
    }

    private fun renderNetwork(r: CaptureRecord): String {
        if (r.network.isEmpty()) return "(tidak ada entri jaringan)"
        val sb = StringBuilder()
        for (n in r.network) {
            sb.append(n.method ?: "GET")
            sb.append(' ')
            sb.append(n.url.take(300))
            val parts = mutableListOf<String>()
            val st = n.status
            if (st != null) {
                if (st == -1) parts.add("GAGAL") else parts.add("HTTP $st")
            }
            n.mimeType?.let { parts.add(it) }
            n.durationMs?.let { parts.add("${it}ms") }
            n.size?.let { parts.add("${it}B") }
            if (parts.isNotEmpty()) {
                sb.append("  →  ")
                sb.append(parts.joinToString(", "))
            }
            sb.append('\n')
        }
        return sb.toString().take(1_000_000)
    }

    private fun renderStorage(r: CaptureRecord): String {
        val o = JSONObject()
        try {
            o.put("localStorage", if (r.localStorage != null) JSONObject(r.localStorage!!) else JSONObject.NULL)
        } catch (e: Exception) {
            o.put("localStorage_raw", r.localStorage ?: "")
        }
        try {
            o.put("sessionStorage", if (r.sessionStorage != null) JSONObject(r.sessionStorage!!) else JSONObject.NULL)
        } catch (e: Exception) {
            o.put("sessionStorage_raw", r.sessionStorage ?: "")
        }
        return o.toString(2)
    }

    private fun renderA11y(r: CaptureRecord): String {
        val snap = r.a11y ?: return "(tidak ada)"
        return snap.toJson().toString(2).take(1_000_000)
    }

    private fun renderMeta(r: CaptureRecord): String {
        val meta = r.toJsonMeta()
        meta.put("catatan", "Sesi & cookie: mode devtools membaca cookie penuh (termasuk HttpOnly); mode view-source menggunakan sesi browser saat request; mode a11y/webview terbatas.")
        return meta.toString(2)
    }

    private fun copySection() {
        val r = rec ?: return
        val id = currentSection()
        val text = when (id) {
            binding.tabDom.id -> r.domHtml
            binding.tabSource.id -> r.sourceHtml
            binding.tabCookies.id -> renderCookies(r)
            binding.tabLogs.id -> renderLogs(r)
            binding.tabNetwork.id -> renderNetwork(r)
            binding.tabStorage.id -> renderStorage(r)
            binding.tabA11y.id -> renderA11y(r)
            else -> renderMeta(r)
        }
        Util.copyClipboard(this, text ?: "")
        Util.toast(this, getString(R.string.toast_copied))
    }

    private fun shareZip() {
        val r = rec ?: return
        try {
            Util.shareZip(this, CaptureStore.dirOf(r.id), r.id)
        } catch (e: Exception) {
            Util.toast(this, e.message ?: "gagal membuat ZIP")
        }
    }

    private fun confirmDelete() {
        val r = rec ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_msg)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { CaptureStore.delete(r.id) }
                    finish()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    override fun onDestroy() {
        binding.webContent.destroy()
        super.onDestroy()
    }
}
