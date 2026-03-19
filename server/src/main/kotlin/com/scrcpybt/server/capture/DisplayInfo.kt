package com.scrcpybt.server.capture

import android.graphics.Point
import android.os.IBinder
import com.scrcpybt.common.util.Logger

/**
 * 显示器信息查询类
 *
 * 通过反射调用 Android 隐藏 API 查询显示器的分辨率、DPI 和旋转角度。
 * 由于运行在 shell 用户权限下（通过 app_process），可以访问系统服务。
 *
 * ### 查询方式
 * 1. 优先尝试通过 DisplayManager 系统服务查询（IDisplayManager）
 * 2. 如果失败则降级到 WindowManager 系统服务（IWindowManager）
 * 3. 如果都失败则使用默认值（1080x2400@420dpi）
 *
 * ### 旋转处理
 * rotation 值的含义：
 * - 0: 自然方向（通常为竖屏）
 * - 1: 逆时针旋转 90°（横屏）
 * - 2: 旋转 180°（倒置竖屏）
 * - 3: 顺时针旋转 90°（横屏）
 *
 * @param displayId 显示器 ID（默认为 0，即主显示器）
 */
class DisplayInfo(private val displayId: Int = 0) {

    /** 逻辑宽度（像素） */
    var width: Int = 0
        private set

    /** 逻辑高度（像素） */
    var height: Int = 0
        private set

    /** 逻辑密度（DPI） */
    var density: Int = 0
        private set

    /** 当前旋转角度（0/1/2/3） */
    var rotation: Int = 0
        private set

    init {
        queryDisplayInfo()
    }

    /**
     * 查询显示器信息
     *
     * 通过反射调用 IDisplayManager.getDisplayInfo() 获取显示器参数。
     * 如果失败则降级到 queryViaWindowManager()。
     */
    private fun queryDisplayInfo() {
        try {
            // 通过 DisplayManager 获取显示器信息（隐藏 API）
            // ServiceManager.getService("display") -> IDisplayManager
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getService = serviceManagerClass.getMethod("getService", String::class.java)
            val displayBinder = getService.invoke(null, "display")

            // IDisplayManager.Stub.asInterface(binder)
            val iDisplayManagerStubClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val asInterface = iDisplayManagerStubClass.getMethod("asInterface", IBinder::class.java)
            val displayManager = asInterface.invoke(null, displayBinder)

            // 获取指定显示器 ID 的信息
            val getDisplayInfo = displayManager!!.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            val info = getDisplayInfo.invoke(displayManager, displayId)

            if (info != null) {
                val displayInfoClass = info.javaClass
                width = displayInfoClass.getField("logicalWidth").getInt(info)
                height = displayInfoClass.getField("logicalHeight").getInt(info)
                density = displayInfoClass.getField("logicalDensityDpi").getInt(info)
                rotation = displayInfoClass.getField("rotation").getInt(info)
            }

            Logger.i(TAG, "Display[$displayId]: ${width}x$height @${density}dpi rotation=$rotation")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to get display info via DisplayManager, trying WindowManager", e)
            queryViaWindowManager()
        }
    }

    /**
     * 通过 WindowManager 查询显示器信息
     *
     * 当 DisplayManager 方式失败时使用的降级方案。
     * 通过反射调用 IWindowManager 的相关方法获取显示器参数。
     */
    private fun queryViaWindowManager() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getService = serviceManagerClass.getMethod("getService", String::class.java)
            val windowBinder = getService.invoke(null, "window")

            val iWindowManagerStubClass = Class.forName("android.view.IWindowManager\$Stub")
            val asInterface = iWindowManagerStubClass.getMethod("asInterface", IBinder::class.java)
            val windowManager = asInterface.invoke(null, windowBinder)

            // getInitialDisplaySize / getBaseDisplaySize
            val size = Point()
            val getBaseDisplaySize = windowManager!!.javaClass.getMethod(
                "getBaseDisplaySize", Int::class.javaPrimitiveType, Point::class.java
            )
            getBaseDisplaySize.invoke(windowManager, 0, size)
            width = size.x
            height = size.y

            val getBaseDisplayDensity = windowManager.javaClass.getMethod(
                "getBaseDisplayDensity", Int::class.javaPrimitiveType
            )
            density = getBaseDisplayDensity.invoke(windowManager, 0) as Int

            val getDefaultDisplayRotation = windowManager.javaClass.getMethod("getDefaultDisplayRotation")
            rotation = getDefaultDisplayRotation.invoke(windowManager) as Int

            Logger.i(TAG, "Display (WM): ${width}x$height @${density}dpi rotation=$rotation")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to get display info", e)
            // 降级默认值
            width = 1080
            height = 2400
            density = 420
            rotation = 0
        }
    }

    /**
     * 获取考虑旋转后的有效宽度
     *
     * 当旋转为 0 或 2（竖屏）时返回 width，
     * 当旋转为 1 或 3（横屏）时返回 height。
     *
     * @return 有效宽度
     */
    fun getEffectiveWidth(): Int = if (rotation % 2 == 0) width else height

    /**
     * 获取考虑旋转后的有效高度
     *
     * 当旋转为 0 或 2（竖屏）时返回 height，
     * 当旋转为 1 或 3（横屏）时返回 width。
     *
     * @return 有效高度
     */
    fun getEffectiveHeight(): Int = if (rotation % 2 == 0) height else width

    companion object {
        private const val TAG = "DisplayInfo"
    }
}
