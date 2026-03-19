package com.scrcpybt.server.clipboard

import android.content.ClipData
import android.content.Context
import android.os.IBinder
import com.scrcpybt.common.protocol.message.ClipboardMessage
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.util.Logger

/**
 * 剪贴板处理器
 *
 * 处理被控设备上的剪贴板操作，支持双向剪贴板同步。
 *
 * ### 技术实现
 * - 通过反射访问 system_server 上下文中的 ClipboardManager
 * - 使用 IClipboard AIDL 接口进行系统级剪贴板操作
 * - 以 com.android.shell 包名身份调用（shell UID 权限）
 *
 * ### 功能
 * - **PUSH**: 控制端推送剪贴板内容到被控端
 * - **PULL**: 控制端请求被控端的剪贴板内容
 *
 * ### 应用场景
 * - 远程控制时的文本复制粘贴
 * - 跨设备的剪贴板共享
 * - 自动同步剪贴板内容
 *
 * @see ClipboardMessage
 * @see ClipboardManager
 */
class ClipboardHandler {

    /** IClipboard 服务实例 */
    private var clipboardManager: Any? = null

    /** 是否成功初始化 */
    private var initialized = false

    init {
        initialize()
    }

    /**
     * 初始化剪贴板处理器
     *
     * 通过反射获取 IClipboard 系统服务。
     */
    private fun initialize() {
        try {
            // 通过 ServiceManager 获取 ClipboardManager
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getService = serviceManagerClass.getMethod("getService", String::class.java)
            val clipboardBinder = getService.invoke(null, Context.CLIPBOARD_SERVICE) as IBinder

            // IClipboard.Stub.asInterface(binder)
            val iClipboardStub = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = iClipboardStub.getMethod("asInterface", IBinder::class.java)
            clipboardManager = asInterface.invoke(null, clipboardBinder)

            initialized = true
            Logger.i(TAG, "ClipboardHandler initialized")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize ClipboardHandler", e)
        }
    }

    /**
     * 获取当前剪贴板文本内容
     *
     * @return 剪贴板文本，如果为空或失败则返回 null
     */
    fun getClipboardContent(): String? {
        if (!initialized) return null

        return try {
            // IClipboard.getPrimaryClip(String callingPackage, int userId)
            val getPrimaryClip = clipboardManager!!.javaClass.getMethod(
                "getPrimaryClip", String::class.java, Int::class.javaPrimitiveType
            )
            val clipData = getPrimaryClip.invoke(clipboardManager, "com.android.shell", 0) as? ClipData

            clipData?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to get clipboard content", e)
            null
        }
    }

    /**
     * 设置剪贴板文本内容
     *
     * @param text 要设置的文本内容
     */
    fun setClipboardContent(text: String) {
        if (!initialized) return

        try {
            // 创建 ClipData
            val clipData = ClipData.newPlainText("scrcpy_bt", text)

            // IClipboard.setPrimaryClip(ClipData clip, String callingPackage, int userId)
            val setPrimaryClip = clipboardManager!!.javaClass.getMethod(
                "setPrimaryClip",
                ClipData::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            setPrimaryClip.invoke(clipboardManager, clipData, "com.android.shell", 0)
            Logger.d(TAG, "Clipboard content set: ${text.length} chars")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to set clipboard content", e)
        }
    }

    /**
     * 处理来自控制端的剪贴板消息
     *
     * 根据方向执行不同操作：
     * - DIR_PUSH: 控制端推送内容到被控端
     * - DIR_PULL: 控制端请求被控端的剪贴板内容
     *
     * @param msg 剪贴板消息
     * @param writer 消息写入器（用于发送响应）
     */
    fun handleClipboardMessage(msg: ClipboardMessage, writer: MessageWriter) {
        try {
            when (msg.direction) {
                ClipboardMessage.DIR_PUSH -> {
                    // 控制端正在推送剪贴板内容给我们
                    setClipboardContent(msg.text)
                }
                ClipboardMessage.DIR_PULL -> {
                    // 控制端正在请求我们的剪贴板内容
                    val content = getClipboardContent()
                    if (content != null) {
                        val response = ClipboardMessage(
                            content,
                            ClipboardMessage.DIR_PUSH
                        )
                        writer.writeMessage(response)
                        Logger.d(TAG, "Sent clipboard content to controller: ${content.length} chars")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error handling clipboard message", e)
        }
    }

    companion object {
        private const val TAG = "ClipboardHandler"
    }
}
