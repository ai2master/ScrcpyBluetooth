package com.scrcpybt.app.ui.textaction

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import com.scrcpybt.app.service.ControllerService
import com.scrcpybt.common.util.Logger

/**
 * 文本选中操作条处理器：拦截 PROCESS_TEXT 意图
 *
 * 当用户在任意应用中选中文本后，系统浮动工具栏会显示"发送到远端剪贴板"选项。
 * 点击后启动此 Activity，将选中的文本发送到受控端的剪贴板。
 *
 * ### 工作原理
 * 1. 用户在任意 App 中选中一段文本
 * 2. 系统浮动工具栏出现"发送到远端剪贴板"
 * 3. 点击 → 启动此 Activity
 * 4. 绑定 ControllerService，获取加密通道
 * 5. 通过 ClipboardMessage(DIR_PUSH) 发送到受控端
 * 6. 显示 Toast 提示，立即 finish()
 *
 * ### Android 版本要求
 * - PROCESS_TEXT 需要 Android 6.0+ (API 23+)
 * - 组件可通过 ShortcutHelper.setTextActionEnabled() 启用/禁用
 *
 * ### 可禁用
 * 用户可在快捷方式设置中禁用此入口，禁用后不再出现在文本选中操作条中。
 *
 * @see com.scrcpybt.app.util.ShortcutHelper
 */
class SendTextToRemoteActivity : Activity() {

    companion object {
        private const val TAG = "SendTextToRemote"
    }

    private var controllerService: ControllerService? = null
    private var pendingText: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ControllerService.LocalBinder
            controllerService = binder?.getService()

            // Service 已连接，发送文本
            pendingText?.let { text ->
                sendText(text)
                pendingText = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controllerService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "未获取到选中文本", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 尝试绑定 ControllerService
        pendingText = text
        val serviceIntent = Intent(this, ControllerService::class.java)
        val bound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        if (!bound) {
            Toast.makeText(this, "未连接到远端设备", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 如果 service 已经连接（同步回调），上面 onServiceConnected 会处理
        // 否则等待异步回调
    }

    private fun sendText(text: String) {
        val service = controllerService
        if (service == null) {
            Toast.makeText(this, "未连接到远端设备", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        service.sendClipboard(text)
        Logger.i(TAG, "Text sent to remote clipboard: ${text.length} chars")
        Toast.makeText(this, "已发送到远端剪贴板 (${text.length} 字符)", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        try {
            unbindService(serviceConnection)
        } catch (ignored: IllegalArgumentException) {
            // 未绑定
        }
        super.onDestroy()
    }
}
