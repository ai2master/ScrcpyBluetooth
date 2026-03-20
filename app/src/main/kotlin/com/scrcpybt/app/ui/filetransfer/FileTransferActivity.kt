package com.scrcpybt.app.ui.filetransfer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scrcpybt.app.R
import com.scrcpybt.app.handler.FileTransferController

/**
 * 文件传输界面：设备间单个/多个文件和文件夹传输
 * File Transfer Activity: Single/multiple files and folder transfer between devices
 *
 * ### 核心功能 | Core Features
 * - 单文件选择和传输 | Single file selection and transfer
 * - 多文件批量传输 | Multiple files batch transfer
 * - 文件夹递归传输（规划中）| Folder recursive transfer (planned)
 * - 传输进度实时显示 | Real-time transfer progress display
 * - 传输队列管理 | Transfer queue management
 *
 * ### 使用场景 | Use Cases
 * - 快速发送照片、文档到另一台设备 | Quickly send photos and documents to another device
 * - 批量传输媒体文件 | Batch transfer media files
 * - 跨设备工作文件同步 | Cross-device work file synchronization
 *
 * ### 技术实现 | Technical Implementation
 * - 使用 Android Storage Access Framework (SAF) 选择文件 | Use Android SAF for file selection
 * - 通过 FileTransferController 管理传输队列 | Manage transfer queue via FileTransferController
 * - RecyclerView 显示传输列表和实时进度 | RecyclerView displays transfer list and real-time progress
 * - 支持前台/后台传输（TODO）| Support foreground/background transfer (TODO)
 *
 * @see FileTransferController
 */
class FileTransferActivity : AppCompatActivity() {
    companion object {
        /** 文件选择请求码 | File selection request code */
        private const val REQUEST_SELECT_FILE = 1001
        /** 文件夹选择请求码 | Folder selection request code */
        private const val REQUEST_SELECT_FOLDER = 1002
    }

    /** 传输列表视图 | Transfer list view */
    private lateinit var rvTransfers: RecyclerView
    /** 整体进度条 | Overall progress bar */
    private lateinit var progressOverall: ProgressBar
    /** 状态文本 | Status text */
    private lateinit var tvStatus: TextView
    /** 文件传输控制器 | File transfer controller */
    private lateinit var transferController: FileTransferController
    /** 传输列表适配器 | Transfer list adapter */
    private lateinit var adapter: TransferAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_transfer)

        supportActionBar?.apply {
            title = "File Transfer"
            setDisplayHomeAsUpEnabled(true)
        }

        val btnSelectFile = findViewById<Button>(R.id.btn_select_files)
        val btnSelectFolder = findViewById<Button>(R.id.btn_select_folder)
        rvTransfers = findViewById(R.id.rv_transfers)
        progressOverall = findViewById(R.id.progress_overall)
        tvStatus = findViewById(R.id.tv_status)

        transferController = FileTransferController(this)
        adapter = TransferAdapter(mutableListOf())

        rvTransfers.layoutManager = LinearLayoutManager(this)
        rvTransfers.adapter = adapter

        // 选择文件按钮：支持单文件和多文件选择 | File selection button: supports single and multiple files
        btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(intent, REQUEST_SELECT_FILE)
        }

        // 选择文件夹按钮：递归传输整个文件夹（待实现）| Folder selection button: recursive folder transfer (TODO)
        btnSelectFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, REQUEST_SELECT_FOLDER)
        }

        // 注册传输监听器，实时更新UI | Register transfer listener for real-time UI updates
        transferController.addListener(object : FileTransferController.TransferListener {
            override fun onTransferStarted(item: FileTransferController.TransferItem) {
                runOnUiThread {
                    adapter.notifyDataSetChanged()
                    tvStatus.text = "Transferring: ${item.name}"
                }
            }

            override fun onTransferProgress(item: FileTransferController.TransferItem, progress: Int) {
                runOnUiThread {
                    adapter.notifyDataSetChanged()
                    progressOverall.progress = progress
                }
            }

            override fun onTransferComplete(item: FileTransferController.TransferItem) {
                runOnUiThread {
                    adapter.notifyDataSetChanged()
                    tvStatus.text = "Transfer complete: ${item.name}"
                }
            }

            override fun onTransferError(item: FileTransferController.TransferItem, error: String) {
                runOnUiThread {
                    adapter.notifyDataSetChanged()
                    tvStatus.text = "Error: $error"
                }
            }
        })

        tvStatus.text = "Ready"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK && data != null) {
            when (requestCode) {
                REQUEST_SELECT_FILE -> {
                    val clipData = data.clipData
                    if (clipData != null) {
                        // Multiple files selected
                        for (i in 0 until clipData.itemCount) {
                            val uri = clipData.getItemAt(i).uri
                            queueFile(uri)
                        }
                    } else {
                        // Single file selected
                        data.data?.let { queueFile(it) }
                    }
                }
                REQUEST_SELECT_FOLDER -> {
                    data.data?.let { folderUri ->
                        Toast.makeText(this, "Folder transfer not yet implemented", Toast.LENGTH_SHORT).show()
                        // TODO: Implement folder traversal and queuing
                    }
                }
            }
        }
    }

    /**
     * 将文件加入传输队列
     * Queue file for transfer
     *
     * @param uri 文件 URI（由 SAF 返回）| File URI (returned by SAF)
     */
    private fun queueFile(uri: Uri) {
        val item = transferController.queueFile(uri)
        adapter.addItem(item)
        tvStatus.text = "Queued: ${item.name}"
        Toast.makeText(this, "File queued: ${item.name}", Toast.LENGTH_SHORT).show()

        // TODO: 通过活跃连接实际发送文件 | Actually send the file through the active connection
        // transferController.sendFile(item, writer)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * 传输列表适配器：显示传输项的进度和状态
     * Transfer list adapter: displays progress and status of transfer items
     *
     * @property items 传输项列表 | List of transfer items
     */
    class TransferAdapter(private val items: MutableList<FileTransferController.TransferItem>) :
        RecyclerView.Adapter<TransferAdapter.ViewHolder>() {

        /**
         * ViewHolder：缓存传输项视图控件
         * ViewHolder: caches transfer item view controls
         */
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvFileName: TextView = view.findViewById(R.id.tv_file_name)
            val tvFileSize: TextView = view.findViewById(R.id.tv_file_size)
            val progressFile: ProgressBar = view.findViewById(R.id.progress_file)
            val tvStatus: TextView = view.findViewById(R.id.tv_status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transfer, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvFileName.text = item.name
            holder.tvFileSize.text = formatSize(item.size)
            holder.progressFile.progress = item.progress
            holder.tvStatus.text = item.status.name
        }

        override fun getItemCount(): Int = items.size

        /**
         * 添加传输项到列表
         * Add transfer item to list
         *
         * @param item 传输项 | Transfer item
         */
        fun addItem(item: FileTransferController.TransferItem) {
            items.add(item)
            notifyItemInserted(items.size - 1)
        }

        /**
         * 格式化字节大小为可读字符串
         * Format byte size to readable string
         *
         * @param bytes 字节数 | Byte count
         * @return 格式化后的字符串（B/KB/MB）| Formatted string (B/KB/MB)
         */
        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> "${bytes / (1024 * 1024)} MB"
            }
        }
    }
}
