package com.scrcpybt.app.ui.history

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scrcpybt.app.R
import com.scrcpybt.app.history.HistoryDatabase
import com.scrcpybt.app.history.HistoryEntry

/**
 * 历史记录界面：显示所有历史操作记录，支持分类筛选
 *
 * 支持的操作类型：
 * - 剪贴板同步
 * - 文件传输
 * - 文件夹同步
 * - 分享转发
 * - 屏幕镜像
 *
 * 功能：
 * - 按类型筛选历史记录
 * - 查看详细信息（设备名、时间、状态、传输大小等）
 * - 清空全部历史记录
 */
class HistoryActivity : AppCompatActivity() {
    /** 历史记录数据库 */
    private lateinit var historyDb: HistoryDatabase
    /** 历史记录列表视图 */
    private lateinit var recyclerView: RecyclerView
    /** 历史记录适配器 */
    private lateinit var adapter: HistoryAdapter
    /** 类型筛选下拉框 */
    private lateinit var filterSpinner: Spinner
    /** 当前筛选类型 */
    private var currentFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.history_title)

        historyDb = HistoryDatabase(this)

        filterSpinner = findViewById(R.id.filter_spinner)
        recyclerView = findViewById(R.id.history_recycler)

        setupFilterSpinner()
        setupRecyclerView()
        loadHistory()
    }

    /**
     * 设置类型筛选下拉框
     */
    private fun setupFilterSpinner() {
        val filterOptions = listOf(
            getString(R.string.history_filter_all),
            getString(R.string.history_filter_clipboard),
            getString(R.string.history_filter_file_transfer),
            getString(R.string.history_filter_folder_sync),
            getString(R.string.history_filter_share_forward),
            getString(R.string.history_filter_screen_mirror)
        )

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, filterOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        filterSpinner.adapter = spinnerAdapter

        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentFilter = when (position) {
                    0 -> null
                    1 -> HistoryEntry.TYPE_CLIPBOARD
                    2 -> HistoryEntry.TYPE_FILE_TRANSFER
                    3 -> HistoryEntry.TYPE_FOLDER_SYNC
                    4 -> HistoryEntry.TYPE_SHARE_FORWARD
                    5 -> HistoryEntry.TYPE_SCREEN_MIRROR
                    else -> null
                }
                loadHistory()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // 不做任何操作
            }
        }
    }

    /**
     * 设置历史记录列表视图
     */
    private fun setupRecyclerView() {
        adapter = HistoryAdapter(emptyList()) { entry ->
            showDetailsDialog(entry)
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    /**
     * 从数据库加载历史记录并更新列表
     */
    private fun loadHistory() {
        val entries = historyDb.getHistory(type = currentFilter, limit = 100)
        adapter.updateEntries(entries)
    }

    /**
     * 显示历史记录详情对话框
     *
     * @param entry 历史记录条目
     */
    private fun showDetailsDialog(entry: HistoryEntry) {
        val message = buildString {
            append("Type: ${entry.type}\n")
            append("Direction: ${entry.direction}\n")
            append("Device: ${entry.deviceName}\n")
            append("Time: ${formatTimestamp(entry.timestamp)}\n")
            append("Status: ${entry.status}\n")
            if (entry.bytesTransferred > 0) {
                append("Size: ${formatBytes(entry.bytesTransferred)}\n")
            }
            if (entry.fileName != null) {
                append("File: ${entry.fileName}\n")
            }
            if (entry.filePath != null) {
                append("Path: ${entry.filePath}\n")
            }
            if (entry.details.isNotEmpty()) {
                append("\nDetails:\n${entry.details}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.history_details_title))
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * 格式化时间戳为可读字符串
     *
     * @param millis 毫秒时间戳
     * @return 格式化后的时间字符串（yyyy-MM-dd HH:mm:ss）
     */
    private fun formatTimestamp(millis: Long): String {
        val date = java.util.Date(millis)
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return format.format(date)
    }

    /**
     * 格式化字节大小为可读字符串
     *
     * @param bytes 字节数
     * @return 格式化后的大小字符串（B/KB/MB/GB）
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_clear_history -> {
                confirmClearHistory()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 显示清空历史记录的确认对话框
     */
    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_clear_confirm_title)
            .setMessage(R.string.history_clear_confirm_message)
            .setPositiveButton(R.string.history_clear_confirm) { _, _ ->
                historyDb.clearHistory()
                loadHistory()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
