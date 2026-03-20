package com.scrcpybt.app

import android.app.Application
import com.scrcpybt.app.util.ShortcutHelper
import com.scrcpybt.common.util.Logger

/**
 * ScrcpyBluetooth 应用程序主入口类，负责全局初始化工作。
 * 在应用启动时执行一次性配置，包括日志系统、快捷方式管理等核心功能的初始化。
 * 作为应用生命周期的顶层管理者，确保所有全局资源和服务在应用运行前就绪。
 *
 * ScrcpyBluetooth application entry point, responsible for global initialization.
 * Performs one-time configuration at app startup, including logging system, shortcut management,
 * and other core functionality initialization. As the top-level lifecycle manager,
 * ensures all global resources and services are ready before the app runs.
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
class ScrcpyApplication : Application() {
    /**
     * 应用创建时的回调方法，执行全局初始化任务。
     * 包括日志记录启动、快捷方式系统初始化等关键操作。
     * 此方法在应用的所有组件（Activity、Service 等）创建之前调用。
     *
     * Callback method when application is created, performs global initialization tasks.
     * Includes logging startup, shortcut system initialization, and other critical operations.
     * This method is called before any application components (Activity, Service, etc.) are created.
     */
    override fun onCreate() {
        super.onCreate()

        // 记录应用启动日志 | Log application startup
        Logger.i("App", "ScrcpyBluetooth application started")

        // 初始化桌面长按快捷方式和组件启用状态 | Initialize launcher shortcuts and component enabled states
        ShortcutHelper.initialize(this)
    }
}
