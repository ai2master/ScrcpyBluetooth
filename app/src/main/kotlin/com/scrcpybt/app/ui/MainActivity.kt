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
 * 主界面：角色选择（控制端/中继端/被控端）和传输方式选择。
 * 这是应用的入口界面，提供用户友好的角色和传输方式选择体验，并整合权限管理和电池优化引导。
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
 *
 * Main interface: role selection (Controller/Relay/Controlled) and transport method selection.
 * This is the app's entry screen, providing a user-friendly experience for selecting roles
 * and transport methods, with integrated permission management and battery optimization guidance.
 *
 * Supports three roles:
 * - Controller: Connects to controlled device, receives screen frames and sends control commands
 * - Relay: Acts as Bluetooth-USB bridge, forwarding data between controller and controlled
 * - Controlled: Provides screen frames and receives control commands
 *
 * Supports two transport methods:
 * - USB ADB: Connection via adb forward port forwarding
 * - Bluetooth RFCOMM: Connection via Bluetooth Classic protocol
 *
 * Also provides quick access to standalone features:
 * - Clipboard Sync: Cross-device clipboard sharing
 * - File Transfer: Inter-device file exchange
 * - Folder Sync: Automated bidirectional folder synchronization
 *
 * Robustness design:
 * - Shows status panel when permissions are missing, explaining affected features
 * - Blocks startup and guides user to authorization when required permissions are missing in Bluetooth mode
 * - Prompts for battery optimization exemption when using Controller/Relay for the first time
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
class MainActivity : AppCompatActivity() {
    companion object {
        /** 请求运行时权限的请求码 | Request code for runtime permissions */
        private const val REQUEST_PERMISSIONS = 100
        /** 请求启用蓝牙的请求码 | Request code for enabling Bluetooth */
        private const val REQUEST_ENABLE_BT = 101

        /** SharedPreferences 名称 | SharedPreferences name */
        private const val PREFS_NAME = "app_settings"
        /** 是否已提示过电池优化 | Whether battery optimization prompt has been shown */
        private const val PREF_BATTERY_PROMPT_SHOWN = "battery_optimization_prompted"
    }

    /** 角色选择单选按钮组 | Role selection radio group */
    private lateinit var roleGroup: RadioGroup
    /** 传输方式选择单选按钮组 | Transport method selection radio group */
    private lateinit var transportGroup: RadioGroup

    /** 权限状态面板 | Permission status panel */
    private lateinit var permissionPanel: LinearLayout
    /** 权限摘要文本 | Permission summary text */
    private lateinit var tvPermissionSummary: TextView
    /** 权限详情按钮 | Permission details button */
    private lateinit var btnPermissionDetails: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        roleGroup = findViewById(R.id.role_group)
        transportGroup = findViewById(R.id.transport_group)
        val startButton = findViewById<Button>(R.id.btn_start)

        // 独立功能按钮 | Standalone feature buttons
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

    /**
     * 活动恢复时的回调，每次界面可见时刷新权限状态面板。
     *
     * Callback when activity resumes, refreshes permission status panel whenever UI becomes visible.
     */
    override fun onResume() {
        super.onResume()
        // 每次回到前台刷新权限状态（用户可能从设置返回）| Refresh permission status every time returning to foreground (user may return from settings)
        updatePermissionStatusPanel()
    }

    /**
     * 处理开始按钮点击事件。
     * 根据用户选择的角色和传输方式，启动对应的 Activity。
     * 蓝牙模式下会先检查权限和蓝牙状态，确保满足运行条件后再跳转。
     *
     * Handles start button click event.
     * Launches the corresponding Activity based on user-selected role and transport method.
     * In Bluetooth mode, checks permissions and Bluetooth status first, ensuring prerequisites are met before navigation.
     */
    private fun onStartClicked() {
        val roleId = roleGroup.checkedRadioButtonId
        val transportId = transportGroup.checkedRadioButtonId

        val transport = if (transportId == R.id.radio_usb) {
            TransportType.USB_ADB
        } else {
            TransportType.BLUETOOTH_RFCOMM
        }

        // 蓝牙传输时，检查权限 | When using Bluetooth transport, check permissions
        if (transport == TransportType.BLUETOOTH_RFCOMM) {
            val requiredPermissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                // 中继模式额外需要 ADVERTISE 权限 | Relay mode requires additional ADVERTISE permission
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

            // 检查蓝牙是否已启用 | Check if Bluetooth is enabled
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

        // 对于需要后台保活的角色，检查电池优化 | For roles requiring background keep-alive, check battery optimization
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

    // ---- 权限请求 | Permission Requests ----

    /**
     * 请求应用运行所需的全部权限，包括蓝牙、定位、通知、存储等。
     * 根据 Android 版本动态选择对应的权限常量。
     *
     * Requests all permissions required for app operation, including Bluetooth, location, notifications, storage, etc.
     * Dynamically selects corresponding permission constants based on Android version.
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

        // 文件传输功能需要的存储权限 | Storage permissions required for file transfer features
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

    /**
     * 权限请求结果回调，处理用户授权选择后的逻辑。
     *
     * Permission request result callback, handles logic after user authorization choices.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            // 权限结果返回后刷新面板 | Refresh panel after permission result returns
            updatePermissionStatusPanel()
        }
    }

    // ---- 权限状态面板 | Permission Status Panel ----

    /**
     * 更新权限状态面板的显示。
     * 根据当前缺失的权限数量和类型动态调整面板背景色和文案：
     * - 红色背景：缺少核心权限（BLUETOOTH_CONNECT）
     * - 橙色背景：缺少可选权限
     * - 隐藏：所有权限已授予
     *
     * Updates the permission status panel display.
     * Dynamically adjusts panel background color and text based on missing permissions:
     * - Red background: Missing critical permissions (BLUETOOTH_CONNECT)
     * - Orange background: Missing optional permissions
     * - Hidden: All permissions granted
     */
    private fun updatePermissionStatusPanel() {
        val missingPermissions = PermissionHelper.getMissingPermissionsSummary(this)

        if (missingPermissions.isEmpty()) {
            permissionPanel.visibility = View.GONE
            return
        }

        permissionPanel.visibility = View.VISIBLE

        // 判断是否缺少核心权限 | Check if critical permissions are missing
        val hasCriticalMissing = missingPermissions.any { result ->
            result.affectedFeatures.any {
                it == "屏幕镜像" || it == "所有蓝牙操作"
            }
        }

        if (hasCriticalMissing) {
            permissionPanel.setBackgroundColor(Color.parseColor("#D32F2F")) // 红色 | Red
            tvPermissionSummary.text = getString(
                R.string.permission_missing_critical,
                missingPermissions.size
            )
        } else {
            permissionPanel.setBackgroundColor(Color.parseColor("#F57C00")) // 橙色 | Orange
            tvPermissionSummary.text = getString(
                R.string.permission_missing_optional,
                missingPermissions.size
            )
        }
    }

    /**
     * 显示权限详情对话框，详细列出所有缺失权限信息。
     * 包含受影响功能、缺失原因说明、以及当前状态（可重新请求 / 需前往设置）。
     * 根据权限状态提供对应的操作按钮。
     *
     * Shows permission details dialog with detailed information about all missing permissions.
     * Includes affected features, explanation for missing permissions, and current status (can re-request / need to go to settings).
     * Provides corresponding action buttons based on permission status.
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
     * 打开应用的系统设置页面，方便用户手动授予永久拒绝的权限。
     *
     * Opens the app's system settings page, allowing users to manually grant permanently denied permissions.
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

    // ---- 电池优化 | Battery Optimization ----

    /**
     * 检查并请求电池优化豁免，确保后台服务不被系统杀死。
     * 仅在首次使用需要后台保活的功能（控制端/中继端）时提示一次。
     * 不强制要求，用户可选择"暂不设置"，后续可从系统设置手动开启。
     *
     * Checks and requests battery optimization exemption to ensure background services aren't killed by system.
     * Only prompts once when using features requiring background keep-alive (Controller/Relay) for the first time.
     * Not mandatory, users can choose "Skip for now" and manually enable it later from system settings.
     */
    private fun checkAndRequestBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_BATTERY_PROMPT_SHOWN, false)) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        // 标记已提示，避免每次都弹 | Mark as prompted to avoid showing every time
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
