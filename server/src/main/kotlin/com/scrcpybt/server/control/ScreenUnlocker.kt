package com.scrcpybt.server.control

import com.scrcpybt.common.util.Logger

/**
 * 屏幕解锁和状态管理器
 *
 * 负责管理远程控制会话期间的屏幕状态。
 *
 * ### 虚拟显示器方案（Android 10+，推荐）
 * 不解锁物理屏幕（那会让任何有物理接触的人都能使用设备），而是创建一个
 * 完全独立于物理屏幕的虚拟显示器。这实现了类似 Windows RDP 的行为：
 *
 * - 远程用户在虚拟显示器上操作（有启动器、应用、完整 UI）
 * - 物理屏幕保持关闭或显示锁屏界面
 * - 物理用户无法看到或与远程会话交互
 * - 从不清除或修改锁屏凭据
 *
 * VirtualDisplayManager 处理实际的显示器创建。本类协调整体屏幕策略：
 *
 * 1. 虚拟显示器模式激活时：物理屏幕关闭，从虚拟显示器捕获
 * 2. Android < 10 的降级方案：保持唤醒 + 输入注入（安全性较低）
 * 3. 断开连接时自动清理
 *
 * ### 降级方案（Android < 10 或虚拟显示器失败）
 * 与 TeamViewer/AnyDesk 相同的方式：
 * - 保持屏幕唤醒（防止锁定）
 * - 如果已锁定则通过输入注入解锁
 * - 断开连接时自动锁定
 *
 * Screen unlocker and state manager that implements RDP-like virtual display mode
 * on Android 10+ and falls back to keep-awake + input injection on older versions.
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 * @see VirtualDisplayManager
 * @see PowerControl
 */
class ScreenUnlocker {

    /**
     * 锁屏凭据类型
     */
    enum class CredentialType {
        /** 无密码或滑动解锁 */
        NONE,
        /** PIN 码 */
        PIN,
        /** 密码 */
        PASSWORD,
        /** 图案 */
        PATTERN
    }

    /** 功能是否启用 */
    @Volatile
    private var enabled = false

    /** 锁屏凭据类型 */
    private var credentialType = CredentialType.NONE

    /** 锁屏凭据值（仅本地存储，从不传输） */
    private var credential: String? = null

    /** 是否正在管理屏幕状态 */
    @Volatile
    private var isManagingScreen = false

    /** 断开连接时自动锁定 */
    @Volatile
    var autoLockOnDisconnect = true

    /** 是否正在使用虚拟显示器模式 */
    @Volatile
    var usingVirtualDisplay = false
        private set

    /** 保存的 screen_off_timeout 值（用于降级模式恢复） */
    private var originalScreenOffTimeout: String? = null

    /** 保存的 stay_on_while_plugged_in 值 */
    private var originalStayOnWhilePluggedIn: String? = null

    /** 屏幕尺寸（用于输入注入） */
    private var screenWidth = 0
    private var screenHeight = 0

    /**
     * 启用屏幕管理功能
     *
     * @param type 锁屏凭据类型
     * @param credential 锁屏凭据值
     */
    fun enable(type: CredentialType, credential: String?) {
        this.credentialType = type
        this.credential = credential
        this.enabled = true
        Logger.i(TAG, "Screen management enabled, type=$type")
    }

    /**
     * 禁用屏幕管理功能
     */
    fun disable() {
        if (isManagingScreen) {
            onDisconnect()
        }
        enabled = false
        Logger.i(TAG, "Screen management disabled")
    }

    /**
     * 检查功能是否启用
     *
     * @return 启用返回 true
     */
    fun isEnabled(): Boolean = enabled

    /**
     * 会话断开连接时调用
     *
     * 恢复屏幕状态，根据模式执行不同的清理操作。
     */
    fun onDisconnect() {
        if (!isManagingScreen) return

        Logger.i(TAG, "Session ended, cleaning up screen state")

        if (usingVirtualDisplay) {
            // 虚拟显示器模式：仅需恢复物理显示器
            // VirtualDisplayManager.release() 由 Server.cleanup() 调用
            restorePhysicalDisplay()
            usingVirtualDisplay = false
        } else {
            // Fallback mode: restore timeout settings and lock
            restoreScreenSettings()
            lockScreen()
        }

        isManagingScreen = false
    }

    /**
     * Activate virtual display mode.
     * Called by Server when VirtualDisplayManager successfully creates a virtual display.
     * The physical screen is turned off -- remote user operates on the virtual display.
     */
    fun activateVirtualDisplayMode() {
        usingVirtualDisplay = true
        isManagingScreen = true

        // Turn off physical display -- remote user works on virtual display,
        // physical user sees nothing (or lock screen when they press power)
        turnOffPhysicalDisplay()

        Logger.i(TAG, "Virtual display mode active: physical screen off, remote on VD")
    }

    /**
     * Fallback: unlock via keep-awake + input injection.
     * Used when virtual display is not available (Android < 10 or creation failure).
     */
    fun fallbackUnlock(fullUnlock: Boolean) {
        if (!enabled) {
            Logger.w(TAG, "Unlock requested but feature is disabled")
            return
        }

        Logger.i(TAG, "Fallback unlock: fullUnlock=$fullUnlock")

        // Prevent future locks
        saveAndOverrideScreenSettings()

        // Wake and unlock if needed
        val screenOn = isScreenOn()
        val locked = isKeyguardLocked()

        if (!screenOn || locked) {
            if (screenWidth == 0 || screenHeight == 0) queryScreenDimensions()
            if (!screenOn) { wakeScreen(); sleep(500) }
            if (locked && fullUnlock) unlockViaInputInjection()
        }

        usingVirtualDisplay = false
        isManagingScreen = true
        Logger.i(TAG, "Fallback mode active: screen kept awake")
    }

    // ---- Physical display control ----

    private fun turnOffPhysicalDisplay() {
        try {
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            val token = getPhysicalDisplayToken(surfaceControlClass)
            if (token != null) {
                val setDisplayPowerMode = surfaceControlClass.getMethod(
                    "setDisplayPowerMode",
                    android.os.IBinder::class.java, Int::class.javaPrimitiveType
                )
                setDisplayPowerMode.invoke(null, token, POWER_MODE_OFF)
                Logger.d(TAG, "Physical display turned off via SurfaceControl")
                return
            }
        } catch (e: Exception) {
            Logger.w(TAG, "SurfaceControl.setDisplayPowerMode failed", e)
        }
        // Fallback
        execShell("input keyevent KEYCODE_SLEEP")
    }

    private fun restorePhysicalDisplay() {
        try {
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            val token = getPhysicalDisplayToken(surfaceControlClass)
            if (token != null) {
                val setDisplayPowerMode = surfaceControlClass.getMethod(
                    "setDisplayPowerMode",
                    android.os.IBinder::class.java, Int::class.javaPrimitiveType
                )
                setDisplayPowerMode.invoke(null, token, POWER_MODE_NORMAL)
                Logger.d(TAG, "Physical display restored via SurfaceControl")
                return
            }
        } catch (e: Exception) {
            Logger.w(TAG, "SurfaceControl.setDisplayPowerMode restore failed", e)
        }
        execShell("input keyevent KEYCODE_WAKEUP")
    }

    private fun getPhysicalDisplayToken(surfaceControlClass: Class<*>): android.os.IBinder? {
        // Try Android 12+ method
        try {
            val getIds = surfaceControlClass.getMethod("getPhysicalDisplayIds")
            val ids = getIds.invoke(null) as LongArray
            if (ids.isNotEmpty()) {
                val getToken = surfaceControlClass.getMethod(
                    "getPhysicalDisplayToken", Long::class.javaPrimitiveType
                )
                return getToken.invoke(null, ids[0]) as android.os.IBinder
            }
        } catch (_: NoSuchMethodException) {}

        // Try older methods
        try {
            val getToken = surfaceControlClass.getMethod(
                "getBuiltInDisplay", Int::class.javaPrimitiveType
            )
            return getToken.invoke(null, 0) as android.os.IBinder
        } catch (_: NoSuchMethodException) {}

        try {
            val getToken = surfaceControlClass.getMethod("getInternalDisplayToken")
            return getToken.invoke(null) as android.os.IBinder
        } catch (_: NoSuchMethodException) {}

        return null
    }

    // ---- Fallback: screen settings override ----

    private fun saveAndOverrideScreenSettings() {
        originalScreenOffTimeout = execShellRead("settings get system screen_off_timeout")
        originalStayOnWhilePluggedIn = execShellRead("settings get global stay_on_while_plugged_in")
        execShell("settings put system screen_off_timeout 2147483647")
        execShell("settings put global stay_on_while_plugged_in 7")
        execShell("svc power stayon true")
    }

    private fun restoreScreenSettings() {
        val timeout = originalScreenOffTimeout
        if (timeout != null && timeout != "null" && timeout.isNotBlank()) {
            execShell("settings put system screen_off_timeout $timeout")
        } else {
            execShell("settings put system screen_off_timeout 30000")
        }
        val stayOn = originalStayOnWhilePluggedIn
        if (stayOn != null && stayOn != "null" && stayOn.isNotBlank()) {
            execShell("settings put global stay_on_while_plugged_in $stayOn")
        } else {
            execShell("settings put global stay_on_while_plugged_in 0")
        }
        execShell("svc power stayon false")
    }

    // ---- Input injection unlock ----

    private fun unlockViaInputInjection() {
        swipeUp()
        sleep(500)
        if (credentialType != CredentialType.NONE && credential != null) {
            when (credentialType) {
                CredentialType.PIN -> injectPin(credential!!)
                CredentialType.PASSWORD -> injectPassword(credential!!)
                CredentialType.PATTERN -> injectPattern(credential!!)
                CredentialType.NONE -> {}
            }
            sleep(300)
        }
    }

    private fun injectPin(pin: String) {
        for (digit in pin) {
            if (digit in '0'..'9') {
                execShell("input keyevent ${7 + (digit - '0')}")
                sleep(50)
            }
        }
        sleep(100)
        execShell("input keyevent KEYCODE_ENTER")
    }

    private fun injectPassword(password: String) {
        val escaped = password
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("$", "\\$").replace("`", "\\`")
            .replace(" ", "%s")
        execShell("input text \"$escaped\"")
        sleep(100)
        execShell("input keyevent KEYCODE_ENTER")
    }

    private fun injectPattern(pattern: String) {
        if (pattern.length < 2) return
        val gridSize = (minOf(screenWidth, screenHeight) * 0.6).toInt()
        val cellSize = gridSize / 3
        val gridLeft = (screenWidth - gridSize) / 2 + cellSize / 2
        val gridTop = (screenHeight / 2) - gridSize / 2 + cellSize / 2 + (screenHeight * 0.05).toInt()

        val coords = pattern.mapNotNull { ch ->
            val node = ch - '0'
            if (node in 1..9) {
                val row = (node - 1) / 3
                val col = (node - 1) % 3
                Pair(gridLeft + col * cellSize, gridTop + row * cellSize)
            } else null
        }
        if (coords.size < 2) return

        // Chain swipes for pattern
        for (i in 0 until coords.size - 1) {
            val (x1, y1) = coords[i]
            val (x2, y2) = coords[i + 1]
            execShell("input touchscreen swipe $x1 $y1 $x2 $y2 100")
        }
    }

    // ---- Screen state queries ----

    private fun isScreenOn(): Boolean {
        val result = execShellRead("dumpsys power | grep 'Display Power'") ?: return true
        return result.contains("state=ON")
    }

    private fun isKeyguardLocked(): Boolean {
        val result = execShellRead(
            "dumpsys window | grep 'mDreamingLockscreen\\|isStatusBarKeyguard\\|mShowingLockscreen'"
        ) ?: return false
        return result.contains("true")
    }

    private fun wakeScreen() { execShell("input keyevent KEYCODE_WAKEUP") }
    private fun lockScreen() { execShell("input keyevent KEYCODE_SLEEP") }

    private fun swipeUp() {
        if (screenWidth == 0 || screenHeight == 0) queryScreenDimensions()
        val x = screenWidth / 2
        execShell("input touchscreen swipe $x ${(screenHeight * 0.75).toInt()} $x ${(screenHeight * 0.25).toInt()} 200")
    }

    private fun queryScreenDimensions() {
        screenWidth = 1080; screenHeight = 2400
        val sizeStr = execShellRead("wm size") ?: return
        try {
            for (line in sizeStr.split("\n")) {
                if ("x" in line) {
                    val dims = line.substring(line.lastIndexOf(' ') + 1).trim().split("x")
                    screenWidth = dims[0].toInt()
                    screenHeight = dims[1].toInt()
                    break
                }
            }
        } catch (_: Exception) {}
    }

    // ---- Shell helpers ----

    private fun execShell(command: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command)).waitFor()
        } catch (e: Exception) {
            Logger.e(TAG, "Shell exec failed: $command", e)
        }
    }

    private fun execShellRead(command: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val result = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            result
        } catch (e: Exception) { null }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    companion object {
        private const val TAG = "ScreenUnlocker"
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2
    }
}
