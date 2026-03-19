package com.scrcpybt.app.ui.controlled

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.scrcpybt.app.R
import com.scrcpybt.app.ui.share.ShareConfigActivity
import com.scrcpybt.app.util.RootChecker
import com.scrcpybt.common.transport.TransportType

/**
 * 被控端界面：启动 scrcpy 服务端进程
 *
 * 主要功能：
 * - 使用 root 权限通过 app_process 启动服务端 JAR
 * - 提供虚拟显示模式配置（Android 10+ 可用）
 * - 提供强制解锁配置（Android 10 以下的回退方案）
 * - 支持配置分享转发功能
 *
 * 虚拟显示模式选项：
 * - 启动应用：指定在虚拟屏幕上启动的应用组件名
 * - 绕过安全标志：允许捕获设置了 FLAG_SECURE 的应用内容
 *
 * 强制解锁配置（仅作为回退方案）：
 * - 凭据类型：无/PIN/密码/图案
 * - 凭据内容：实际的解锁凭据
 * - 注意：凭据仅存储在被控设备上，不通过网络传输
 */
class ControlledActivity : AppCompatActivity() {
    /** 状态文本显示 */
    private lateinit var statusText: TextView
    /** 启动服务器按钮 */
    private lateinit var btnStart: Button
    /** 停止服务器按钮 */
    private lateinit var btnStop: Button
    /** 分享配置按钮 */
    private lateinit var btnShareConfig: Button
    /** 服务器启动器 */
    private lateinit var serverLauncher: ServerLauncher

    // 虚拟显示模式 UI 组件
    /** 虚拟显示模式开关 */
    private lateinit var switchVirtualDisplay: SwitchCompat
    /** 虚拟显示说明文本 */
    private lateinit var virtualDisplayNote: TextView
    /** 启动应用输入框 */
    private lateinit var editStartApp: EditText
    /** 绕过安全标志开关 */
    private lateinit var switchBypassSecureFlag: SwitchCompat
    /** 虚拟显示选项面板 */
    private lateinit var vdOptionsPanel: LinearLayout

    // 强制解锁 UI 组件（回退方案）
    /** 强制解锁开关 */
    private lateinit var switchForceUnlock: SwitchCompat
    /** 强制解锁回退说明文本 */
    private lateinit var forceUnlockFallbackNote: TextView
    /** 解锁配置面板 */
    private lateinit var unlockConfigPanel: LinearLayout
    /** 凭据类型下拉框 */
    private lateinit var spinnerCredentialType: Spinner
    /** 凭据内容输入框 */
    private lateinit var editCredential: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controlled)

        statusText = findViewById(R.id.status_text)
        btnStart = findViewById(R.id.btn_start_server)
        btnStop = findViewById(R.id.btn_stop_server)
        btnShareConfig = findViewById(R.id.btn_share_config)

        // Virtual display UI
        switchVirtualDisplay = findViewById(R.id.switch_virtual_display)
        virtualDisplayNote = findViewById(R.id.virtual_display_note)
        editStartApp = findViewById(R.id.edit_start_app)
        switchBypassSecureFlag = findViewById(R.id.switch_bypass_secure_flag)
        vdOptionsPanel = findViewById(R.id.vd_options_panel)

        // Force unlock UI (fallback for Android <10)
        switchForceUnlock = findViewById(R.id.switch_force_unlock)
        forceUnlockFallbackNote = findViewById(R.id.force_unlock_fallback_note)
        unlockConfigPanel = findViewById(R.id.unlock_config_panel)
        spinnerCredentialType = findViewById(R.id.spinner_credential_type)
        editCredential = findViewById(R.id.edit_credential)

        serverLauncher = ServerLauncher(this)

        // 检查 root 权限
        if (!RootChecker.isRootAvailable()) {
            statusText.setText(R.string.error_no_root)
            btnStart.isEnabled = false
            return
        }

        // 虚拟显示模式：需要 Android 10+ (API 29)
        if (Build.VERSION.SDK_INT >= 29) {
            switchVirtualDisplay.isEnabled = true
            switchVirtualDisplay.isChecked = true  // Android 10+ 默认启用虚拟显示
            virtualDisplayNote.setText(R.string.virtual_display_note)
        } else {
            switchVirtualDisplay.isEnabled = false
            switchVirtualDisplay.isChecked = false
            virtualDisplayNote.setText(R.string.virtual_display_requires_api29)
        }

        // 虚拟显示启用时，显示虚拟显示选项并隐藏强制解锁
        switchVirtualDisplay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 虚拟显示模式：显示虚拟显示选项，隐藏强制解锁
                vdOptionsPanel.visibility = View.VISIBLE
                switchForceUnlock.isChecked = false
                switchForceUnlock.isEnabled = false
                unlockConfigPanel.visibility = View.GONE
                forceUnlockFallbackNote.visibility = View.GONE
            } else {
                // 镜像模式：隐藏虚拟显示选项，显示强制解锁
                vdOptionsPanel.visibility = View.GONE
                switchForceUnlock.isEnabled = true
                forceUnlockFallbackNote.visibility = View.VISIBLE
            }
        }

        // 设置凭据类型下拉框
        val credentialTypes = arrayOf(
            getString(R.string.credential_none),
            getString(R.string.credential_pin),
            getString(R.string.credential_password),
            getString(R.string.credential_pattern)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, credentialTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCredentialType.adapter = adapter

        // 切换解锁配置面板的可见性
        switchForceUnlock.setOnCheckedChangeListener { _, isChecked ->
            unlockConfigPanel.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        unlockConfigPanel.visibility = View.GONE

        // 初始状态：如果虚拟显示开启，显示虚拟显示选项并禁用强制解锁
        if (switchVirtualDisplay.isChecked) {
            vdOptionsPanel.visibility = View.VISIBLE
            switchForceUnlock.isEnabled = false
            forceUnlockFallbackNote.visibility = View.GONE
        } else {
            vdOptionsPanel.visibility = View.GONE
            forceUnlockFallbackNote.visibility = View.VISIBLE
        }

        val transportStr = intent.getStringExtra("transport")
        val transport = transportStr?.let { TransportType.valueOf(it) } ?: TransportType.BLUETOOTH_RFCOMM

        btnStart.setOnClickListener {
            statusText.setText(R.string.starting_server)
            btnStart.isEnabled = false
            btnStop.isEnabled = true

            // 传递虚拟显示配置
            serverLauncher.setVirtualDisplayEnabled(switchVirtualDisplay.isChecked)
            if (switchVirtualDisplay.isChecked) {
                val startApp = editStartApp.text.toString().trim()
                if (startApp.isNotEmpty()) {
                    serverLauncher.setStartApp(startApp)
                }
                serverLauncher.setBypassSecureFlag(switchBypassSecureFlag.isChecked)
            }

            // 收集强制解锁设置（仅作为回退方案）
            val forceUnlockEnabled = switchForceUnlock.isChecked
            val credentialTypeIndex = spinnerCredentialType.selectedItemPosition
            val credential = editCredential.text.toString().trim()

            // 将解锁配置传递给服务器启动器
            serverLauncher.setForceUnlockConfig(
                forceUnlockEnabled, credentialTypeIndex, credential
            )

            serverLauncher.launch(transport, object : ServerLauncher.Callback {
                override fun onStarted() {
                    runOnUiThread { statusText.setText(R.string.server_running) }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        statusText.text = getString(R.string.server_error, error)
                        btnStart.isEnabled = true
                        btnStop.isEnabled = false
                    }
                }

                override fun onStopped() {
                    runOnUiThread {
                        statusText.setText(R.string.server_stopped)
                        btnStart.isEnabled = true
                        btnStop.isEnabled = false
                    }
                }
            })
        }

        btnStop.setOnClickListener {
            serverLauncher.stop()
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
        btnStop.isEnabled = false

        // 分享配置按钮
        btnShareConfig.setOnClickListener {
            startActivity(Intent(this, ShareConfigActivity::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serverLauncher.stop()
    }
}
