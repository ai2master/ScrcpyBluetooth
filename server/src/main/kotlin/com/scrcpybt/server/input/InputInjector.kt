package com.scrcpybt.server.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.scrcpybt.common.protocol.message.InputMessage
import com.scrcpybt.common.util.Logger
import java.lang.reflect.Method

/**
 * 输入事件注入器
 *
 * 通过反射调用 Android 隐藏 API 将输入事件注入到系统中。
 * 需要以 shell 用户（UID 2000）身份运行（通过 app_process）。
 *
 * ### 技术实现
 * - 反射获取 InputManager 单例
 * - 调用 `InputManager.injectInputEvent(InputEvent, int)` 注入事件
 * - 支持设置目标显示器 ID（Android 10+）以路由事件到虚拟显示器
 *
 * ### 支持的输入类型
 * - 触摸事件：单点触摸和多点触摸（MotionEvent）
 * - 按键事件：物理按键和虚拟键盘（KeyEvent）
 *
 * ### 虚拟显示器支持
 * 通过 `setDisplayId()` 方法（Android 10+ 隐藏 API）将输入事件路由到指定显示器，
 * 使得虚拟显示器模式下的输入可以正确工作。
 *
 * @see InputMessage
 * @see MotionEvent
 * @see KeyEvent
 */
class InputInjector {

    /** InputManager 实例（通过反射获取） */
    private var inputManager: Any? = null

    /** injectInputEvent 方法引用 */
    private var injectInputEventMethod: Method? = null

    /** setDisplayId 方法引用（Android 10+） */
    private var setDisplayIdMethod: Method? = null

    /** 是否成功初始化 */
    private var initialized = false

    /** 令牌桶速率限制器：防止输入事件洪泛攻击 */
    private var tokenBucket = MAX_EVENTS_PER_SECOND.toLong()
    private var lastRefillTime = System.currentTimeMillis()
    /** 被丢弃的事件计数（用于日志警告） */
    private var droppedEventCount = 0

    /**
     * 目标显示器 ID
     *
     * - 0 = 主显示器（默认）
     * - 设置为虚拟显示器 ID 可将事件路由到虚拟显示器
     */
    var targetDisplayId: Int = 0

    init {
        initialize()
    }

    /**
     * 初始化输入注入器
     *
     * 通过反射获取 InputManager 实例和相关方法引用。
     */
    private fun initialize() {
        try {
            // 通过反射获取 InputManager 实例
            val inputManagerClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = inputManagerClass.getMethod("getInstance")
            inputManager = getInstance.invoke(null)

            // 获取 injectInputEvent 方法
            injectInputEventMethod = inputManagerClass.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )

            // 获取 setDisplayId 方法（隐藏 API，Android 10+ 可用）
            try {
                setDisplayIdMethod = InputEvent::class.java.getMethod(
                    "setDisplayId", Int::class.javaPrimitiveType
                )
                Logger.i(TAG, "setDisplayId method available")
            } catch (e: Exception) {
                Logger.w(TAG, "setDisplayId method not available (Android < 10?)", e)
            }

            initialized = true
            Logger.i(TAG, "InputInjector initialized")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize InputInjector", e)
        }
    }

    /**
     * 注入从控制端接收到的输入消息
     *
     * 根据输入类型（触摸或按键）调用对应的注入方法。
     *
     * @param msg 输入消息
     */
    fun inject(msg: InputMessage) {
        if (!initialized) {
            Logger.w(TAG, "InputInjector not initialized, skipping input")
            return
        }

        // 令牌桶速率限制：防止恶意控制端发送海量输入事件
        if (!tryConsumeToken()) {
            droppedEventCount++
            if (droppedEventCount % 100 == 1) {
                Logger.w(TAG, "输入速率超限，已丢弃 $droppedEventCount 个事件")
            }
            return
        }

        if (msg.inputType == InputMessage.INPUT_TYPE_KEY) {
            injectKeyEvent(msg)
        } else {
            injectTouchEvent(msg)
        }
    }

    /**
     * 令牌桶：每秒填充 MAX_EVENTS_PER_SECOND 个令牌，消费一个令牌注入一次事件。
     * 超过速率的事件直接丢弃。
     */
    private fun tryConsumeToken(): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefillTime
        if (elapsed > 0) {
            val refill = elapsed * MAX_EVENTS_PER_SECOND / 1000
            tokenBucket = minOf(tokenBucket + refill, MAX_EVENTS_PER_SECOND.toLong())
            lastRefillTime = now
        }
        return if (tokenBucket > 0) { tokenBucket--; true } else false
    }

    /**
     * 注入触摸事件
     *
     * 将 InputMessage 转换为 Android MotionEvent 并注入。
     * 支持单点触摸和多点触摸。
     *
     * @param msg 输入消息
     */
    private fun injectTouchEvent(msg: InputMessage) {
        try {
            val action = convertTouchAction(msg.action)
            val pointers = msg.pointers
            if (pointers == null || pointers.isEmpty()) return

            val now = SystemClock.uptimeMillis()

            if (pointers.size == 1) {
                // 单点触摸
                val event = MotionEvent.obtain(
                    now, now, action,
                    pointers[0].x.toFloat(), pointers[0].y.toFloat(),
                    pointers[0].pressure / 65535f,
                    1.0f, 0, 1.0f, 1.0f,
                    0, 0
                )
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                injectEvent(event)
                event.recycle()
            } else {
                // 多点触摸
                val properties = Array(pointers.size) { i ->
                    MotionEvent.PointerProperties().apply {
                        id = pointers[i].pointerId.toInt()
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                val coords = Array(pointers.size) { i ->
                    MotionEvent.PointerCoords().apply {
                        x = pointers[i].x.toFloat()
                        y = pointers[i].y.toFloat()
                        pressure = pointers[i].pressure / 65535f
                        size = 1.0f
                    }
                }

                val event = MotionEvent.obtain(
                    now, now, action, pointers.size,
                    properties, coords,
                    0, 0, 1.0f, 1.0f,
                    0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                )
                injectEvent(event)
                event.recycle()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to inject touch event", e)
        }
    }

    /**
     * 注入按键事件
     *
     * 将 InputMessage 转换为 Android KeyEvent 并注入。
     *
     * @param msg 输入消息
     */
    private fun injectKeyEvent(msg: InputMessage) {
        try {
            val now = SystemClock.uptimeMillis()
            val action = if (msg.action == InputMessage.ACTION_DOWN)
                KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP

            val event = KeyEvent(
                now, now, action,
                msg.keyCode, 0, msg.metaState,
                -1, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
            injectEvent(event)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to inject key event", e)
        }
    }

    /**
     * 注入事件到系统
     *
     * 通过反射调用 InputManager.injectInputEvent() 将事件注入到 Android 输入系统。
     * 如果设置了目标显示器 ID，会先通过 setDisplayId() 路由事件。
     *
     * @param event 输入事件（MotionEvent 或 KeyEvent）
     */
    private fun injectEvent(event: InputEvent) {
        try {
            // 设置显示器 ID 以将事件路由到正确的显示器
            // （触摸/鼠标和按键事件都需要此设置才能支持虚拟显示器）
            if (targetDisplayId != 0 && setDisplayIdMethod != null) {
                try {
                    setDisplayIdMethod?.invoke(event, targetDisplayId)
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to set display ID to $targetDisplayId", e)
                }
            }

            injectInputEventMethod?.invoke(inputManager, event, INJECT_MODE_ASYNC)
        } catch (e: Exception) {
            Logger.e(TAG, "injectInputEvent failed", e)
        }
    }

    /**
     * 转换触摸动作类型
     *
     * 将协议层的动作类型转换为 Android MotionEvent 的动作常量。
     *
     * @param action 协议层动作类型
     * @return MotionEvent 动作常量
     */
    private fun convertTouchAction(action: Byte): Int {
        return when (action) {
            InputMessage.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            InputMessage.ACTION_UP -> MotionEvent.ACTION_UP
            InputMessage.ACTION_MOVE -> MotionEvent.ACTION_MOVE
            else -> MotionEvent.ACTION_MOVE
        }
    }

    companion object {
        private const val TAG = "InputInjector"

        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC - 异步注入模式 */
        private const val INJECT_MODE_ASYNC = 0

        /** 每秒最大允许注入事件数（令牌桶容量/填充速率） */
        private const val MAX_EVENTS_PER_SECOND = 200
    }
}
