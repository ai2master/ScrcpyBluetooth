package com.scrcpybt.server.control

import android.os.IBinder
import com.scrcpybt.common.util.Logger

/**
 * 电源控制模块
 *
 * 通过反射调用 Android 隐藏 API 控制显示器电源状态。
 * 用于在保持捕获运行的情况下关闭被控设备的屏幕。
 *
 * ### 技术实现
 * - 反射调用 SurfaceControl.setDisplayPowerMode() 方法
 * - 兼容多个 Android 版本的 API 差异：
 *   - Android 12+: getPhysicalDisplayToken(long)
 *   - 较早版本: getBuiltInDisplay(int) 或 getInternalDisplayToken()
 *
 * ### 应用场景
 * - 虚拟显示器模式下关闭物理屏幕以节省电量和保护隐私
 * - 远程控制时可以关闭屏幕而不影响捕获
 *
 * Power control module that uses Android hidden APIs to control display power state,
 * allowing screen to be turned off while maintaining virtual display capture.
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 * @see SurfaceControl
 */
class PowerControl {

    /**
     * 关闭物理显示器
     *
     * 即使屏幕关闭，通过虚拟显示器的捕获仍然可以继续。
     */
    fun turnScreenOff() {
        setDisplayPowerMode(POWER_MODE_OFF)
    }

    /**
     * 打开物理显示器
     */
    fun turnScreenOn() {
        setDisplayPowerMode(POWER_MODE_NORMAL)
    }

    /**
     * 设置显示器电源模式
     *
     * 通过反射调用 SurfaceControl.setDisplayPowerMode() 隐藏 API。
     * 兼容不同 Android 版本的 API 变化。
     *
     * @param mode 电源模式（POWER_MODE_OFF 或 POWER_MODE_NORMAL）
     */
    private fun setDisplayPowerMode(mode: Int) {
        try {
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")

            // 获取物理显示器令牌
            val displayToken: IBinder? = try {
                // Android 12+: getPhysicalDisplayToken(long)
                val getPhysicalDisplayIds = surfaceControlClass.getMethod("getPhysicalDisplayIds")
                val ids = getPhysicalDisplayIds.invoke(null) as LongArray
                val getPhysicalDisplayToken = surfaceControlClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                getPhysicalDisplayToken.invoke(null, ids[0]) as IBinder
            } catch (e: NoSuchMethodException) {
                // 较早的 Android 版本: getInternalDisplayToken() 或 getBuiltInDisplay(int)
                try {
                    val getBuiltInDisplay = surfaceControlClass.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
                    getBuiltInDisplay.invoke(null, 0) as IBinder // BUILT_IN_DISPLAY_ID_MAIN = 0
                } catch (e2: NoSuchMethodException) {
                    val getInternalDisplayToken = surfaceControlClass.getMethod("getInternalDisplayToken")
                    getInternalDisplayToken.invoke(null) as IBinder
                }
            }

            displayToken?.let {
                val setDisplayPowerModeMethod = surfaceControlClass.getMethod(
                    "setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType
                )
                setDisplayPowerModeMethod.invoke(null, it, mode)
                Logger.i(TAG, "Display power mode set to: $mode")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to set display power mode", e)
        }
    }

    companion object {
        private const val TAG = "PowerControl"

        /** SurfaceControl.POWER_MODE_OFF - 关闭显示器 */
        private const val POWER_MODE_OFF = 0

        /** SurfaceControl.POWER_MODE_NORMAL - 正常显示 */
        private const val POWER_MODE_NORMAL = 2
    }
}
