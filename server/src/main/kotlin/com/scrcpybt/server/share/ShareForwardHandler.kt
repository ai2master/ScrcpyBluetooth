package com.scrcpybt.server.share

import com.scrcpybt.common.protocol.message.ShareForwardMessage
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.util.Logger
import java.io.File
import java.io.FileOutputStream

/**
 * Handles receiving shared content and launching target app.
 * Pre-configured target is stored as component name (package/activity).
 */
class ShareForwardHandler(private var targetComponent: String? = null) {

    private val tempFiles = mutableMapOf<Int, File>()
    private val receivePath = File(TEMP_SHARE_DIR)

    init {
        // Ensure temp directory exists
        if (!receivePath.exists()) {
            receivePath.mkdirs()
            Logger.i(TAG, "Created share temp directory: ${receivePath.absolutePath}")
        }
    }

    /**
     * Set the target app component for share forwarding.
     * Format: "com.example.app/.ShareActivity"
     */
    fun setTargetComponent(component: String?) {
        targetComponent = component
        Logger.i(TAG, "Share target set to: $component")
    }

    /**
     * Handle share forward message from controller.
     */
    fun handleShareForward(msg: ShareForwardMessage, writer: MessageWriter) {
        try {
            when (msg.subType) {
                ShareForwardMessage.SUB_SHARE_TEXT -> handleShareText(msg)
                ShareForwardMessage.SUB_SHARE_FILE_BEGIN -> handleFileBegin(msg)
                ShareForwardMessage.SUB_SHARE_FILE_CHUNK -> handleFileChunk(msg)
                ShareForwardMessage.SUB_SHARE_FILE_END -> handleFileEnd(msg)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error handling share forward", e)
        }
    }

    private fun handleShareText(msg: ShareForwardMessage) {
        val text = msg.text ?: run {
            Logger.w(TAG, "Share text message missing text content")
            return
        }

        if (targetComponent == null) {
            Logger.w(TAG, "Share target not configured, cannot forward text")
            return
        }

        Logger.i(TAG, "Forwarding text to $targetComponent: ${text.take(50)}...")

        // 使用 ProcessBuilder 安全传递参数，避免 Shell 命令注入
        execCommand(listOf(
            "am", "start",
            "-a", "android.intent.action.SEND",
            "-t", "text/plain",
            "--es", "android.intent.extra.TEXT", text,
            "-n", targetComponent!!
        ))

        Logger.i(TAG, "Share text intent launched")
    }

    private fun handleFileBegin(msg: ShareForwardMessage) {
        val fileName = msg.fileName ?: "shared_file_${System.currentTimeMillis()}"
        val file = File(receivePath, fileName)
        val outputStream = FileOutputStream(file)

        tempFiles[msg.shareId] = file

        Logger.i(TAG, "Started receiving shared file: $fileName")
    }

    private fun handleFileChunk(msg: ShareForwardMessage) {
        val file = tempFiles[msg.shareId]
        if (file == null) {
            Logger.w(TAG, "Received chunk for unknown share ${msg.shareId}")
            return
        }

        msg.chunkData?.let { data ->
            FileOutputStream(file, true).use { it.write(data) }
            Logger.d(TAG, "Received file chunk: ${data.size} bytes")
        }
    }

    private fun handleFileEnd(msg: ShareForwardMessage) {
        val file = tempFiles.remove(msg.shareId)
        if (file == null) {
            Logger.w(TAG, "Received end for unknown share ${msg.shareId}")
            return
        }

        if (targetComponent == null) {
            Logger.w(TAG, "Share target not configured, file saved but not forwarded: ${file.absolutePath}")
            return
        }

        Logger.i(TAG, "Shared file received: ${file.name}, launching share intent")

        // Get MIME type from file extension
        val mimeType = getMimeType(file.name)

        // 使用 ProcessBuilder 安全传递参数，避免 Shell 命令注入
        execCommand(listOf(
            "am", "start",
            "-a", "android.intent.action.SEND",
            "-t", mimeType,
            "--eu", "android.intent.extra.STREAM", "file://${file.absolutePath}",
            "-n", targetComponent!!,
            "--grant-read-uri-permission"
        ))

        Logger.i(TAG, "Share file intent launched: ${file.absolutePath}")
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /**
     * 安全执行命令：使用 ProcessBuilder 直接传递参数列表，
     * 不经过 Shell 解释器，从根本上防止命令注入攻击。
     */
    private fun execCommand(args: List<String>) {
        try {
            val process = ProcessBuilder(args).start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Logger.w(TAG, "Command exited with code $exitCode: ${args.first()}")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Command exec failed: ${args.first()}", e)
        }
    }

    companion object {
        private const val TAG = "ShareForwardHandler"
        private const val TEMP_SHARE_DIR = "/sdcard/ScrcpyBluetooth/share_temp/"
    }
}
