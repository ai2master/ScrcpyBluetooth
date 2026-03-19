package com.scrcpybt.common.util

import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 统一日志工具（单例）。
 *
 * 自动检测运行环境：
 * - Android 环境：通过反射调用 android.util.Log（Tag 前缀为 "ScrcpyBT:"）
 * - 非 Android 环境（服务端 app_process）：输出到 stdout/stderr
 *
 * 这样 common 模块的代码无需依赖 Android SDK 即可使用日志，
 * 服务端运行时也能正常输出调试信息。
 */
object Logger {
    /** 日志级别 */
    enum class Level { DEBUG, INFO, WARN, ERROR }

    /** 最低日志级别（低于此级别的日志被忽略） */
    var minLevel = Level.DEBUG
    /** 时间戳格式 */
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    /** 是否运行在 Android 环境（检测 android.util.Log 类是否存在） */
    private val useAndroidLog = runCatching { Class.forName("android.util.Log") }.isSuccess

    /** Debug 级别日志 */
    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg, null)
    /** Info 级别日志 */
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg, null)
    /** Warning 级别日志 */
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Level.WARN, tag, msg, t)
    /** Error 级别日志 */
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.ERROR, tag, msg, t)

    /** 统一日志入口：级别过滤 → 环境分发 */
    private fun log(level: Level, tag: String, msg: String, t: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        if (useAndroidLog) logAndroid(level, tag, msg, t) else logStdout(level, tag, msg, t)
    }

    /** Android 环境：通过反射调用 android.util.Log */
    private fun logAndroid(level: Level, tag: String, msg: String, t: Throwable?) {
        try {
            val logClass = Class.forName("android.util.Log")
            val method = when (level) {
                Level.DEBUG -> logClass.getMethod("d", String::class.java, String::class.java)
                Level.INFO -> logClass.getMethod("i", String::class.java, String::class.java)
                Level.WARN -> logClass.getMethod("w", String::class.java, String::class.java)
                Level.ERROR -> logClass.getMethod("e", String::class.java, String::class.java)
            }
            method.invoke(null, "ScrcpyBT:$tag", msg)
            t?.let { method.invoke(null, "ScrcpyBT:$tag", it.stackTraceToString()) }
        } catch (_: Exception) { logStdout(level, tag, msg, t) }
    }

    /** 非 Android 环境：输出到 stdout（普通日志）或 stderr（警告/错误） */
    private fun logStdout(level: Level, tag: String, msg: String, t: Throwable?) {
        val out: PrintStream = if (level == Level.ERROR || level == Level.WARN) System.err else System.out
        out.println("[${dateFormat.format(Date())}] ${level.name[0]}/$tag: $msg")
        t?.printStackTrace(out)
    }
}
