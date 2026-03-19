package com.scrcpybt.app.ui.clipboard

import android.content.ClipData
import android.content.ClipboardManager
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
import com.scrcpybt.common.util.Logger

/**
 * 剪贴板传输 Activity：手动在控制端和受控端之间传递剪贴板内容
 *
 * 独立于屏幕镜像功能使用，但需要一个活跃的 ControllerService 连接。
 *
 * ### 功能
 * - 查看本地剪贴板内容
 * - 发送本地剪贴板到受控端（控制端 → 受控端）
 * - 请求受控端剪贴板内容（受控端 → 控制端）
 * - 将收到的远端内容写入本地剪贴板
 *
 * ### Android 剪贴板限制处理
 * 此 Activity 在前台时可以正常读写剪贴板（Android 10+ 限制不影响前台 Activity）。
 * 当在后台收到受控端剪贴板内容时，通过 ClipboardTrampolineActivity 处理。
 *
 * @see ControllerService
 * @see ClipboardTrampolineActivity
 */
class ClipboardActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ClipboardActivity"
    }

    private lateinit var tvLocalClipboard: TextView
    private lateinit var tvRemoteClipboard: TextView
    private var controllerService: ControllerService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ControllerService.LocalBinder
            controllerService = binder?.getService()
            bound = true

            // 注册剪贴板回调：在前台 Activity 中直接写入
            controllerService?.clipboardCallback = object : ControllerService.ClipboardCallback {
                override fun onClipboardReceived(text: String) {
                    runOnUiThread {
                        tvRemoteClipboard.text = text
                        Toast.makeText(
                            this@ClipboardActivity,
                            "收到远端剪贴板 (${text.length} 字符)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            Logger.i(TAG, "Bound to ControllerService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controllerService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clipboard)

        supportActionBar?.apply {
            title = "剪贴板传输"
            setDisplayHomeAsUpEnabled(true)
        }

        tvLocalClipboard = findViewById(R.id.tv_local_clipboard)
        tvRemoteClipboard = findViewById(R.id.tv_remote_clipboard)
        val btnRefresh = findViewById<Button>(R.id.btn_refresh_local)
        val btnSendToRemote = findViewById<Button>(R.id.btn_send_to_remote)
        val btnFetchFromRemote = findViewById<Button>(R.id.btn_fetch_from_remote)
        val btnCopyToLocal = findViewById<Button>(R.id.btn_copy_to_local)

        // 读取本地剪贴板（Activity 在前台，不受 Android 10+ 限制）
        refreshLocalClipboard()

        btnRefresh.setOnClickListener {
            refreshLocalClipboard()
        }

        btnSendToRemote.setOnClickListener {
            val text = tvLocalClipboard.text.toString()
            if (text.isNotEmpty() && text != "(空)") {
                sendClipboardToRemote(text)
            } else {
                Toast.makeText(this, "本地剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }

        btnFetchFromRemote.setOnClickListener {
            requestRemoteClipboard()
        }

        btnCopyToLocal.setOnClickListener {
            val text = tvRemoteClipboard.text.toString()
            if (text.isNotEmpty() && text != "(空)") {
                copyToLocalClipboard(text)
            } else {
                Toast.makeText(this, "远端剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }

        // 绑定到 ControllerService
        val serviceIntent = Intent(this, ControllerService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * 读取本地剪贴板
     *
     * Activity 在前台，Android 10+ 的剪贴板限制不影响。
     */
    private fun refreshLocalClipboard() {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboardManager.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            tvLocalClipboard.text = text
        } else {
            tvLocalClipboard.text = "(空)"
        }
    }

    /**
     * 发送本地剪贴板到受控端
     */
    private fun sendClipboardToRemote(text: String) {
        val service = controllerService
        if (service == null) {
            Toast.makeText(this, "未连接到远端设备", Toast.LENGTH_SHORT).show()
            return
        }
        service.sendClipboard(text)
        Toast.makeText(this, "已发送到远端剪贴板", Toast.LENGTH_SHORT).show()
    }

    /**
     * 请求受控端的剪贴板内容
     *
     * 受控端会通过 ClipboardMessage(DIR_PUSH) 回复，
     * 内容通过 clipboardCallback 回调更新到 tvRemoteClipboard。
     */
    private fun requestRemoteClipboard() {
        val service = controllerService
        if (service == null) {
            Toast.makeText(this, "未连接到远端设备", Toast.LENGTH_SHORT).show()
            return
        }
        service.requestClipboard()
        Toast.makeText(this, "已请求远端剪贴板", Toast.LENGTH_SHORT).show()
    }

    /**
     * 将远端剪贴板内容写入本地
     *
     * Activity 在前台，可以直接写入。
     */
    private fun copyToLocalClipboard(text: String) {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("remote_clipboard", text)
        clipboardManager.setPrimaryClip(clip)
        Toast.makeText(this, "已写入本地剪贴板", Toast.LENGTH_SHORT).show()
        refreshLocalClipboard()
    }

    override fun onDestroy() {
        // 清除回调，防止 Activity 销毁后还持有引用
        controllerService?.clipboardCallback = null
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
