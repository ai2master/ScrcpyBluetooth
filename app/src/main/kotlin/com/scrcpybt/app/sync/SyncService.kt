package com.scrcpybt.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.scrcpybt.app.R
import com.scrcpybt.app.ui.foldersync.FolderSyncActivity
import com.scrcpybt.app.util.NotificationHelper
import com.scrcpybt.common.protocol.stream.MessageReader
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.sync.SyncConfig
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台文件夹同步前台服务 | Foreground service for background folder synchronization
 *
 * 功能特性：| Features:
 * - 在后台运行并显示通知 | Runs in background with notification
 * - 管理多个文件夹同步配置 | Manages multiple folder sync configs
 * - 启动/停止 SyncEngine 实例 | Starts/stops SyncEngine instances
 * - 遵守 PowerConditionChecker 限制 | Respects PowerConditionChecker
 * - 通过 FolderWatcher 监控文件夹 | Watches folders via FolderWatcher
 * - 使用 SyncDatabase 持久化 | Uses SyncDatabase for persistence
 * - 提供 Intent 操作和 Binder 接口 | Provides Intent actions and Binder interface
 */
class SyncService : Service() {

    companion object {
        private const val TAG = "SyncService"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "folder_sync_channel"
        private const val CHANNEL_NAME = "Folder Sync"

        // Intent actions
        const val ACTION_START_SYNC = "com.scrcpybt.app.sync.START_SYNC"
        const val ACTION_STOP_SYNC = "com.scrcpybt.app.sync.STOP_SYNC"
        const val ACTION_RESYNC = "com.scrcpybt.app.sync.RESYNC"
        const val ACTION_PAUSE = "com.scrcpybt.app.sync.PAUSE"
        const val ACTION_RESUME = "com.scrcpybt.app.sync.RESUME"

        // Intent extras
        const val EXTRA_FOLDER_ID = "folder_id"

        /**
         * 启动同步服务 | Start the sync service
         *
         * @param context 上下文 | Context
         */
        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止同步服务 | Stop the sync service
         *
         * @param context 上下文 | Context
         */
        fun stop(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            context.stopService(intent)
        }

        /**
         * 请求手动重新同步文件夹 | Request manual resync for a folder
         *
         * @param context 上下文 | Context
         * @param folderId 文件夹 ID | Folder ID
         */
        fun resync(context: Context, folderId: String) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_RESYNC
                putExtra(EXTRA_FOLDER_ID, folderId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    /** 服务 Binder | Service binder */
    private val binder = LocalBinder()

    /**
     * 本地 Binder 实现 | Local Binder implementation
     */
    inner class LocalBinder : Binder() {
        fun getService(): SyncService = this@SyncService
    }

    /** 核心组件 | Core components */
    private lateinit var database: SyncDatabase
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    /** 同步状态管理 | Sync state management */
    private val activeSyncs = ConcurrentHashMap<String, SyncContext>()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val watchers = ConcurrentHashMap<String, FolderWatcher>()
    private val powerCheckers = ConcurrentHashMap<String, PowerConditionChecker>()

    /**
     * 同步状态监听器，用于向 Activity 更新状态 | Listener for activity updates
     */
    interface SyncStatusListener {
        fun onSyncStarted(folderId: String)
        fun onSyncProgress(folderId: String, current: Int, total: Int, currentFile: String)
        fun onSyncCompleted(folderId: String, result: SyncResult)
        fun onSyncError(folderId: String, error: String)
        fun onFolderChange(folderId: String)
    }

    private var statusListener: SyncStatusListener? = null

    /**
     * 单个文件夹同步操作的上下文 | Context for a single folder sync operation
     */
    private data class SyncContext(
        val config: SyncConfig,
        val job: Job?,
        var lastSyncTime: Long = 0,
        var isRunning: Boolean = false,
        var currentProgress: SyncProgress = SyncProgress()
    )

    data class SyncProgress(
        var current: Int = 0,
        var total: Int = 0,
        var currentFile: String = ""
    )

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SyncService created")

        database = SyncDatabase(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Sync service active"))

        when (intent?.action) {
            ACTION_START_SYNC -> {
                val folderId = intent.getStringExtra(EXTRA_FOLDER_ID)
                if (folderId != null) {
                    startFolderSync(folderId)
                } else {
                    startAllFolderSyncs()
                }
            }
            ACTION_STOP_SYNC -> {
                val folderId = intent.getStringExtra(EXTRA_FOLDER_ID)
                if (folderId != null) {
                    stopFolderSync(folderId)
                } else {
                    stopAllFolderSyncs()
                }
            }
            ACTION_RESYNC -> {
                val folderId = intent.getStringExtra(EXTRA_FOLDER_ID)
                if (folderId != null) {
                    resyncFolder(folderId)
                }
            }
            ACTION_PAUSE -> {
                val folderId = intent.getStringExtra(EXTRA_FOLDER_ID)
                if (folderId != null) {
                    pauseFolder(folderId)
                }
            }
            ACTION_RESUME -> {
                val folderId = intent.getStringExtra(EXTRA_FOLDER_ID)
                if (folderId != null) {
                    resumeFolder(folderId)
                }
            }
            else -> {
                // Default: start all configured syncs
                startAllFolderSyncs()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 有活跃同步任务时重启服务
        if (activeSyncs.isNotEmpty()) {
            Log.i(TAG, "Task removed, restarting sync service (${activeSyncs.size} active syncs)")
            val restartIntent = Intent(applicationContext, SyncService::class.java)
            startForegroundService(restartIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "SyncService destroyed")

        stopAllFolderSyncs()
        serviceScope.cancel()
        releaseWakeLock()
    }

    /**
     * 设置同步状态更新监听器 | Set listener for sync status updates
     *
     * @param listener 监听器实例 | Listener instance
     */
    fun setStatusListener(listener: SyncStatusListener?) {
        this.statusListener = listener
    }

    /**
     * 获取所有活跃的文件夹同步配置 | Get all active folder sync configs
     *
     * @return 配置和进度的列表 | List of configs and progress
     */
    fun getActiveSyncs(): List<Pair<SyncConfig, SyncProgress>> {
        return activeSyncs.map { (_, context) ->
            context.config to context.currentProgress
        }
    }

    /**
     * 获取特定文件夹的同步状态 | Get sync status for a specific folder
     *
     * @param folderId 文件夹 ID | Folder ID
     * @return 状态字符串 | Status string
     */
    fun getSyncStatus(folderId: String): String? {
        return database.getFolderStatus(folderId)
    }

    /**
     * 检查文件夹是否正在同步 | Check if a folder is currently syncing
     *
     * @param folderId 文件夹 ID | Folder ID
     * @return 是否正在同步 | Whether syncing
     */
    fun isSyncing(folderId: String): Boolean {
        return activeSyncs[folderId]?.isRunning == true
    }

    /**
     * 启动所有已配置文件夹的同步 | Start sync for all configured folders
     */
    private fun startAllFolderSyncs() {
        val configs = database.getAllFolderConfigs()
        Log.i(TAG, "Starting sync for ${configs.size} folders")

        for (config in configs) {
            if (!config.paused) {
                startFolderSync(config.id)
            }
        }

        updateNotification("Syncing ${configs.size} folders")
    }

    /**
     * 启动特定文件夹的同步 | Start sync for a specific folder
     *
     * @param folderId 文件夹 ID | Folder ID
     */
    private fun startFolderSync(folderId: String) {
        if (activeSyncs.containsKey(folderId)) {
            Log.w(TAG, "Sync already active for folder: $folderId")
            return
        }

        val config = database.getFolderConfig(folderId)
        if (config == null) {
            Log.e(TAG, "Folder config not found: $folderId")
            return
        }

        if (config.paused) {
            Log.i(TAG, "Folder is paused: $folderId")
            return
        }

        Log.i(TAG, "Starting sync for folder: $folderId")

        // Create power condition checker
        val powerChecker = PowerConditionChecker(this, config)
        powerCheckers[folderId] = powerChecker

        // Register power condition listener
        powerChecker.registerListener(object : PowerConditionChecker.Listener {
            override fun onConditionsChanged(canSync: Boolean) {
                if (canSync) {
                    Log.i(TAG, "Power conditions met, triggering sync: $folderId")
                    resyncFolder(folderId)
                } else {
                    Log.i(TAG, "Power conditions not met, pausing sync: $folderId")
                }
            }
        })

        // Create folder watcher if enabled
        if (config.fsWatcherEnabled) {
            val ignorePattern = com.scrcpybt.common.sync.IgnorePattern()
            val watcher = FolderWatcher(
                config.localPath,
                config.fsWatcherDelaySec,
                ignorePattern,
                object : FolderWatcher.Listener {
                    override fun onChangesDetected(changedPaths: Set<String>) {
                        Log.i(TAG, "Changes detected in folder: $folderId (${changedPaths.size} files)")
                        statusListener?.onFolderChange(folderId)
                        resyncFolder(folderId)
                    }
                }
            )
            watcher.start()
            watchers[folderId] = watcher
        }

        // Create sync context
        val context = SyncContext(
            config = config,
            job = null,
            lastSyncTime = 0,
            isRunning = false
        )
        activeSyncs[folderId] = context

        // Schedule periodic syncs
        schedulePeriodicSync(folderId)

        // Trigger initial sync
        resyncFolder(folderId)
    }

    /**
     * 停止特定文件夹的同步 | Stop sync for a specific folder
     *
     * @param folderId 文件夹 ID | Folder ID
     */
    private fun stopFolderSync(folderId: String) {
        Log.i(TAG, "Stopping sync for folder: $folderId")

        val context = activeSyncs.remove(folderId)
        context?.job?.cancel()

        watchers.remove(folderId)?.stop()
        powerCheckers.remove(folderId)?.unregisterListener()

        database.updateFolderStatus(folderId, "idle")
    }

    /**
     * 停止所有文件夹同步 | Stop all folder syncs
     */
    private fun stopAllFolderSyncs() {
        Log.i(TAG, "Stopping all folder syncs")

        activeSyncs.keys.toList().forEach { folderId ->
            stopFolderSync(folderId)
        }
    }

    /**
     * 手动触发文件夹重新同步 | Manually trigger resync for a folder
     *
     * @param folderId 文件夹 ID | Folder ID
     */
    private fun resyncFolder(folderId: String) {
        val context = activeSyncs[folderId]
        if (context == null) {
            Log.w(TAG, "Cannot resync: folder not active: $folderId")
            return
        }

        if (context.isRunning) {
            Log.w(TAG, "Sync already running for folder: $folderId")
            return
        }

        // Check power conditions
        val powerChecker = powerCheckers[folderId]
        if (powerChecker != null && !powerChecker.canSync()) {
            Log.i(TAG, "Power conditions not met for sync: $folderId")
            database.updateFolderStatus(folderId, "waiting")
            return
        }

        Log.i(TAG, "Starting resync for folder: $folderId")

        // Cancel any existing job
        context.job?.cancel()

        // Create new sync job
        val job = serviceScope.launch {
            try {
                context.isRunning = true
                database.updateFolderStatus(folderId, "syncing")
                statusListener?.onSyncStarted(folderId)

                // NOTE: This is a simplified version. In reality, you would need
                // to get MessageWriter and MessageReader from an active connection.
                // For now, we'll just update the status to show the service structure.

                // In a real implementation, you would:
                // 1. Check if there's an active connection
                // 2. Get MessageWriter/MessageReader from the connection
                // 3. Create SyncEngine with proper parameters
                // 4. Run sync and handle results

                Log.w(TAG, "Sync execution placeholder - needs active connection")
                database.updateFolderStatus(folderId, "error")

                context.lastSyncTime = System.currentTimeMillis()

            } catch (e: CancellationException) {
                Log.i(TAG, "Sync cancelled: $folderId")
                database.updateFolderStatus(folderId, "cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed: $folderId", e)
                database.updateFolderStatus(folderId, "error")
                statusListener?.onSyncError(folderId, e.message ?: "Unknown error")
            } finally {
                context.isRunning = false
            }
        }

        // Update context with new job
        val updatedContext = context.copy(job = job)
        activeSyncs[folderId] = updatedContext
    }

    /**
     * 为文件夹安排周期性同步 | Schedule periodic sync for a folder
     *
     * @param folderId 文件夹 ID | Folder ID
     */
    private fun schedulePeriodicSync(folderId: String) {
        serviceScope.launch {
            val context = activeSyncs[folderId] ?: return@launch
            val intervalMs = context.config.rescanIntervalSec * 1000L

            while (isActive && activeSyncs.containsKey(folderId)) {
                delay(intervalMs)

                val currentContext = activeSyncs[folderId] ?: break
                if (!currentContext.isRunning && !currentContext.config.paused) {
                    Log.i(TAG, "Triggering periodic sync: $folderId")
                    resyncFolder(folderId)
                }
            }
        }
    }

    /**
     * 暂停文件夹同步 | Pause a folder sync
     *
     * @param folderId 文件夹 ID | Folder ID
     */
    private fun pauseFolder(folderId: String) {
        val config = database.getFolderConfig(folderId) ?: return
        val updatedConfig = config.copy(paused = true)
        database.saveFolderConfig(updatedConfig)

        activeSyncs[folderId]?.let { context ->
            val updated = context.copy(config = updatedConfig)
            activeSyncs[folderId] = updated
        }

        database.updateFolderStatus(folderId, "paused")
        Log.i(TAG, "Folder paused: $folderId")
    }

    /**
     * 恢复已暂停的文件夹同步 | Resume a paused folder sync
     *
     * @param folderId 文件夹 ID | Folder ID
     */
    private fun resumeFolder(folderId: String) {
        val config = database.getFolderConfig(folderId) ?: return
        val updatedConfig = config.copy(paused = false)
        database.saveFolderConfig(updatedConfig)

        activeSyncs[folderId]?.let { context ->
            val updated = context.copy(config = updatedConfig)
            activeSyncs[folderId] = updated
            resyncFolder(folderId)
        }

        Log.i(TAG, "Folder resumed: $folderId")
    }

    /**
     * 创建通知渠道（Android O+） | Create notification channel (Android O+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background folder synchronization"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台服务通知 | Create notification for foreground service
     *
     * @param contentText 通知内容文本 | Notification content text
     * @return 通知对象 | Notification object
     */
    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, FolderSyncActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 断开连接操作按钮
        val disconnectIntent = Intent(NotificationHelper.ACTION_DISCONNECT).apply {
            setPackage(packageName)
            putExtra(NotificationHelper.EXTRA_SERVICE_CLASS, SyncService::class.java.name)
        }
        val disconnectPendingIntent = PendingIntent.getBroadcast(
            this,
            SyncService::class.java.name.hashCode(),
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Folder Sync")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "断开连接", disconnectPendingIntent)
            .build()
    }

    /**
     * 更新通知内容 | Update notification content
     *
     * @param contentText 新的内容文本 | New content text
     */
    private fun updateNotification(contentText: String) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    /**
     * 获取 WakeLock 以在同步期间保持 CPU 唤醒 | Acquire wake lock to keep CPU awake during sync
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SyncService::WakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
    }

    /**
     * 释放 WakeLock | Release wake lock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}
