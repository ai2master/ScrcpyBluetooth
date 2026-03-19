package com.scrcpybt.app.ui.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scrcpybt.app.R
import com.scrcpybt.app.service.ControllerService
import com.scrcpybt.common.protocol.message.ClipboardMessage
import com.scrcpybt.common.protocol.message.ShareForwardMessage
import com.scrcpybt.common.util.Logger

/**
 * 分享接收 Activity：拦截 Android 系统分享意图（ACTION_SEND）
 *
 * 当用户在任意应用中选择"分享到 ScrcpyBluetooth"时，此 Activity 会被启动。
 *
 * ### 分享类型处理
 * - **text/plain**: 作为剪贴板内容发送（ClipboardMessage），这是"控制端 → 受控端"
 *   剪贴板传递的主路径。用户在任意 App 中复制文本后，通过分享菜单将文本
 *   发送到受控端的剪贴板。
 * - **其他类型**: 作为文件分享转发（ShareForwardMessage），在受控端打开对应应用。
 *
 * ### 连接方式
 * 通过 bindService 绑定到 ControllerService，获取加密通道发送消息。
 * 如果 ControllerService 未运行或未连接，提示用户先建立连接。
 *
 * @see ControllerService
 * @see ClipboardMessage
 * @see ShareForwardMessage
 */
class ShareReceiverActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ShareReceiver"
    }

    private lateinit var tvShareContent: TextView
    private var controllerService: ControllerService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ControllerService.LocalBinder
            controllerService = binder?.getService()
            bound = true
            Logger.i(TAG, "Bound to ControllerService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controllerService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_receiver)

        tvShareContent = findViewById(R.id.tv_share_content)
        val btnCancel = findViewById<Button>(R.id.btn_cancel)
        val btnSend = findViewById<Button>(R.id.btn_send)

        // 绑定到 ControllerService
        val serviceIntent = Intent(this, ControllerService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 处理分享意图
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    handleSendText(intent)
                } else {
                    handleSendFile(intent)
                }
            }
            else -> {
                Toast.makeText(this, "Invalid share action", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        btnCancel.setOnClickListener {
            finish()
        }

        btnSend.setOnClickListener {
            sendToRemote()
        }
    }

    private fun handleSendText(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text != null) {
            tvShareContent.text = "文本: $text"
        } else {
            tvShareContent.text = "(空文本)"
        }
    }

    private fun handleSendFile(intent: Intent) {
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        if (uri != null) {
            val fileName = uri.lastPathSegment ?: "unknown"
            tvShareContent.text = "文件: $fileName\nURI: $uri"
        } else {
            tvShareContent.text = "(无文件)"
        }
    }

    private fun sendToRemote() {
        val service = controllerService
        if (service == null) {
            Toast.makeText(this, "未连接到远端设备", Toast.LENGTH_SHORT).show()
            return
        }

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (text != null) {
                        sendTextAsClipboard(service, text)
                    }
                } else {
                    val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        sendFileShare(service, uri)
                    }
                }
            }
        }
    }

    /**
     * 将文本作为剪贴板消息发送到受控端
     *
     * 这是控制端 → 受控端剪贴板传递的主路径：
     * 用户在任意 App 选中文本 → 分享 → ScrcpyBluetooth → 受控端剪贴板
     */
    private fun sendTextAsClipboard(service: ControllerService, text: String) {
        service.sendClipboard(text)
        Toast.makeText(this, "已发送到远端剪贴板 (${text.length} 字符)", Toast.LENGTH_SHORT).show()
        Logger.i(TAG, "Text shared as clipboard: ${text.length} chars")
        finish()
    }

    /**
     * 将文件作为分享转发消息发送到受控端
     */
    private fun sendFileShare(service: ControllerService, uri: android.net.Uri) {
        try {
            val mimeType = contentResolver.getType(uri) ?: "*/*"
            val fileName = uri.lastPathSegment ?: "shared_file"

            // 读取文件大小
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show()
                return
            }

            val fileBytes = inputStream.readBytes()
            inputStream.close()

            val shareId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

            // 发送 FILE_BEGIN
            val beginMsg = ShareForwardMessage(
                subType = ShareForwardMessage.SUB_SHARE_FILE_BEGIN,
                shareId = shareId,
                mimeType = mimeType,
                fileName = fileName,
                fileSize = fileBytes.size.toLong(),
                totalFiles = 1,
                fileIndex = 0
            )
            service.sendMessage(beginMsg)

            // 分块发送
            val chunkSize = 32 * 1024 // 32KB
            var offset = 0
            while (offset < fileBytes.size) {
                val end = minOf(offset + chunkSize, fileBytes.size)
                val chunk = fileBytes.copyOfRange(offset, end)
                val chunkMsg = ShareForwardMessage(
                    subType = ShareForwardMessage.SUB_SHARE_FILE_CHUNK,
                    shareId = shareId,
                    chunkData = chunk
                )
                service.sendMessage(chunkMsg)
                offset = end
            }

            // 发送 FILE_END
            val endMsg = ShareForwardMessage(
                subType = ShareForwardMessage.SUB_SHARE_FILE_END,
                shareId = shareId
            )
            service.sendMessage(endMsg)

            // 发送 COMPLETE
            val completeMsg = ShareForwardMessage(
                subType = ShareForwardMessage.SUB_SHARE_COMPLETE,
                shareId = shareId
            )
            service.sendMessage(completeMsg)

            Toast.makeText(this, "文件已发送 ($fileName)", Toast.LENGTH_SHORT).show()
            Logger.i(TAG, "File shared: $fileName, ${fileBytes.size} bytes")
            finish()

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to send file share", e)
            Toast.makeText(this, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }
}
