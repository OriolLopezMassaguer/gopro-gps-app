package com.example.goprogps.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.goprogps.databinding.ItemVideoBinding
import com.example.goprogps.model.VideoItem
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class VideoAdapter(
    private val items: List<VideoItem>,
    private val onClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    inner class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            tvName.text = item.name
            tvDuration.text = formatDuration(item.durationMs)
            tvSize.text = formatSize(item.sizeBytes)
            tvDate.text = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
                .format(Date(item.dateTaken))
            root.setOnClickListener { onClick(item) }
        }
    }

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1000) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
    }
}
