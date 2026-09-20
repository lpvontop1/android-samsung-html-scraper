package com.qa.samsungscraper.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.qa.samsungscraper.R
import com.qa.samsungscraper.model.CaptureRecord
import com.qa.samsungscraper.util.Util

class CaptureAdapter(
    private var items: List<CaptureRecord>,
    private val onClick: (CaptureRecord) -> Unit,
    private val onLongClick: (CaptureRecord) -> Unit
) : RecyclerView.Adapter<CaptureAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtTitle: TextView = itemView.findViewById(R.id.txtTitle)
        val txtUrl: TextView = itemView.findViewById(R.id.txtUrl)
        val txtTime: TextView = itemView.findViewById(R.id.txtTime)
        val txtModes: TextView = itemView.findViewById(R.id.txtModes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_capture, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.txtTitle.text = item.title.ifBlank { "(tanpa judul)" }
        holder.txtUrl.text = item.url.ifBlank { "-" }
        holder.txtTime.text = Util.fmtTime(item.capturedAt)
        holder.txtModes.text = item.modes.joinToString("+").uppercase()
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    fun submit(newItems: List<CaptureRecord>) {
        items = newItems
        notifyDataSetChanged()
    }
}
