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
 * Activity for file and folder transfer between devices.
 */
class FileTransferActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_SELECT_FILE = 1001
        private const val REQUEST_SELECT_FOLDER = 1002
    }

    private lateinit var rvTransfers: RecyclerView
    private lateinit var progressOverall: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var transferController: FileTransferController
    private lateinit var adapter: TransferAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_transfer)

        supportActionBar?.apply {
            title = "File Transfer"
            setDisplayHomeAsUpEnabled(true)
        }

        val btnSelectFile = findViewById<Button>(R.id.btn_select_file)
        val btnSelectFolder = findViewById<Button>(R.id.btn_select_folder)
        rvTransfers = findViewById(R.id.rv_transfers)
        progressOverall = findViewById(R.id.progress_overall)
        tvStatus = findViewById(R.id.tv_status)

        transferController = FileTransferController(this)
        adapter = TransferAdapter(mutableListOf())

        rvTransfers.layoutManager = LinearLayoutManager(this)
        rvTransfers.adapter = adapter

        btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(intent, REQUEST_SELECT_FILE)
        }

        btnSelectFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, REQUEST_SELECT_FOLDER)
        }

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

    private fun queueFile(uri: Uri) {
        val item = transferController.queueFile(uri)
        adapter.addItem(item)
        tvStatus.text = "Queued: ${item.name}"
        Toast.makeText(this, "File queued: ${item.name}", Toast.LENGTH_SHORT).show()

        // TODO: Actually send the file through the active connection
        // transferController.sendFile(item, writer)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class TransferAdapter(private val items: MutableList<FileTransferController.TransferItem>) :
        RecyclerView.Adapter<TransferAdapter.ViewHolder>() {

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

        fun addItem(item: FileTransferController.TransferItem) {
            items.add(item)
            notifyItemInserted(items.size - 1)
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> "${bytes / (1024 * 1024)} MB"
            }
        }
    }
}
