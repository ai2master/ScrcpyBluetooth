package com.scrcpybt.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.scrcpybt.app.service.ControllerService
import com.scrcpybt.app.service.RelayService
import com.scrcpybt.app.sync.SyncService
import com.scrcpybt.app.util.NotificationHelper
import com.scrcpybt.common.util.Logger

/**
 * 处理通知栏"断开连接"操作按钮的广播接收器
 *
 * 用户在通知栏点击"断开连接"后：
 * 1. 接收 ACTION_DISCONNECT 广播
 * 2. 根据 EXTRA_SERVICE_CLASS 确定目标服务
 * 3. 调用 stopService() 停止对应服务
 */
class DisconnectReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DisconnectReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationHelper.ACTION_DISCONNECT) return

        val serviceClass = intent.getStringExtra(NotificationHelper.EXTRA_SERVICE_CLASS) ?: return
        Logger.i(TAG, "Disconnect requested for: $serviceClass")

        val serviceIntent = when {
            serviceClass.endsWith("ControllerService") ->
                Intent(context, ControllerService::class.java)
            serviceClass.endsWith("RelayService") ->
                Intent(context, RelayService::class.java)
            serviceClass.endsWith("SyncService") ->
                Intent(context, SyncService::class.java)
            else -> {
                Logger.w(TAG, "Unknown service class: $serviceClass")
                return
            }
        }

        context.stopService(serviceIntent)
    }
}
