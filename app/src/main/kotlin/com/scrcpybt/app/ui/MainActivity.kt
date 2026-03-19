package com.scrcpybt.app.ui

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.scrcpybt.app.R
import com.scrcpybt.app.ui.clipboard.ClipboardActivity
import com.scrcpybt.app.ui.controlled.ControlledActivity
import com.scrcpybt.app.ui.controller.ControllerActivity
import com.scrcpybt.app.ui.filetransfer.FileTransferActivity
import com.scrcpybt.app.ui.foldersync.FolderSyncActivity
import com.scrcpybt.app.ui.relay.RelayActivity
import com.scrcpybt.app.ui.shortcut.ShortcutConfigActivity
import com.scrcpybt.app.util.PermissionHelper
import com.scrcpybt.common.transport.TransportType

/**
 * 主界面：角色选择（控制端/中继端/被控端）和传输方式选择
 *
 * 本应用支持三种角色：
 * - 控制端（Controller）：连接到被控设备，接收屏幕画面并发送控制指令
 * - 中继端（Relay）：作为蓝牙-USB 桥接，转发控制端和被控端之间的数据
 * - 被控端（Controlled）：提供屏幕画面并接收控制指令
 *
 * 支持两种传输方式：
 * - USB ADB：通过 adb forward 端口转发进行连接
 * - 蓝牙 RFCOMM：通过蓝牙经典协议进行连接
 *
 * 此外还提供快捷访问独立功能：
 * - 剪贴板同步：跨设备剪贴板共享
 * - 文件传输：设备间文件互传
 * - 文件夹同步：自动化文件夹双向同步
 *
 * 健壮性设计：
 * - 权限缺失时显示状态面板，说明影响哪些功能
 * - 蓝牙传输模式下，缺少必需权限时阻止启动并引导用户授权
 * - 首次使用控制端/中继端时提示电池优化豁免
 */
class MainActivity : AppCompatActivity() {
    companion object {
        /** 请求运行时权限的请求码 */
        private const val REQUEST_PERMISSIONS = 100
        /** 请求启用蓝牙的请求码 */
        private const val REQUEST_ENABLE_BT = 101

        /** SharedPreferences 名称 */
        private const val PREFS_NAME = "app_settings"
        /** 是否已提示过电池优化 */
        private const val PREF_BATTERY_PROMPT_SHOWN = "battery_optimization_prompted"
    }

    /** 角色选择单选按钮组 */
    private lateinit var roleGroup: RadioGroup
    /** 传输方式选择单选按钮组 */
    private lateinit var transportGroup: RadioGroup

    /** 权限状态面板 */
    private lateinit var permissionPanel: LinearLayout
    private lateinit var tvPermissionSummary: TextView
    private lateinit var btnPermissionDetails: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        roleGroup = findViewById(R.id.role_group)
        transportGroup = findViewById(R.id.transport_group)
        val startButton = findViewById<Button>(R.id.btn_start)

        // Standalone feature buttons
        val btnClipboard = findViewById<Button>(R.id.btn_clipboard)
        val btnFileTransfer = findViewById<Button>(R.id.btn_file_transfer)
        val btnFolderSync = findViewById<Button>(R.id.btn_folder_sync)

        startButton.setOnClickListener { onStartClicked() }

        btnClipboard.setOnClickListener {
            startActivity(Intent(this, ClipboardActivity::class.java))
        }

        btnFileTransfer.setOnClickListener {
            startActivity(Intent(this, FileTransferActivity::class.java))
        }

        btnFolderSync.setOnClickListener {
            startActivity(Intent(this, FolderSyncActivity::class.java))
        }

        val btnShortcutConfig = findViewById<Button>(R.id.btn_shortcut_config)
        btnShortcutConfig.setOnClickListener {
            startActivity(Intent(this, ShortcutConfigActivity::class.java))
        }

        // 权限面板
        permissionPanel = findViewById(R.id.permission_status_panel)
        tvPermissionSummary = findViewById(R.id.tv_permission_summary)
        btnPermissionDetails = findViewById(R.id.btn_permission_details)
        btnPermissionDetails.setOnClickListener { showPermissionDetailsDialog() }

        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台刷新权限状态（用户可能从设置返回）
        updatePermissionStatusPanel()
    }

    /**
     * 处理开始按钮点击事件
     *
     * 根据用户选择的角色和传输方式，启动对应的 Activity。
     * 蓝牙模式下会先检查权限和蓝牙状态。
     */
    private fun onStartClicked() {
        val roleId = roleGroup.checkedRadioButtonId
        val transportId = transportGroup.checkedRadioButtonId

        val transport = if (transportId == R.id.radio_usb) {
            TransportType.USB_ADB
        } else {
            TransportType.BLUETOOTH_RFCOMM
        }

        // 蓝牙传输时，检查权限
        if (transport == TransportType.BLUETOOTH_RFCOMM) {
            val requiredPermissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                // 中继模式额外需要 ADVERTISE 权限
                if (roleId == R.id.radio_relay) {
                    requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                }
            }

            if (requiredPermissions.isNotEmpty()) {
                val canProceed = PermissionHelper.guardOperation(
                    this,
                    "启动蓝牙连接",
                    requiredPermissions
                ) { explanation ->
                    AlertDialog.Builder(this)
                        .setTitle("权限不足")
                        .setMessage(explanation)
                        .setPositiveButton("前往设置") { _, _ -> openAppSettings() }
                        .setNegativeButton("取消", null)
                        .show()
                }
                if (!canProceed) return
            }

            // 检查蓝牙是否已启用
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null || !adapter.isEnabled) {
                    val enableBt = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    @Suppress("DEPRECATION")
                    startActivityForResult(enableBt, REQUEST_ENABLE_BT)
                    return
                }
            } catch (e: SecurityException) {
                Toast.makeText(this, "缺少蓝牙权限", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 对于需要后台保活的角色，检查电池优化
        if (roleId == R.id.radio_controller || roleId == R.id.radio_relay) {
            checkAndRequestBatteryOptimization()
        }

        val intent = when (roleId) {
            R.id.radio_controller -> Intent(this, ControllerActivity::class.java)
            R.id.radio_relay -> Intent(this, RelayActivity::class.java)
            R.id.radio_controlled -> Intent(this, ControlledActivity::class.java)
            else -> {
                Toast.makeText(this, R.string.select_role_hint, Toast.LENGTH_SHORT).show()
                return
            }
        }

        intent.putExtra("transport", transport.name)
        startActivity(intent)
    }

    // ---- 权限请求 ----

    /**
     * 请求应用运行所需的全部权限
     */
    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 文件传输功能需要的存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(), REQUEST_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            // 权限结果返回后刷新面板
            updatePermissionStatusPanel()
        }
    }

    // ---- 权限状态面板 ----

    /**
     * 更新权限状态面板的显示
     *
     * 根据当前缺失的权限数量和类型：
     * - 红色背景：缺少核心权限（BLUETOOTH_CONNECT）
     * - 橙色背景：缺少可选权限
     * - 隐藏：所有权限已授予
     */
    private fun updatePermissionStatusPanel() {
        val missingPermissions = PermissionHelper.getMissingPermissionsSummary(this)

        if (missingPermissions.isEmpty()) {
            permissionPanel.visibility = View.GONE
            return
        }

        permissionPanel.visibility = View.VISIBLE

        // 判断是否缺少核心权限
        val hasCriticalMissing = missingPermissions.any { result ->
            result.affectedFeatures.any {
                it == "屏幕镜像" || it == "所有蓝牙操作"
            }
        }

        if (hasCriticalMissing) {
            permissionPanel.setBackgroundColor(Color.parseColor("#D32F2F")) // 红色
            tvPermissionSummary.text = getString(
                R.string.permission_missing_critical,
                missingPermissions.size
            )
        } else {
            permissionPanel.setBackgroundColor(Color.parseColor("#F57C00")) // 橙色
            tvPermissionSummary.text = getString(
                R.string.permission_missing_optional,
                missingPermissions.size
            )
        }
    }

    /**
     * 显示权限详情对话框
     *
     * 列出所有缺失权限、受影响功能、以及状态（可重新请求 / 需前往设置）
     */
    private fun showPermissionDetailsDialog() {
        val missingPermissions = PermissionHelper.getMissingPermissionsSummary(this)

        if (missingPermissions.isEmpty()) {
            Toast.makeText(this, R.string.permission_all_granted, Toast.LENGTH_SHORT).show()
            return
        }

        val message = StringBuilder()
        missingPermissions.forEach { result ->
            message.append("【${result.affectedFeatures.joinToString("、")}】\n")
            message.append("${result.explanation}\n")
            message.append("状态：")
            message.append(
                when (result.status) {
                    PermissionHelper.PermissionStatus.DENIED -> "被拒绝（可重新请求）"
                    PermissionHelper.PermissionStatus.PERMANENTLY_DENIED -> "永久拒绝（需前往设置）"
                    else -> "未知"
                }
            )
            message.append("\n\n")
        }

        val hasPermanentlyDenied = missingPermissions.any {
            it.status == PermissionHelper.PermissionStatus.PERMANENTLY_DENIED
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.permission_panel_title)
            .setMessage(message.toString().trimEnd())
            .setNegativeButton(R.string.close, null)

        if (hasPermanentlyDenied) {
            builder.setPositiveButton(R.string.permission_go_to_settings) { _, _ ->
                openAppSettings()
            }
        } else {
            builder.setPositiveButton(R.string.permission_grant) { _, _ ->
                requestRequiredPermissions()
            }
        }

        builder.show()
    }

    /**
     * 打开应用的系统设置页面
     */
    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开设置：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- 电池优化 ----

    /**
     * 检查并请求电池优化豁免
     *
     * 仅在首次使用需要后台保活的功能时提示一次。
     * 不强制要求，用户可选择"暂不设置"。
     */
    private fun checkAndRequestBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_BATTERY_PROMPT_SHOWN, false)) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        // 标记已提示，避免每次都弹
        prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply()

        AlertDialog.Builder(this)
            .setTitle(R.string.battery_opt_title)
            .setMessage(R.string.battery_opt_message)
            .setPositiveButton(R.string.battery_opt_go) { _, _ ->
                try {
                    @Suppress("BatteryLife")
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(
                        this, "无法打开设置：${e.message}", Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.battery_opt_skip, null)
            .show()
    }
}
