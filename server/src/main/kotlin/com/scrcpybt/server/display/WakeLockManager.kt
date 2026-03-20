package com.scrcpybt.server.display

import android.os.IBinder
import com.scrcpybt.common.util.Logger

/**
 * 唤醒锁管理器
 *
 * 在远程控制会话期间管理设备唤醒状态。
 *
 * ### 问题背景
 * 当物理屏幕关闭（虚拟显示器模式）时，设备可能进入深度睡眠（Doze 模式），
 * 导致虚拟显示器捕获冻结或 CPU 降频，造成丢帧和输入延迟。
 *
 * ### 解决方案（按优先级）
 * 1. **PowerManager 唤醒锁**（通过反射）- PARTIAL_WAKE_LOCK 保持 CPU 运行
 * 2. **Settings.Global.stay_on_while_plugged_in** - 充电时保持唤醒
 * 3. **svc power stayon** 命令 - 系统服务命令
 *
 * ### 技术实现
 * 所有操作都使用隐藏 API，因为以 shell UID (2000) 运行（通过 app_process）。
 * 通过反射调用 IPowerManager 系统服务进行 IPC 通信。
 *
 * ### 资源清理
 * release() 方法会恢复原始设置，确保不影响用户的设备配置。
 *
 * Wake lock manager that prevents device from entering deep sleep during remote
 * sessions using multiple fallback strategies with proper cleanup on release.
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 * @see PowerManager
 */
class WakeLockManager {

    companion object {
        private const val TAG = "WakeLockManager"

        /** PowerManager.PARTIAL_WAKE_LOCK - 部分唤醒锁（保持 CPU 运行） */
        private const val PARTIAL_WAKE_LOCK = 1

        /** PowerManager.ACQUIRE_CAUSES_WAKEUP - 获取时唤醒设备 */
        private const val ACQUIRE_CAUSES_WAKEUP = 0x10000000

        /** Settings.Global.STAY_ON_WHILE_PLUGGED_IN 的值 */
        /** BatteryManager.BATTERY_PLUGGED_AC = 1 (交流电) */
        /** BatteryManager.BATTERY_PLUGGED_USB = 2 (USB) */
        /** BatteryManager.BATTERY_PLUGGED_WIRELESS = 4 (无线充电) */
        /** 所有充电方式的组合 */
        private const val STAY_ON_ALL_PLUGGED = 7  // AC | USB | Wireless
    }

    /** 唤醒锁令牌 */
    private var wakeLockToken: IBinder? = null

    /** 原始 stay_on_while_plugged_in 值 */
    private var originalStayOnValue: Int = -1

    /** 是否持有唤醒锁 */
    private var holdingWakeLock = false

    /** 是否修改了 stay_on 设置 */
    private var modifiedStayOnSetting = false

    /**
     * 获取唤醒锁
     *
     * 在远程会话期间保持设备 CPU 运行。
     * 通过 IPC 使用 PowerManager 隐藏 API。
     *
     * ### 尝试顺序
     * 1. 通过 IPowerManager 服务获取唤醒锁
     * 2. 修改 stay_on_while_plugged_in 系统设置作为降级方案
     * 3. 使用 svc power stayon 命令
     */
    fun acquire() {
        if (holdingWakeLock) {
            Logger.d(TAG, "Wake lock already held")
            return
        }

        Logger.i(TAG, "Acquiring wake lock for remote session")

        // 方案 1: 通过 ServiceManager 使用 IPowerManager.acquireWakeLock
        if (acquireWakeLockViaService()) {
            holdingWakeLock = true
            Logger.i(TAG, "Wake lock acquired via PowerManager service")
            return
        }

        // 方案 2: 修改 stay_on_while_plugged_in 系统设置
        if (enableStayOnSetting()) {
            modifiedStayOnSetting = true
            holdingWakeLock = true
            Logger.i(TAG, "Stay-on-while-plugged-in enabled as fallback")
            return
        }

        // 方案 3: svc power stayon 命令
        if (enableStayOnViaSvc()) {
            holdingWakeLock = true
            Logger.i(TAG, "Stay-on enabled via svc command")
            return
        }

        Logger.w(TAG, "All wake lock approaches failed - device may enter deep sleep")
    }

    /**
     * 释放唤醒锁并恢复原始设置
     */
    fun release() {
        if (!holdingWakeLock) return

        Logger.i(TAG, "Releasing wake lock")

        // Release IPowerManager wake lock
        if (wakeLockToken != null) {
            releaseWakeLockViaService()
        }

        // Restore stay_on_while_plugged_in setting
        if (modifiedStayOnSetting) {
            restoreStayOnSetting()
            modifiedStayOnSetting = false
        }

        holdingWakeLock = false
        Logger.i(TAG, "Wake lock released")
    }

    /**
     * 通过 IPowerManager 服务获取唤醒锁
     *
     * ### 方法签名（因 Android 版本而异）
     * ```
     * void acquireWakeLock(IBinder lock, int flags, String tag,
     *     String packageName, WorkSource ws, String historyTag, int uid, int pid)
     * ```
     *
     * 会尝试多种方法签名以兼容不同版本。
     *
     * @return 成功返回 true
     */
    private fun acquireWakeLockViaService(): Boolean {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getService = serviceManagerClass.getMethod("getService", String::class.java)
            val powerBinder = getService.invoke(null, "power") as? IBinder ?: return false

            val iPowerManagerStub = Class.forName("android.os.IPowerManager\$Stub")
            val asInterface = iPowerManagerStub.getMethod("asInterface", IBinder::class.java)
            val powerManager = asInterface.invoke(null, powerBinder)

            // 创建令牌 Binder
            wakeLockToken = android.os.Binder()

            // 尝试多种方法签名
            val service = powerManager!!

            // 尝试 Android 10+ 签名（带 uid/pid）
            val acquired = tryAcquireWakeLock(service,
                arrayOf(
                    IBinder::class.java,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    String::class.java,
                    Class.forName("android.os.WorkSource"),
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!
                ),
                arrayOf(
                    wakeLockToken,
                    PARTIAL_WAKE_LOCK,
                    "scrcpy-bt:remote",
                    "com.android.shell",
                    null, // WorkSource
                    null, // historyTag
                    android.os.Process.myUid(),
                    android.os.Process.myPid()
                )
            ) ?: tryAcquireWakeLock(service,
                // 旧版本签名（无 uid/pid）
                arrayOf(
                    IBinder::class.java,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    String::class.java,
                    Class.forName("android.os.WorkSource"),
                    String::class.java
                ),
                arrayOf(
                    wakeLockToken,
                    PARTIAL_WAKE_LOCK,
                    "scrcpy-bt:remote",
                    "com.android.shell",
                    null,
                    null
                )
            )

            return acquired == true
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to acquire wake lock via service: ${e.message}")
            wakeLockToken = null
            return false
        }
    }

    private fun tryAcquireWakeLock(
        service: Any,
        paramTypes: Array<Class<*>>,
        args: Array<Any?>
    ): Boolean? {
        return try {
            val method = service.javaClass.getMethod("acquireWakeLock", *paramTypes)
            method.invoke(service, *args)
            true
        } catch (_: NoSuchMethodException) {
            null
        } catch (e: Exception) {
            Logger.d(TAG, "acquireWakeLock failed: ${e.message}")
            null
        }
    }

    /**
     * 通过 IPowerManager 服务释放唤醒锁
     */
    private fun releaseWakeLockViaService() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getService = serviceManagerClass.getMethod("getService", String::class.java)
            val powerBinder = getService.invoke(null, "power") as? IBinder ?: return

            val iPowerManagerStub = Class.forName("android.os.IPowerManager\$Stub")
            val asInterface = iPowerManagerStub.getMethod("asInterface", IBinder::class.java)
            val powerManager = asInterface.invoke(null, powerBinder)

            // releaseWakeLock(IBinder lock, int flags)
            try {
                val method = powerManager!!.javaClass.getMethod(
                    "releaseWakeLock",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType
                )
                method.invoke(powerManager, wakeLockToken, 0)
                Logger.d(TAG, "Wake lock released via PowerManager service")
            } catch (e: NoSuchMethodException) {
                // 尝试无 flags 参数的版本
                val method = powerManager!!.javaClass.getMethod(
                    "releaseWakeLock",
                    IBinder::class.java
                )
                method.invoke(powerManager, wakeLockToken)
            }

            wakeLockToken = null
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to release wake lock via service: ${e.message}")
        }
    }

    /**
     * 通过 Settings.Global 启用 stay_on_while_plugged_in 设置
     *
     * 使设备在任何电源充电时保持唤醒。
     * 此方法会保存原始值以便后续恢复。
     *
     * @return 成功返回 true
     */
    private fun enableStayOnSetting(): Boolean {
        try {
            // 保存原始值
            val getResult = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "settings get global stay_on_while_plugged_in")
            )
            val originalStr = getResult.inputStream.bufferedReader().readText().trim()
            originalStayOnValue = originalStr.toIntOrNull() ?: 0
            getResult.waitFor()

            // 设置为所有电源都保持唤醒
            val setResult = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "settings put global stay_on_while_plugged_in $STAY_ON_ALL_PLUGGED")
            )
            setResult.waitFor()

            Logger.d(TAG, "stay_on_while_plugged_in: $originalStayOnValue -> $STAY_ON_ALL_PLUGGED")
            return setResult.exitValue() == 0
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to modify stay_on setting: ${e.message}")
            return false
        }
    }

    /**
     * 恢复原始的 stay_on_while_plugged_in 设置
     */
    private fun restoreStayOnSetting() {
        try {
            val value = if (originalStayOnValue >= 0) originalStayOnValue else 0
            val result = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "settings put global stay_on_while_plugged_in $value")
            )
            result.waitFor()
            Logger.d(TAG, "Restored stay_on_while_plugged_in to $value")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to restore stay_on setting: ${e.message}")
        }
    }

    /**
     * 通过 svc power 命令启用 stay-on
     *
     * 使用系统服务命令作为最后的降级方案。
     *
     * @return 成功返回 true
     */
    private fun enableStayOnViaSvc(): Boolean {
        return try {
            val result = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "svc power stayon true")
            )
            result.waitFor()
            result.exitValue() == 0
        } catch (e: Exception) {
            Logger.w(TAG, "svc power stayon failed: ${e.message}")
            false
        }
    }
}
