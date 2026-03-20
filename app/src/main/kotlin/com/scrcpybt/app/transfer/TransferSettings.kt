package com.scrcpybt.app.transfer

import android.content.Context

/**
 * 文件传输行为偏好设置管理器 | File transfer behavior preferences manager
 *
 * 核心功能 | Core Functions:
 * - 统一管理文件传输的用户偏好配置 | Centrally manage user preferences for file transfer
 * - 支持断点续传、自动重连、重试策略等参数 | Support resume, auto-reconnect, retry strategy parameters
 * - 持久化存储在 SharedPreferences | Persist in SharedPreferences
 *
 * 配置项说明 | Configuration Items:
 * - auto_resume_enabled: 是否启用断点续传 | Whether to enable resume transfer
 * - auto_resume_on_reconnect: 重连时是否自动恢复传输 | Whether to auto-resume on reconnect
 * - max_retry_count: 失败最大重试次数 | Max retry count on failure
 * - chunk_size: 分块大小（字节）| Chunk size (bytes)
 * - checkpoint_interval: 检查点保存间隔（块数）| Checkpoint save interval (chunk count)
 *
 * 使用场景 | Use Cases:
 * - FileTransferService 读取配置决定传输策略 | FileTransferService reads config to decide transfer strategy
 * - 设置界面允许用户调整参数 | Settings UI allows user to adjust parameters
 *
 * @see TransferState
 * @see TransferStateDatabase
 */
object TransferSettings {
    private const val PREFS_NAME = "transfer_settings"
    private const val KEY_AUTO_RESUME_ENABLED = "auto_resume_enabled"
    private const val KEY_AUTO_RESUME_ON_RECONNECT = "auto_resume_on_reconnect"
    private const val KEY_MAX_RETRY_COUNT = "max_retry_count"
    private const val KEY_CHUNK_SIZE = "chunk_size"
    private const val KEY_CHECKPOINT_INTERVAL = "checkpoint_interval"

    // 默认值 | Default values
    private const val DEFAULT_AUTO_RESUME = true
    private const val DEFAULT_AUTO_RESUME_ON_RECONNECT = true
    private const val DEFAULT_MAX_RETRY_COUNT = 3
    private const val DEFAULT_CHUNK_SIZE = 32768 // 32KB（块大小）| 32KB (chunk size)
    private const val DEFAULT_CHECKPOINT_INTERVAL = 100 // 每 100 块保存一次状态（约 3.2MB）| Save state every 100 chunks (~3.2MB)

    /**
     * 检查是否启用断点续传 | Check if auto-resume is enabled
     *
     * @param context Android Context
     * @return true 启用，false 禁用 | true if enabled, false otherwise
     */
    fun isAutoResumeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_RESUME_ENABLED, DEFAULT_AUTO_RESUME)
    }

    /**
     * 设置是否启用断点续传 | Enable or disable auto-resume
     *
     * @param context Android Context
     * @param enabled 是否启用 | Whether to enable
     */
    fun setAutoResumeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_RESUME_ENABLED, enabled).apply()
    }

    /**
     * 检查重连时是否自动恢复传输 | Check if auto-resume on reconnect is enabled
     *
     * @param context Android Context
     * @return true 启用，false 禁用 | true if enabled, false otherwise
     */
    fun isAutoResumeOnReconnectEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_RESUME_ON_RECONNECT, DEFAULT_AUTO_RESUME_ON_RECONNECT)
    }

    /**
     * 设置重连时是否自动恢复传输 | Enable or disable auto-resume on reconnect
     *
     * @param context Android Context
     * @param enabled 是否启用 | Whether to enable
     */
    fun setAutoResumeOnReconnectEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_RESUME_ON_RECONNECT, enabled).apply()
    }

    /**
     * 获取传输失败最大重试次数 | Get maximum retry count for failed transfers
     *
     * @param context Android Context
     * @return 重试次数（默认 3）| Retry count (default 3)
     */
    fun getMaxRetryCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_RETRY_COUNT, DEFAULT_MAX_RETRY_COUNT)
    }

    /**
     * 设置传输失败最大重试次数 | Set maximum retry count for failed transfers
     *
     * @param context Android Context
     * @param count 重试次数 | Retry count
     */
    fun setMaxRetryCount(context: Context, count: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_MAX_RETRY_COUNT, count).apply()
    }

    /**
     * 获取文件传输分块大小（字节）| Get chunk size for file transfers in bytes
     *
     * @param context Android Context
     * @return 分块大小（默认 32KB）| Chunk size (default 32KB)
     */
    fun getChunkSize(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE)
    }

    /**
     * 设置文件传输分块大小（字节）| Set chunk size for file transfers in bytes
     *
     * @param context Android Context
     * @param size 分块大小 | Chunk size
     */
    fun setChunkSize(context: Context, size: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CHUNK_SIZE, size).apply()
    }

    /**
     * 获取检查点保存间隔（块数）| Get checkpoint interval (number of chunks between state saves)
     *
     * 例如设置为 100，表示每传输 100 块保存一次状态。| For example, 100 means save state every 100 chunks.
     *
     * @param context Android Context
     * @return 间隔块数（默认 100）| Interval chunk count (default 100)
     */
    fun getCheckpointInterval(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CHECKPOINT_INTERVAL, DEFAULT_CHECKPOINT_INTERVAL)
    }

    /**
     * 设置检查点保存间隔（块数）| Set checkpoint interval (number of chunks between state saves)
     *
     * @param context Android Context
     * @param interval 间隔块数 | Interval chunk count
     */
    fun setCheckpointInterval(context: Context, interval: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CHECKPOINT_INTERVAL, interval).apply()
    }
}
