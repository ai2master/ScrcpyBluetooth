package com.scrcpybt.app

import android.app.Application
import com.scrcpybt.app.util.ShortcutHelper
import com.scrcpybt.common.util.Logger

class ScrcpyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.i("App", "ScrcpyBluetooth application started")

        // 初始化桌面长按快捷方式和组件启用状态
        ShortcutHelper.initialize(this)
    }
}
