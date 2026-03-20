package com.scrcpybt.app.handler

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.scrcpybt.app.history.HistoryDatabase
import com.scrcpybt.app.history.HistoryEntry
import com.scrcpybt.common.protocol.message.ClipboardMessage
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.util.Logger
import java.io.IOException

/**
 * 剪贴板传输处理器：处理应用端（控制端）的剪贴板传输逻辑 | Clipboard Transfer Handler: Handles clipboard transfer logic on the app (controller) side
 *
 * 功能 | Features:
 * - 发送本地剪贴板到远程设备 | Send local clipboard to remote device
 * - 请求远程设备的剪贴板内容 | Request clipboard content from remote device
 * - 接收远程剪贴板并写入本地 | Receive remote clipboard and write to local
 * - 历史记录集成 | History recording integration
 *
 * 使用场景 | Use Cases:
 * - 用户手动同步剪贴板 | User manually syncs clipboard
 * - 自动剪贴板同步（如果启用）| Automatic clipboard sync (if enabled)
 * - 分享文本到远程设备 | Share text to remote device
 *
 * 安全注意 | Security Notes:
 * - 控制端只在主动请求后才接受远程剪贴板 | Controller only accepts remote clipboard after explicit request
 * - 防止未经授权的剪贴板写入 | Prevents unauthorized clipboard writes
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
object ClipboardTransferHandler {
    private const val TAG = "ClipboardHandler"
    /** 历史记录数据库 | History database */
    private var historyDb: HistoryDatabase? = null

    /**
     * 设置历史记录数据库 | Set history database
     * @param db 历史数据库实例 | History database instance
     */
    fun setHistoryDatabase(db: HistoryDatabase) {
        historyDb = db
    }

    /**
     * 发送本地剪贴板到远程设备 | Send local clipboard to remote device
     *
     * @param text 要发送的文本内容 | Text content to send
     * @param writer 消息写入器 | Message writer
     * @param deviceName 远程设备名称（用于历史记录）| Remote device name (for history)
     */
    fun sendClipboard(text: String, writer: MessageWriter, deviceName: String = "Remote") {
        try {
            val msg = ClipboardMessage(text, ClipboardMessage.DIR_PUSH)
            writer.writeMessage(msg)
            Logger.i(TAG, "Clipboard sent: ${text.length} chars")

            // Record history
            historyDb?.recordClipboard(
                direction = HistoryEntry.DIR_SEND,
                deviceName = deviceName,
                charCount = text.length,
                status = HistoryEntry.STATUS_SUCCESS
            )
        } catch (e: IOException) {
            Logger.e(TAG, "Failed to send clipboard", e)
            historyDb?.recordClipboard(
                direction = HistoryEntry.DIR_SEND,
                deviceName = deviceName,
                charCount = text.length,
                status = HistoryEntry.STATUS_FAILED
            )
        }
    }

    /**
     * 请求远程设备的剪贴板内容 | Request clipboard from remote device
     *
     * 发送 DIR_PULL 消息，远程设备收到后会回复其剪贴板内容 | Send DIR_PULL message, remote device will reply with its clipboard
     *
     * @param writer 消息写入器 | Message writer
     */
    fun requestClipboard(writer: MessageWriter) {
        try {
            val msg = ClipboardMessage("", ClipboardMessage.DIR_PULL)
            writer.writeMessage(msg)
            Logger.i(TAG, "Clipboard request sent")
        } catch (e: IOException) {
            Logger.e(TAG, "Failed to request clipboard", e)
        }
    }

    /**
     * 处理从远程设备接收的剪贴板内容 | Handle incoming clipboard content from remote device
     *
     * 将接收到的文本写入本地剪贴板并记录历史 | Write received text to local clipboard and record history
     *
     * @param msg 剪贴板消息 | Clipboard message
     * @param context Android 上下文（用于访问剪贴板服务）| Android context (for accessing clipboard service)
     * @param deviceName 远程设备名称（用于历史记录）| Remote device name (for history)
     */
    fun handleIncoming(msg: ClipboardMessage, context: Context, deviceName: String = "Remote") {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("remote_clipboard", msg.text)
            clipboardManager.setPrimaryClip(clip)
            Logger.i(TAG, "Clipboard received and set: ${msg.text.length} chars")

            // Record history
            historyDb?.recordClipboard(
                direction = HistoryEntry.DIR_RECEIVE,
                deviceName = deviceName,
                charCount = msg.text.length,
                status = HistoryEntry.STATUS_SUCCESS
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to set clipboard", e)
            historyDb?.recordClipboard(
                direction = HistoryEntry.DIR_RECEIVE,
                deviceName = deviceName,
                charCount = msg.text.length,
                status = HistoryEntry.STATUS_FAILED
            )
        }
    }
}
