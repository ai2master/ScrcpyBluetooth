package com.scrcpybt.app.transfer

import android.content.Context

/**
 * User preferences for file transfer behavior.
 * Stored in SharedPreferences.
 */
object TransferSettings {
    private const val PREFS_NAME = "transfer_settings"
    private const val KEY_AUTO_RESUME_ENABLED = "auto_resume_enabled"
    private const val KEY_AUTO_RESUME_ON_RECONNECT = "auto_resume_on_reconnect"
    private const val KEY_MAX_RETRY_COUNT = "max_retry_count"
    private const val KEY_CHUNK_SIZE = "chunk_size"
    private const val KEY_CHECKPOINT_INTERVAL = "checkpoint_interval"

    private const val DEFAULT_AUTO_RESUME = true
    private const val DEFAULT_AUTO_RESUME_ON_RECONNECT = true
    private const val DEFAULT_MAX_RETRY_COUNT = 3
    private const val DEFAULT_CHUNK_SIZE = 32768 // 32KB
    private const val DEFAULT_CHECKPOINT_INTERVAL = 100 // Save state every 100 chunks (~3.2MB)

    /**
     * Check if auto-resume is enabled.
     */
    fun isAutoResumeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_RESUME_ENABLED, DEFAULT_AUTO_RESUME)
    }

    /**
     * Enable or disable auto-resume.
     */
    fun setAutoResumeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_RESUME_ENABLED, enabled).apply()
    }

    /**
     * Check if auto-resume on reconnect is enabled.
     */
    fun isAutoResumeOnReconnectEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_RESUME_ON_RECONNECT, DEFAULT_AUTO_RESUME_ON_RECONNECT)
    }

    /**
     * Enable or disable auto-resume on reconnect.
     */
    fun setAutoResumeOnReconnectEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_RESUME_ON_RECONNECT, enabled).apply()
    }

    /**
     * Get maximum retry count for failed transfers.
     */
    fun getMaxRetryCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_RETRY_COUNT, DEFAULT_MAX_RETRY_COUNT)
    }

    /**
     * Set maximum retry count for failed transfers.
     */
    fun setMaxRetryCount(context: Context, count: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_MAX_RETRY_COUNT, count).apply()
    }

    /**
     * Get chunk size for file transfers in bytes.
     */
    fun getChunkSize(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE)
    }

    /**
     * Set chunk size for file transfers in bytes.
     */
    fun setChunkSize(context: Context, size: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CHUNK_SIZE, size).apply()
    }

    /**
     * Get checkpoint interval (number of chunks between state saves).
     */
    fun getCheckpointInterval(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CHECKPOINT_INTERVAL, DEFAULT_CHECKPOINT_INTERVAL)
    }

    /**
     * Set checkpoint interval (number of chunks between state saves).
     */
    fun setCheckpointInterval(context: Context, interval: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CHECKPOINT_INTERVAL, interval).apply()
    }
}
