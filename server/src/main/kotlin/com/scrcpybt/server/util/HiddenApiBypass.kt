package com.scrcpybt.server.util

import com.scrcpybt.common.util.Logger

/**
 * 隐藏 API 绕过工具
 *
 * 用于绕过 Android 隐藏 API 限制（Android 9+ 引入的限制）。
 *
 * ### 背景
 * Android 9 (Pie) 开始限制应用通过反射访问隐藏 API（非 SDK 接口）。
 * 然而，当通过 app_process 以 shell 用户运行时，大多数限制不适用。
 *
 * ### 工作原理
 * 使用 `dalvik.system.VMRuntime.setHiddenApiExemptions()` 方法
 * 将所有以 "L" 开头的类（即所有类）豁免于隐藏 API 检查。
 *
 * ### 使用场景
 * - 在 ServerMain.main() 入口处调用 `exempt()`
 * - 为后续的反射调用（DisplayManager、PowerManager 等）解除限制
 * - 提供降级方案，即使豁免失败也能继续运行（shell UID 通常有足够权限）
 *
 * @see VMRuntime
 */
object HiddenApiBypass {

    private const val TAG = "HiddenApiBypass"

    /**
     * 尝试豁免调用者的隐藏 API 限制
     *
     * 使用 dalvik.system.VMRuntime 方式绕过限制。
     * 将所有以 "L" 开头的类（即所有类）添加到豁免列表。
     *
     * ### 技术细节
     * - 调用 VMRuntime.getRuntime() 获取运行时实例
     * - 调用 setHiddenApiExemptions(String[]) 方法（Android 9+ 可用）
     * - 传入 ["L"] 表示豁免所有类
     *
     * 如果失败（例如在较旧的 Android 版本），会记录警告但不会抛出异常，
     * 因为 shell UID 通常已经有足够的权限。
     */
    fun exempt() {
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getMethod("getRuntime")
            val runtime = getRuntime.invoke(null)

            // setHiddenApiExemptions(String[]) - Android 9+ (API 28+)
            val setExemptions = vmRuntimeClass.getMethod("setHiddenApiExemptions", Array<String>::class.java)
            setExemptions.invoke(runtime, arrayOf("L"))
            Logger.i(TAG, "Hidden API exemptions set")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to set hidden API exemptions (may not be needed): ${e.message}")
        }
    }

    /**
     * 通过名称调用类的静态方法
     *
     * 反射调用工具方法，自动设置 accessible 标志。
     *
     * @param className 类的完全限定名
     * @param methodName 方法名
     * @param paramTypes 参数类型数组
     * @param args 参数值
     * @return 方法返回值
     * @throws RuntimeException 如果调用失败
     */
    fun invokeStatic(className: String, methodName: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? {
        return try {
            val clazz = Class.forName(className)
            val method = clazz.getMethod(methodName, *paramTypes)
            method.isAccessible = true
            method.invoke(null, *args)
        } catch (e: Exception) {
            throw RuntimeException("Failed to invoke $className.$methodName", e)
        }
    }

    /**
     * 调用无参数的静态方法
     *
     * @param className 类的完全限定名
     * @param methodName 方法名
     * @return 方法返回值
     */
    fun invokeStatic(className: String, methodName: String): Any? {
        return invokeStatic(className, methodName, emptyArray())
    }
}
