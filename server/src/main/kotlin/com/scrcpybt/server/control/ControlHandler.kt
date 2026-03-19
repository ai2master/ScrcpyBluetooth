package com.scrcpybt.server.control

import android.os.IBinder
import android.view.KeyEvent
import com.scrcpybt.common.protocol.message.ControlMessage
import com.scrcpybt.common.protocol.message.InputMessage
import com.scrcpybt.common.util.Logger
import com.scrcpybt.server.input.InputInjector

/**
 * 设备控制命令处理器
 *
 * 处理来自控制端的设备控制命令，包括：
 * - 物理按键模拟（电源、返回、主页、最近任务、音量）
 * - 屏幕电源控制（开/关屏）
 * - 通知面板展开
 * - 强制解锁和恢复锁定
 * - 虚拟显示器模式切换
 *
 * ### 实现原理
 * - 通过 InputInjector 注入按键事件
 * - 通过 PowerControl 控制屏幕电源状态
 * - 通过 ScreenUnlocker 处理解锁和锁屏
 * - 通过反射调用 IStatusBarService 展开通知面板
 *
 * @param inputInjector 输入事件注入器
 * @see PowerControl
 * @see ScreenUnlocker
 * @see InputInjector
 */
class ControlHandler(private val inputInjector: InputInjector) {

    /** 电源控制模块 */
    private val powerControl = PowerControl()

    /** 屏幕解锁模块 */
    private val screenUnlocker = ScreenUnlocker()

    /** 虚拟显示器命令回调（需要 Server 层面的协调） */
    var virtualDisplayCallback: VirtualDisplayCallback? = null

    /**
     * 虚拟显示器回调接口
     *
     * 用于将虚拟显示器控制命令传递到 Server 层处理。
     */
    interface VirtualDisplayCallback {
        /** 启用虚拟显示器模式 */
        fun onEnableVirtualDisplay()

        /** 禁用虚拟显示器模式 */
        fun onDisableVirtualDisplay()
    }

    /**
     * 获取屏幕解锁器实例
     *
     * @return ScreenUnlocker 实例
     */
    fun getScreenUnlocker(): ScreenUnlocker = screenUnlocker

    /**
     * 处理控制消息
     *
     * 根据命令类型执行对应的设备控制操作。
     *
     * @param msg 控制消息
     */
    fun handle(msg: ControlMessage) {
        when (msg.command) {
            ControlMessage.CMD_POWER -> injectKeyCode(KeyEvent.KEYCODE_POWER)
            ControlMessage.CMD_BACK -> injectKeyCode(KeyEvent.KEYCODE_BACK)
            ControlMessage.CMD_HOME -> injectKeyCode(KeyEvent.KEYCODE_HOME)
            ControlMessage.CMD_RECENTS -> injectKeyCode(KeyEvent.KEYCODE_APP_SWITCH)
            ControlMessage.CMD_VOLUME_UP -> injectKeyCode(KeyEvent.KEYCODE_VOLUME_UP)
            ControlMessage.CMD_VOLUME_DOWN -> injectKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN)
            ControlMessage.CMD_SCREEN_OFF -> powerControl.turnScreenOff()
            ControlMessage.CMD_SCREEN_ON -> powerControl.turnScreenOn()
            ControlMessage.CMD_EXPAND_NOTIFICATION -> expandNotificationPanel()
            ControlMessage.CMD_FORCE_UNLOCK -> handleForceUnlock(msg.param)
            ControlMessage.CMD_RESTORE_LOCK -> handleRestoreLock()
            ControlMessage.CMD_ENABLE_VIRTUAL_DISPLAY -> {
                Logger.i(TAG, "CMD_ENABLE_VIRTUAL_DISPLAY received")
                virtualDisplayCallback?.onEnableVirtualDisplay()
            }
            ControlMessage.CMD_DISABLE_VIRTUAL_DISPLAY -> {
                Logger.i(TAG, "CMD_DISABLE_VIRTUAL_DISPLAY received")
                virtualDisplayCallback?.onDisableVirtualDisplay()
            }
            else -> Logger.w(TAG, "Unknown control command: ${msg.command}")
        }
    }

    /**
     * 处理强制解锁命令
     *
     * @param param 参数（1 = 完全解锁，0 = 仅解锁屏幕）
     */
    private fun handleForceUnlock(param: Int) {
        val fullUnlock = (param == 1)
        screenUnlocker.fallbackUnlock(fullUnlock)
    }

    /**
     * 处理恢复锁定命令
     *
     * 在断开连接时恢复设备的锁定状态。
     */
    private fun handleRestoreLock() {
        screenUnlocker.onDisconnect()
    }

    /**
     * 注入按键码
     *
     * 模拟物理按键的按下和释放动作。
     *
     * @param keyCode Android KeyEvent 键码
     */
    private fun injectKeyCode(keyCode: Int) {
        val downMsg = InputMessage().apply {
            inputType = InputMessage.INPUT_TYPE_KEY
            action = InputMessage.ACTION_DOWN
            this.keyCode = keyCode
            metaState = 0
        }
        inputInjector.inject(downMsg)

        val upMsg = InputMessage().apply {
            inputType = InputMessage.INPUT_TYPE_KEY
            action = InputMessage.ACTION_UP
            this.keyCode = keyCode
            metaState = 0
        }
        inputInjector.inject(upMsg)
    }

    /**
     * 展开通知面板
     *
     * 通过反射调用 IStatusBarService.expandNotificationsPanel() 隐藏 API。
     */
    private fun expandNotificationPanel() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val statusBarService = serviceManagerClass
                .getMethod("getService", String::class.java)
                .invoke(null, "statusbar")

            val iStatusBarServiceStub = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            val statusBarManager = iStatusBarServiceStub
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, statusBarService)
            statusBarManager!!.javaClass.getMethod("expandNotificationsPanel").invoke(statusBarManager)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to expand notification panel", e)
        }
    }

    companion object {
        private const val TAG = "ControlHandler"
    }
}
