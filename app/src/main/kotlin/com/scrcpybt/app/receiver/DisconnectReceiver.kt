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
 * 通知栏断开连接广播接收器 | Notification disconnect broadcast receiver
 *
 * 核心功能 | Core Functions:
 * - 处理通知栏中"断开连接"按钮的用户操作 | Handle user action for "Disconnect" button in notification
 * - 根据服务类型停止对应的前台服务 | Stop corresponding foreground service based on service type
 * - 支持多种服务类型（ControllerService、RelayService、SyncService）| Support multiple service types
 *
 * 工作流程 | Workflow:
 * 1. 接收 ACTION_DISCONNECT 广播 | Receive ACTION_DISCONNECT broadcast
 * 2. 从 Intent 提取服务类名（EXTRA_SERVICE_CLASS）| Extract service class name from Intent (EXTRA_SERVICE_CLASS)
 * 3. 根据类名创建对应的服务 Intent | Create corresponding service Intent based on class name
 * 4. 调用 stopService() 停止服务 | Call stopService() to stop the service
 *
 * 使用场景 | Use Cases:
 * - NotificationHelper 创建通知时关联此 Receiver | NotificationHelper associates this Receiver when creating notifications
 * - 用户点击通知栏断开按钮触发 | Triggered when user clicks disconnect button in notification
 * - 优雅地终止前台服务（释放资源、关闭连接）| Gracefully terminate foreground service (release resources, close connections)
 *
 * 注册方式 | Registration:
 * - 在 AndroidManifest.xml 中静态注册 | Statically registered in AndroidManifest.xml
 * - 仅接收本应用的私有广播 | Only receive private broadcasts from this app
 *
 * @see NotificationHelper
 * @see ControllerService
 * @see RelayService
 * @see SyncService
 */
class DisconnectReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DisconnectReceiver"
    }

    /**
     * 广播接收回调 | Broadcast receive callback
     *
     * @param context Android Context
     * @param intent 广播 Intent（包含 ACTION_DISCONNECT 和 EXTRA_SERVICE_CLASS）| Broadcast Intent with ACTION_DISCONNECT and EXTRA_SERVICE_CLASS
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationHelper.ACTION_DISCONNECT) return

        // 提取目标服务类名 | Extract target service class name
        val serviceClass = intent.getStringExtra(NotificationHelper.EXTRA_SERVICE_CLASS) ?: return
        Logger.i(TAG, "Disconnect requested for: $serviceClass")

        // 根据类名匹配服务类型 | Match service type based on class name
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

        // 停止对应服务 | Stop corresponding service
        context.stopService(serviceIntent)
    }
}
