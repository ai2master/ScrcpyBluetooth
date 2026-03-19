package com.scrcpybt.app.ui.clipboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.scrcpybt.common.util.Logger

/**
 * 透明跳板 Activity：用于在后台场景下写入本地剪贴板
 *
 * ### 背景
 * Android 10+ 限制后台应用访问剪贴板，只有前台 Activity 才能读写。
 * 当控制端 App 在后台收到受控端发来的剪贴板内容时，无法直接写入。
 *
 * ### 工作原理
 * 1. 用户点击"收到远端剪贴板"通知
 * 2. 系统启动此透明 Activity（使用 Theme.NoDisplay，用户无感知）
 * 3. Activity 进入前台，此时可以正常写入剪贴板
 * 4. 写入完成后立即 finish()，整个过程对用户几乎不可见
 *
 * ### Intent 参数
 * - `clipboard_text`: String — 要写入本地剪贴板的文本内容
 *
 * @see com.scrcpybt.app.util.NotificationHelper.showClipboardNotification
 */
class ClipboardTrampolineActivity : Activity() {

    companion object {
        private const val TAG = "ClipboardTrampoline"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getStringExtra("clipboard_text")
        if (text != null && text.isNotEmpty()) {
            writeToClipboard(text)
        } else {
            Logger.w(TAG, "No clipboard text in intent")
        }

        finish()
    }

    private fun writeToClipboard(text: String) {
        try {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("remote_clipboard", text)
            clipboardManager.setPrimaryClip(clip)
            Logger.i(TAG, "Clipboard written: ${text.length} chars")
            Toast.makeText(this, "已写入剪贴板 (${text.length} 字符)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to write clipboard", e)
            Toast.makeText(this, "写入剪贴板失败", Toast.LENGTH_SHORT).show()
        }
    }
}
