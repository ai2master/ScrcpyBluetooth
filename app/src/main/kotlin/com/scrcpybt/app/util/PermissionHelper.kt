package com.scrcpybt.app.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.scrcpybt.common.util.Logger

/**
 * 权限辅助工具：管理运行时权限请求与优雅降级。
 * 提供全面的权限管理功能，包括版本适配、状态检测、用户友好的权限说明，以及操作前的权限守卫。
 *
 * 主要功能：
 * - 检测缺失的蓝牙相关权限
 * - 根据 Android 版本动态适配权限需求
 * - 提供权限-功能映射，告知用户缺失权限影响哪些功能
 * - 操作前权限守卫，避免 SecurityException 崩溃
 *
 * 权限说明：
 * - Android 12+：BLUETOOTH_CONNECT, BLUETOOTH_SCAN（新蓝牙权限）
 * - Android 12+：BLUETOOTH_ADVERTISE（中继端需要）
 * - 所有版本：ACCESS_FINE_LOCATION（蓝牙扫描需要）
 * - Android 13+：POST_NOTIFICATIONS（前台服务通知需要）
 *
 * Permission helper utility: manages runtime permission requests and graceful degradation.
 * Provides comprehensive permission management including version adaptation, status detection,
 * user-friendly permission explanations, and pre-operation permission guards.
 *
 * Main features:
 * - Detects missing Bluetooth-related permissions
 * - Dynamically adapts permission requirements based on Android version
 * - Provides permission-feature mapping to inform users about affected functionality
 * - Pre-operation permission guards to avoid SecurityException crashes
 *
 * Permission notes:
 * - Android 12+: BLUETOOTH_CONNECT, BLUETOOTH_SCAN (new Bluetooth permissions)
 * - Android 12+: BLUETOOTH_ADVERTISE (required for relay mode)
 * - All versions: ACCESS_FINE_LOCATION (required for Bluetooth scanning)
 * - Android 13+: POST_NOTIFICATIONS (required for foreground service notifications)
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
object PermissionHelper {
    private const val TAG = "PermissionHelper"

    /**
     * 权限授予状态枚举。
     *
     * Permission grant status enum.
     */
    enum class PermissionStatus {
        /** 已授予 | Granted */
        GRANTED,
        /** 被拒绝，可继续请求 | Denied, can continue requesting */
        DENIED,
        /** 永久拒绝（用户勾选了"不再询问"），需引导至系统设置 | Permanently denied (user checked "Don't ask again"), need to guide to settings */
        PERMANENTLY_DENIED
    }

    /**
     * 权限检查结果，包含受影响功能和用户友好的说明。
     *
     * Permission check result with affected features and user-friendly explanation.
     */
    data class PermissionCheckResult(
        val permission: String,
        val status: PermissionStatus,
        /** 受影响的功能列表 | List of affected features */
        val affectedFeatures: List<String>,
        /** 中文说明：为什么需要此权限、缺少会怎样 | Chinese explanation: why this permission is needed and what happens without it */
        val explanation: String
    )

    // ---- 基础权限检查 ----

    /**
     * 获取蓝牙操作所需的缺失权限列表
     *
     * @param activity 当前 Activity
     * @return 缺失的权限数组
     */
    fun getMissingBluetoothPermissions(activity: Activity): Array<String> {
        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkAndAdd(activity, missing, Manifest.permission.BLUETOOTH_CONNECT)
            checkAndAdd(activity, missing, Manifest.permission.BLUETOOTH_SCAN)
        }
        checkAndAdd(activity, missing, Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkAndAdd(activity, missing, Manifest.permission.POST_NOTIFICATIONS)
        }

        return missing.toTypedArray()
    }

    /**
     * 检查是否已授予所有蓝牙权限
     */
    fun hasBluetoothPermissions(activity: Activity): Boolean {
        return getMissingBluetoothPermissions(activity).isEmpty()
    }

    /**
     * 请求缺失的蓝牙权限
     */
    fun requestBluetoothPermissions(activity: Activity, requestCode: Int) {
        val missing = getMissingBluetoothPermissions(activity)
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing, requestCode)
        }
    }

    // ---- 权限状态与功能映射 ----

    /**
     * 检查单个权限的授予状态
     *
     * 区分三种状态：已授予、被拒绝（可重新请求）、永久拒绝（需前往设置）
     */
    fun checkPermissionStatus(activity: Activity, permission: String): PermissionStatus {
        return when {
            ContextCompat.checkSelfPermission(activity, permission) ==
                    PackageManager.PERMISSION_GRANTED -> PermissionStatus.GRANTED
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) ->
                PermissionStatus.DENIED
            else -> PermissionStatus.PERMANENTLY_DENIED
        }
    }

    /**
     * 使用 Context 检查权限是否已授予（不区分拒绝类型，用于 Service 等非 Activity 场景）
     */
    fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查权限并返回详细结果（含受影响功能和中文说明）
     */
    fun checkPermissionWithExplanation(
        activity: Activity,
        permission: String
    ): PermissionCheckResult {
        val status = checkPermissionStatus(activity, permission)
        val (features, explanation) = getPermissionInfo(permission)
        return PermissionCheckResult(permission, status, features, explanation)
    }

    /**
     * 获取所有需要检查的权限中，缺失权限的汇总信息
     */
    fun getMissingPermissionsSummary(activity: Activity): List<PermissionCheckResult> {
        val results = mutableListOf<PermissionCheckResult>()

        val allPermissions = mutableListOf<String>()

        // 蓝牙权限（Android 12+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            allPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            allPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            allPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        // 位置权限
        allPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            allPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        for (permission in allPermissions) {
            val result = checkPermissionWithExplanation(activity, permission)
            if (result.status != PermissionStatus.GRANTED) {
                results.add(result)
            }
        }

        return results
    }

    /**
     * 操作前权限守卫：检查所需权限，缺失时调用 onDenied 回调
     *
     * @param activity 当前 Activity
     * @param operation 操作名称（用于日志）
     * @param requiredPermissions 必需的权限列表
     * @param onDenied 权限缺失时的回调，接收中文说明文本
     * @return true 如果所有权限都已授予，false 如果缺失权限
     */
    fun guardOperation(
        activity: Activity,
        operation: String,
        requiredPermissions: List<String>,
        onDenied: (explanation: String) -> Unit
    ): Boolean {
        val explanations = mutableListOf<String>()

        for (permission in requiredPermissions) {
            val result = checkPermissionWithExplanation(activity, permission)
            if (result.status != PermissionStatus.GRANTED) {
                explanations.add(
                    "【${result.affectedFeatures.joinToString("、")}】\n${result.explanation}"
                )
            }
        }

        return if (explanations.isEmpty()) {
            true
        } else {
            Logger.w(TAG, "Operation '$operation' blocked: missing permissions")
            onDenied(explanations.joinToString("\n\n"))
            false
        }
    }

    // ---- 内部方法 ----

    private fun checkAndAdd(
        activity: Activity,
        list: MutableList<String>,
        permission: String
    ) {
        if (ContextCompat.checkSelfPermission(activity, permission) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            list.add(permission)
        }
    }

    /**
     * 权限-功能映射表：返回 (受影响功能列表, 中文说明)
     */
    private fun getPermissionInfo(permission: String): Pair<List<String>, String> {
        return when (permission) {
            Manifest.permission.BLUETOOTH_CONNECT ->
                listOf("屏幕镜像", "所有蓝牙操作", "连接设备") to
                        "需要此权限才能通过蓝牙连接远程设备。" +
                        "缺少此权限将无法进行屏幕镜像、剪贴板传输等所有蓝牙功能。"

            Manifest.permission.BLUETOOTH_SCAN ->
                listOf("设备发现") to
                        "需要此权限才能扫描附近的蓝牙设备。" +
                        "缺少此权限仍可连接已配对的设备，但无法发现新设备。"

            Manifest.permission.BLUETOOTH_ADVERTISE ->
                listOf("中继模式") to
                        "中继模式需要此权限作为蓝牙服务端接受连接。" +
                        "控制端和被控端不需要此权限。"

            Manifest.permission.ACCESS_FINE_LOCATION ->
                listOf("设备发现") to
                        "蓝牙设备扫描需要位置权限（Android 系统限制）。" +
                        "缺少此权限仍可连接已配对的设备，但无法发现新设备。"

            Manifest.permission.POST_NOTIFICATIONS ->
                listOf("前台服务通知", "剪贴板接收通知") to
                        "需要此权限才能显示服务运行通知和剪贴板接收通知。" +
                        "缺少此权限可能导致系统在后台杀死服务。"

            else ->
                listOf("未知功能") to "权限：$permission"
        }
    }
}
