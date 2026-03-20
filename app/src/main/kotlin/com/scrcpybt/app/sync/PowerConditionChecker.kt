package com.scrcpybt.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.scrcpybt.common.sync.SyncConfig
import java.util.*

/**
 * 电源条件检查器：检查是否满足同步的电源/电池条件 | Power Condition Checker: Checks if power/battery conditions are met for syncing
 *
 * 检查条件 | Checked Conditions:
 * 1. 充电状态：是否需要充电才能同步 | Charging status: Whether charging is required for sync
 * 2. 电池电量：最低电量百分比要求 | Battery level: Minimum battery percentage requirement
 * 3. 省电模式：是否尊重省电模式 | Battery saver: Whether to respect battery saver mode
 * 4. 时间窗口：允许同步的时间范围 | Time window: Time range allowed for syncing
 *
 * 监听广播 | Monitored Broadcasts:
 *   - ACTION_BATTERY_CHANGED: 电池状态变化 | Battery status changed
 *   - ACTION_POWER_CONNECTED/DISCONNECTED: 电源连接/断开 | Power connected/disconnected
 *   - ACTION_POWER_SAVE_MODE_CHANGED: 省电模式变化 | Power save mode changed
 *
 * 使用场景 | Use Cases:
 * - SyncService 检查是否可以开始同步 | SyncService checks if sync can start
 * - 监听电源状态变化自动触发/暂停同步 | Monitor power state changes to auto trigger/pause sync
 * - 用户配置的省电策略 | User-configured power saving policies
 *
 * @property context Android 上下文 | Android context
 * @property config 同步配置（包含电源条件设置）| Sync config (contains power condition settings)
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
class PowerConditionChecker(
    private val context: Context,
    private val config: SyncConfig
) {
    /**
     * 条件变化监听器接口 | Condition change listener interface
     */
    interface Listener {
        /**
         * 条件变化时触发 | Triggered when conditions change
         * @param canSync 是否可以同步 | Whether syncing is allowed
         */
        fun onConditionsChanged(canSync: Boolean)
    }

    companion object {
        private const val TAG = "PowerConditionChecker"
    }

    private var listener: Listener? = null
    private var isRegistered = false
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED,
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED,
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    Log.d(TAG, "Power state changed: ${intent.action}")
                    notifyConditionsChanged()
                }
            }
        }
    }

    /**
     * 检查是否满足所有电源条件可以同步 | Check if all power conditions are met for syncing
     * @return true 如果满足所有条件 | true if all conditions are met
     */
    fun canSync(): Boolean {
        // Check if charging required
        if (config.syncOnlyWhileCharging && !isCharging()) {
            Log.d(TAG, "Sync blocked: not charging")
            return false
        }

        // Check battery level
        if (config.minBatteryPercent > 0) {
            val batteryLevel = getBatteryLevel()
            if (batteryLevel < config.minBatteryPercent) {
                Log.d(TAG, "Sync blocked: battery level $batteryLevel% < ${config.minBatteryPercent}%")
                return false
            }
        }

        // Check battery saver mode
        if (config.respectBatterySaver && isBatterySaverActive()) {
            Log.d(TAG, "Sync blocked: battery saver active")
            return false
        }

        // Check time window
        if (!isInTimeWindow()) {
            Log.d(TAG, "Sync blocked: outside time window")
            return false
        }

        return true
    }

    /**
     * Register broadcast receiver for power state changes.
     */
    fun registerListener(listener: Listener) {
        this.listener = listener

        if (!isRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            context.registerReceiver(powerReceiver, filter)
            isRegistered = true
            Log.i(TAG, "Registered power state receiver")
        }
    }

    /**
     * Unregister broadcast receiver.
     */
    fun unregisterListener() {
        if (isRegistered) {
            context.unregisterReceiver(powerReceiver)
            isRegistered = false
            Log.i(TAG, "Unregistered power state receiver")
        }
        this.listener = null
    }

    private fun isCharging(): Boolean {
        val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getBatteryLevel(): Int {
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isBatterySaverActive(): Boolean {
        return powerManager.isPowerSaveMode
    }

    private fun isInTimeWindow(): Boolean {
        if (config.syncTimeWindowStart == -1 || config.syncTimeWindowEnd == -1) {
            return true  // No time window restriction
        }

        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)

        return if (config.syncTimeWindowStart <= config.syncTimeWindowEnd) {
            // Normal range: e.g., 8:00 to 18:00
            currentHour in config.syncTimeWindowStart..config.syncTimeWindowEnd
        } else {
            // Wrap around: e.g., 22:00 to 6:00 (night time)
            currentHour >= config.syncTimeWindowStart || currentHour <= config.syncTimeWindowEnd
        }
    }

    private fun notifyConditionsChanged() {
        val canSyncNow = canSync()
        listener?.onConditionsChanged(canSyncNow)
    }

    /**
     * Get human-readable description of current power state.
     */
    fun getPowerStateDescription(): String {
        val parts = mutableListOf<String>()

        parts.add("Battery: ${getBatteryLevel()}%")

        if (isCharging()) {
            parts.add("Charging")
        }

        if (isBatterySaverActive()) {
            parts.add("Battery Saver ON")
        }

        if (!isInTimeWindow()) {
            parts.add("Outside time window")
        }

        return parts.joinToString(", ")
    }
}
