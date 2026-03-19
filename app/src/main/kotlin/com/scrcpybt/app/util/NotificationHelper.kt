package com.scrcpybt.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.scrcpybt.app.R

/**
 * 通知辅助工具：创建前台服务通知
 *
 * 前台服务要求：
 * - Android 8.0+ 必须显示持久通知
 * - 防止系统在后台杀死服务
 * - 提升用户对后台运行状态的感知
 *
 * 通知渠道：
 * - 服务渠道：重要性低（不会发出声音或振动）
 * - 剪贴板渠道：重要性高（需要用户注意）
 */
object NotificationHelper {
    /** 前台服务通知渠道 ID */
    private const val CHANNEL_ID = "scrcpy_bt_service"
    /** 前台服务通知渠道名称 */
    private const val CHANNEL_NAME = "ScrcpyBluetooth Service"

    /** 剪贴板通知渠道 ID */
    private const val CLIPBOARD_CHANNEL_ID = "scrcpy_bt_clipboard"
    /** 剪贴板通知渠道名称 */
    private const val CLIPBOARD_CHANNEL_NAME = "Clipboard Transfer"

    /** 剪贴板通知 ID */
    private const val CLIPBOARD_NOTIFICATION_ID = 2001

    /** 断开连接广播 Action */
    const val ACTION_DISCONNECT = "com.scrcpybt.app.ACTION_DISCONNECT"
    /** 广播 Extra：服务类名 */
    const val EXTRA_SERVICE_CLASS = "service_class"

    /**
     * 创建前台服务通知（含"断开连接"操作按钮）
     *
     * @param context 上下文
     * @param contentText 通知内容文本
     * @param serviceClass 服务的完整类名，用于断开连接广播（可选）
     * @return 通知对象
     */
    fun createServiceNotification(
        context: Context,
        contentText: String,
        serviceClass: String? = null
    ): Notification {
        createChannelIfNeeded(context)

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)

        // 添加"断开连接"操作按钮
        if (serviceClass != null) {
            val disconnectIntent = Intent(ACTION_DISCONNECT).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_SERVICE_CLASS, serviceClass)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                serviceClass.hashCode(),
                disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "断开连接",
                    pendingIntent
                ).build()
            )
        }

        return builder.build()
    }

    /**
     * 显示剪贴板接收通知
     *
     * 当控制端在后台收到受控端的剪贴板内容时，显示通知提示用户。
     * 用户点击通知后，通过 ClipboardTrampolineActivity 写入本地剪贴板。
     *
     * @param context 上下文
     * @param text 剪贴板文本内容
     */
    fun showClipboardNotification(context: Context, text: String) {
        createClipboardChannelIfNeeded(context)

        // 点击通知 → 启动透明跳板 Activity → 写入本地剪贴板
        val trampolineIntent = Intent().apply {
            setClassName(context, "com.scrcpybt.app.ui.clipboard.ClipboardTrampolineActivity")
            putExtra("clipboard_text", text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, CLIPBOARD_NOTIFICATION_ID, trampolineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val preview = if (text.length > 50) text.substring(0, 50) + "..." else text

        val notification = Notification.Builder(context, CLIPBOARD_CHANNEL_ID)
            .setContentTitle("收到远端剪贴板")
            .setContentText("点击写入本地剪贴板: $preview")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(CLIPBOARD_NOTIFICATION_ID, notification)
    }

    private fun createChannelIfNeeded(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Background service for screen mirroring"
            manager.createNotificationChannel(channel)
        }
    }

    private fun createClipboardChannelIfNeeded(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CLIPBOARD_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CLIPBOARD_CHANNEL_ID, CLIPBOARD_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Clipboard transfer notifications"
            manager.createNotificationChannel(channel)
        }
    }
}
