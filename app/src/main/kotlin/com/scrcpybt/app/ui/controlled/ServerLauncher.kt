package com.scrcpybt.app.ui.controlled

import android.content.Context
import com.scrcpybt.common.transport.TransportType
import com.scrcpybt.common.util.Logger
import java.io.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 服务端启动器：在被控设备上启动 scrcpy-bt 服务端进程
 *
 * 工作原理：
 * - 从 APK assets 中提取 server.jar 到缓存目录
 * - 使用 root 权限通过 app_process 启动服务端主类
 * - 支持虚拟显示模式和强制解锁配置
 *
 * 启动命令示例：
 * ```
 * su -c "CLASSPATH=/path/to/server.jar app_process / com.scrcpybt.server.ServerMain --transport=bluetooth"
 * ```
 *
 * 关键技术点：
 * - app_process 是 Android 系统工具，可以直接运行 Java 类（绕过 APK 安装）
 * - 需要 root 权限才能访问系统 API（如屏幕捕获、输入注入）
 * - 服务端运行在独立进程中，通过 Socket 与客户端通信
 */
class ServerLauncher(private val context: Context) {
    companion object {
        private const val TAG = "ServerLauncher"
        /** 服务端 JAR 文件名 */
        private const val SERVER_JAR_NAME = "server.jar"
    }

    /** 服务端进程实例 */
    private var serverProcess: Process? = null
    /** 用于异步启动服务端的线程池 */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * 服务端启动回调接口
     */
    interface Callback {
        /** 服务端已启动 */
        fun onStarted()
        /** 服务端启动失败 */
        fun onError(error: String)
        /** 服务端已停止 */
        fun onStopped()
    }

    // 虚拟显示配置（在启动前设置）
    /** 是否启用虚拟显示模式 */
    private var virtualDisplayEnabled = false
    /** 在虚拟屏幕上启动的应用组件名 */
    private var startApp: String? = null
    /** 是否绕过 FLAG_SECURE 安全标志 */
    private var bypassSecureFlag = false

    // 强制解锁配置（在启动前设置，Android 10 以下的回退方案）
    /** 是否启用强制解锁 */
    private var forceUnlockEnabled = false
    /** 凭据类型索引：0=无, 1=PIN, 2=密码, 3=图案 */
    private var credentialTypeIndex = 0
    /** 解锁凭据内容 */
    private var credential = ""

    /**
     * 启用虚拟显示模式
     *
     * 虚拟显示模式会创建一个独立的虚拟屏幕用于远程控制：
     * - 物理屏幕可以保持关闭或锁定状态（类似 RDP 的行为）
     * - 需要 Android 10+ (API 29)
     *
     * @param enabled 是否启用虚拟显示
     */
    fun setVirtualDisplayEnabled(enabled: Boolean) {
        this.virtualDisplayEnabled = enabled
    }

    /**
     * 设置在虚拟显示上启动的应用，防止黑屏
     *
     * @param component 组件名（例如 "com.example.app/.MainActivity"）或 null 自动检测
     */
    fun setStartApp(component: String?) {
        this.startApp = component
    }

    /**
     * 启用 FLAG_SECURE 绕过，允许捕获受保护应用的内容
     *
     * 警告：这会禁用一个安全特性，仅在特定使用场景下启用
     *
     * @param enabled 是否绕过安全标志
     */
    fun setBypassSecureFlag(enabled: Boolean) {
        this.bypassSecureFlag = enabled
    }

    /**
     * 设置强制解锁配置
     *
     * 必须在 launch() 之前调用。凭据作为服务端参数传递，
     * 仅存储在被控设备上，永远不会通过网络传输。
     *
     * 这是 Android 10 以下版本的回退方案（因为虚拟显示不可用）
     *
     * @param enabled 是否启用强制解锁
     * @param credentialTypeIndex 凭据类型索引：0=无, 1=PIN, 2=密码, 3=图案
     * @param credential 实际的解锁凭据
     */
    fun setForceUnlockConfig(enabled: Boolean, credentialTypeIndex: Int, credential: String) {
        this.forceUnlockEnabled = enabled
        this.credentialTypeIndex = credentialTypeIndex
        this.credential = credential
    }

    /**
     * 启动服务端进程
     *
     * @param transport 传输方式（USB ADB 或蓝牙 RFCOMM）
     * @param callback 启动回调
     */
    fun launch(transport: TransportType, callback: Callback) {
        executor.submit {
            try {
                // 从 assets 提取 server.jar 到缓存目录
                val serverJar = extractServerJar()
                    ?: run {
                        callback.onError("Failed to extract server.jar")
                        return@submit
                    }

                // 设置为可读
                serverJar.setReadable(true, false)

                // 构建启动命令
                val cmdBuilder = StringBuilder().apply {
                    append("CLASSPATH=${serverJar.absolutePath} app_process / com.scrcpybt.server.ServerMain --transport=${transport.cliName}")
                }

                // 如果启用虚拟显示，追加相关参数
                if (virtualDisplayEnabled) {
                    cmdBuilder.append(" --virtual-display=true")
                    if (!startApp.isNullOrBlank()) {
                        cmdBuilder.append(" --start-app=$startApp")
                    }
                    if (bypassSecureFlag) {
                        cmdBuilder.append(" --bypass-secure-flag=true")
                    }
                }

                // 如果启用强制解锁，追加相关参数（Android 10 以下的回退方案）
                if (forceUnlockEnabled) {
                    val typeNames = arrayOf("none", "pin", "password", "pattern")
                    val typeName = typeNames.getOrElse(credentialTypeIndex) { "none" }
                    cmdBuilder.append(" --force-unlock=true")
                    cmdBuilder.append(" --credential-type=$typeName")
                    if (credentialTypeIndex > 0 && credential.isNotEmpty()) {
                        cmdBuilder.append(" --credential=$credential")
                    }
                }

                val command = cmdBuilder.toString()

                Logger.i(TAG, "Launching: su -c '$command'")

                // 通过 su 执行命令
                val pb = ProcessBuilder("su", "-c", command)
                pb.redirectErrorStream(true)
                serverProcess = pb.start()

                callback.onStarted()

                // 读取并记录服务端输出
                BufferedReader(InputStreamReader(serverProcess!!.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Logger.i(TAG, "[server] $line")
                    }
                }

                val exitCode = serverProcess!!.waitFor()
                Logger.i(TAG, "Server exited with code: $exitCode")
                callback.onStopped()

            } catch (e: Exception) {
                Logger.e(TAG, "Server launch failed", e)
                callback.onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 停止服务端进程
     */
    fun stop() {
        serverProcess?.let {
            it.destroy()
            serverProcess = null
            Logger.i(TAG, "Server process destroyed")
        }
    }

    /**
     * 从 APK assets 中提取 server.jar 到应用缓存目录
     *
     * @return 提取后的 server.jar 文件对象，失败返回 null
     */
    private fun extractServerJar(): File? {
        val output = File(context.cacheDir, SERVER_JAR_NAME)

        return try {
            context.assets.open(SERVER_JAR_NAME).use { input ->
                FileOutputStream(output).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                }
            }
            Logger.i(TAG, "Server jar extracted to: ${output.absolutePath}")
            output
        } catch (e: IOException) {
            Logger.e(TAG, "Failed to extract server jar", e)
            null
        }
    }
}
