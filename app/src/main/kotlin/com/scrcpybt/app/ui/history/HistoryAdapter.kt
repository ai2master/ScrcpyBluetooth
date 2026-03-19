package com.scrcpybt.app.ui.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.scrcpybt.app.R
import com.scrcpybt.app.history.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for history entries.
 */
class HistoryAdapter(
    private var entries: List<HistoryEntry>,
    private val onItemClick: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconView: ImageView = view.findViewById(R.id.history_icon)
        val titleView: TextView = view.findViewById(R.id.history_title)
        val descriptionView: TextView = view.findViewById(R.id.history_description)
        val timestampView: TextView = view.findViewById(R.id.history_timestamp)
        val statusBadge: TextView = view.findViewById(R.id.history_status_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val entry = entries[position]
        val context = holder.itemView.context

        // Set icon based on type
        val iconRes = when (entry.type) {
            HistoryEntry.TYPE_CLIPBOARD -> android.R.drawable.ic_menu_edit
            HistoryEntry.TYPE_FILE_TRANSFER -> android.R.drawable.ic_menu_save
            HistoryEntry.TYPE_FOLDER_SYNC -> android.R.drawable.ic_menu_rotate
            HistoryEntry.TYPE_SHARE_FORWARD -> android.R.drawable.ic_menu_share
            HistoryEntry.TYPE_SCREEN_MIRROR -> android.R.drawable.ic_menu_view
            else -> android.R.drawable.ic_dialog_info
        }
        holder.iconView.setImageResource(iconRes)

        // Set title
        val title = when (entry.type) {
            HistoryEntry.TYPE_CLIPBOARD -> "Clipboard"
            HistoryEntry.TYPE_FILE_TRANSFER -> "File Transfer"
            HistoryEntry.TYPE_FOLDER_SYNC -> "Folder Sync"
            HistoryEntry.TYPE_SHARE_FORWARD -> "Share Forward"
            HistoryEntry.TYPE_SCREEN_MIRROR -> "Screen Mirror"
            else -> entry.type
        }
        holder.titleView.text = title

        // Set description
        val description = buildString {
            append(entry.direction.replaceFirstChar { it.uppercase() })
            if (entry.deviceName.isNotEmpty()) {
                append(" • ${entry.deviceName}")
            }
            if (entry.fileName != null) {
                append(" • ${entry.fileName}")
            } else if (entry.bytesTransferred > 0) {
                append(" • ${formatBytes(entry.bytesTransferred)}")
            }
        }
        holder.descriptionView.text = description

        // Set timestamp
        holder.timestampView.text = formatTimestamp(entry.timestamp)

        // Set status badge
        holder.statusBadge.text = entry.status
        val statusColor = when (entry.status) {
            HistoryEntry.STATUS_SUCCESS -> android.R.color.holo_green_dark
            HistoryEntry.STATUS_FAILED -> android.R.color.holo_red_dark
            HistoryEntry.STATUS_INTERRUPTED -> android.R.color.holo_orange_dark
            else -> android.R.color.darker_gray
        }
        holder.statusBadge.setTextColor(context.getColor(statusColor))

        // Set click listener
        holder.itemView.setOnClickListener {
            onItemClick(entry)
        }
    }

    override fun getItemCount(): Int = entries.size

    fun updateEntries(newEntries: List<HistoryEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    private fun formatTimestamp(millis: Long): String {
        val date = Date(millis)
        val now = System.currentTimeMillis()
        val diff = now - millis

        return when {
            diff < 60000 -> "Just now"
            diff < 3600000 -> "${diff / 60000}m ago"
            diff < 86400000 -> "${diff / 3600000}h ago"
            else -> SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(date)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
