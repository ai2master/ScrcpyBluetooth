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
 * 历史记录列表适配器：显示历史操作记录
 * History list adapter: displays historical operation records
 *
 * ### 功能 | Features
 * - 根据操作类型显示不同图标 | Display different icons based on operation type
 * - 人性化的时间显示（刚才、X分钟前、X小时前）| Human-friendly time display (just now, X minutes ago, X hours ago)
 * - 状态徽章颜色区分（成功/失败/中断）| Status badge color differentiation (success/failed/interrupted)
 * - 点击条目查看详情 | Click item to view details
 *
 * @property entries 历史记录列表 | History entry list
 * @property onItemClick 条目点击回调 | Item click callback
 */
class HistoryAdapter(
    private var entries: List<HistoryEntry>,
    private val onItemClick: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    /**
     * ViewHolder：缓存历史记录条目的视图控件
     * ViewHolder: caches view controls of history entry
     */
    class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        /** 类型图标 | Type icon */
        val iconView: ImageView = view.findViewById(R.id.history_icon)
        /** 标题文本 | Title text */
        val titleView: TextView = view.findViewById(R.id.history_title)
        /** 描述文本（方向、设备名、文件名）| Description text (direction, device name, file name) */
        val descriptionView: TextView = view.findViewById(R.id.history_description)
        /** 时间戳文本 | Timestamp text */
        val timestampView: TextView = view.findViewById(R.id.history_timestamp)
        /** 状态徽章（成功/失败/中断）| Status badge (success/failed/interrupted) */
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

        // 根据操作类型设置图标 | Set icon based on operation type
        val iconRes = when (entry.type) {
            HistoryEntry.TYPE_CLIPBOARD -> android.R.drawable.ic_menu_edit
            HistoryEntry.TYPE_FILE_TRANSFER -> android.R.drawable.ic_menu_save
            HistoryEntry.TYPE_FOLDER_SYNC -> android.R.drawable.ic_menu_rotate
            HistoryEntry.TYPE_SHARE_FORWARD -> android.R.drawable.ic_menu_share
            HistoryEntry.TYPE_SCREEN_MIRROR -> android.R.drawable.ic_menu_view
            else -> android.R.drawable.ic_dialog_info
        }
        holder.iconView.setImageResource(iconRes)

        // 设置标题 | Set title
        val title = when (entry.type) {
            HistoryEntry.TYPE_CLIPBOARD -> "Clipboard"
            HistoryEntry.TYPE_FILE_TRANSFER -> "File Transfer"
            HistoryEntry.TYPE_FOLDER_SYNC -> "Folder Sync"
            HistoryEntry.TYPE_SHARE_FORWARD -> "Share Forward"
            HistoryEntry.TYPE_SCREEN_MIRROR -> "Screen Mirror"
            else -> entry.type
        }
        holder.titleView.text = title

        // 构建描述信息：方向 + 设备名 + 文件名/大小 | Build description: direction + device name + file name/size
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

        // 设置人性化时间戳 | Set human-friendly timestamp
        holder.timestampView.text = formatTimestamp(entry.timestamp)

        // 设置状态徽章及颜色 | Set status badge and color
        holder.statusBadge.text = entry.status
        val statusColor = when (entry.status) {
            HistoryEntry.STATUS_SUCCESS -> android.R.color.holo_green_dark
            HistoryEntry.STATUS_FAILED -> android.R.color.holo_red_dark
            HistoryEntry.STATUS_INTERRUPTED -> android.R.color.holo_orange_dark
            else -> android.R.color.darker_gray
        }
        holder.statusBadge.setTextColor(context.getColor(statusColor))

        // 设置点击监听器 | Set click listener
        holder.itemView.setOnClickListener {
            onItemClick(entry)
        }
    }

    override fun getItemCount(): Int = entries.size

    /**
     * 更新历史记录列表
     * Update history entry list
     *
     * @param newEntries 新的历史记录列表 | New history entry list
     */
    fun updateEntries(newEntries: List<HistoryEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    /**
     * 格式化时间戳为人性化字符串
     * Format timestamp to human-friendly string
     *
     * - 1分钟内：刚才 | Within 1 minute: Just now
     * - 1小时内：X分钟前 | Within 1 hour: X minutes ago
     * - 24小时内：X小时前 | Within 24 hours: X hours ago
     * - 更早：MMM dd, HH:mm | Earlier: MMM dd, HH:mm
     *
     * @param millis 毫秒时间戳 | Millisecond timestamp
     * @return 格式化后的时间字符串 | Formatted time string
     */
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

    /**
     * 格式化字节大小为可读字符串
     * Format byte size to readable string
     *
     * @param bytes 字节数 | Byte count
     * @return 格式化后的大小字符串（B/KB/MB/GB）| Formatted size string (B/KB/MB/GB)
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
