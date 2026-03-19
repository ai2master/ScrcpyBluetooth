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
 * Handles clipboard transfer logic on the app (controller) side.
 */
object ClipboardTransferHandler {
    private const val TAG = "ClipboardHandler"
    private var historyDb: HistoryDatabase? = null

    fun setHistoryDatabase(db: HistoryDatabase) {
        historyDb = db
    }

    /**
     * Send local clipboard to remote device.
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
     * Request clipboard from remote device.
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
     * Handle incoming clipboard content from remote device.
     * Sets the local clipboard with the received text.
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
