package com.scrcpybt.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.scrcpybt.common.util.Logger

/**
 * 快捷方式管理器
 *
 * 统一管理三种快捷入口：
 * 1. **桌面长按快捷方式** (App Shortcuts) — 通过 ShortcutManager 动态管理
 * 2. **文本选中操作条** (PROCESS_TEXT) — 通过 PackageManager 启用/禁用组件
 * 3. **分享菜单** (ACTION_SEND) — 通过 PackageManager 启用/禁用组件
 *
 * ### 自定义机制
 * 所有快捷方式均可通过 ShortcutConfigActivity 独立开关。
 * 配置持久化在 SharedPreferences("shortcut_config") 中。
 *
 * ### 快捷方式列表
 *
 * | ID | 桌面长按 | 文本选中 | 分享菜单 |
 * |----|---------|---------|---------|
 * | clipboard | 剪贴板传输 | 发送到远端剪贴板 | 发送到远端剪贴板 |
 * | file_transfer | 文件传输 | — | 分享文件到远端 |
 * | folder_sync | 文件夹同步 | — | — |
 * | share_forward | 分享穿透 | — | 分享到远端设备 |
 */
object ShortcutHelper {

    private const val TAG = "ShortcutHelper"
    private const val PREFS_NAME = "shortcut_config"

    // 桌面长按快捷方式 ID
    const val SHORTCUT_CLIPBOARD = "shortcut_clipboard"
    const val SHORTCUT_FILE_TRANSFER = "shortcut_file_transfer"
    const val SHORTCUT_FOLDER_SYNC = "shortcut_folder_sync"
    const val SHORTCUT_SHARE_FORWARD = "shortcut_share_forward"

    // 文本选中操作条组件
    private const val COMPONENT_TEXT_CLIPBOARD =
        "com.scrcpybt.app.ui.textaction.SendTextToRemoteActivity"

    // 分享菜单组件
    private const val COMPONENT_SHARE_RECEIVER =
        "com.scrcpybt.app.ui.share.ShareReceiverActivity"

    // Preference key 前缀
    private const val KEY_LAUNCHER_PREFIX = "launcher_"
    private const val KEY_TEXT_ACTION_PREFIX = "text_action_"
    private const val KEY_SHARE_PREFIX = "share_"

    /**
     * 所有可配置的快捷方式定义
     */
    data class ShortcutDef(
        val id: String,
        val labelRes: String,
        val hasLauncher: Boolean = true,
        val hasTextAction: Boolean = false,
        val hasShare: Boolean = false
    )

    val ALL_SHORTCUTS = listOf(
        ShortcutDef(SHORTCUT_CLIPBOARD, "clipboard",
            hasLauncher = true, hasTextAction = true, hasShare = true),
        ShortcutDef(SHORTCUT_FILE_TRANSFER, "file_transfer",
            hasLauncher = true, hasTextAction = false, hasShare = true),
        ShortcutDef(SHORTCUT_FOLDER_SYNC, "folder_sync",
            hasLauncher = true, hasTextAction = false, hasShare = false),
        ShortcutDef(SHORTCUT_SHARE_FORWARD, "share_forward",
            hasLauncher = true, hasTextAction = false, hasShare = true)
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ==================== 桌面长按快捷方式 ====================

    /**
     * 是否启用某个桌面长按快捷方式
     */
    fun isLauncherShortcutEnabled(context: Context, shortcutId: String): Boolean {
        return getPrefs(context).getBoolean(KEY_LAUNCHER_PREFIX + shortcutId, true)
    }

    /**
     * 设置桌面长按快捷方式开关
     */
    fun setLauncherShortcutEnabled(context: Context, shortcutId: String, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LAUNCHER_PREFIX + shortcutId, enabled).apply()
        refreshLauncherShortcuts(context)
    }

    /**
     * 刷新所有桌面长按快捷方式
     *
     * 根据当前配置，动态更新 ShortcutManager 中的快捷方式列表。
     */
    fun refreshLauncherShortcuts(context: Context) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
        val prefs = getPrefs(context)

        val shortcuts = mutableListOf<ShortcutInfo>()

        if (prefs.getBoolean(KEY_LAUNCHER_PREFIX + SHORTCUT_CLIPBOARD, true)) {
            shortcuts.add(buildShortcut(context, SHORTCUT_CLIPBOARD,
                "剪贴板传输", "在设备之间传递剪贴板",
                android.R.drawable.ic_menu_edit,
                "com.scrcpybt.app.ui.clipboard.ClipboardActivity"))
        }

        if (prefs.getBoolean(KEY_LAUNCHER_PREFIX + SHORTCUT_FILE_TRANSFER, true)) {
            shortcuts.add(buildShortcut(context, SHORTCUT_FILE_TRANSFER,
                "文件传输", "传输文件到远端设备",
                android.R.drawable.ic_menu_upload,
                "com.scrcpybt.app.ui.filetransfer.FileTransferActivity"))
        }

        if (prefs.getBoolean(KEY_LAUNCHER_PREFIX + SHORTCUT_FOLDER_SYNC, true)) {
            shortcuts.add(buildShortcut(context, SHORTCUT_FOLDER_SYNC,
                "文件夹同步", "同步文件夹内容",
                android.R.drawable.ic_menu_rotate,
                "com.scrcpybt.app.ui.foldersync.FolderSyncActivity"))
        }

        if (prefs.getBoolean(KEY_LAUNCHER_PREFIX + SHORTCUT_SHARE_FORWARD, true)) {
            shortcuts.add(buildShortcut(context, SHORTCUT_SHARE_FORWARD,
                "分享穿透设置", "配置分享转发",
                android.R.drawable.ic_menu_share,
                "com.scrcpybt.app.ui.share.ShareConfigActivity"))
        }

        shortcutManager.dynamicShortcuts = shortcuts
        Logger.i(TAG, "Launcher shortcuts refreshed: ${shortcuts.size} active")
    }

    private fun buildShortcut(
        context: Context,
        id: String,
        shortLabel: String,
        longLabel: String,
        iconRes: Int,
        activityClassName: String
    ): ShortcutInfo {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(context, activityClassName)
        }
        return ShortcutInfo.Builder(context, id)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(Icon.createWithResource(context, iconRes))
            .setIntent(intent)
            .build()
    }

    // ==================== 文本选中操作条 ====================

    /**
     * 是否启用文本选中操作条中的"发送到远端剪贴板"
     */
    fun isTextActionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_TEXT_ACTION_PREFIX + "clipboard", true)
    }

    /**
     * 设置文本选中操作条开关
     *
     * 通过 PackageManager 启用/禁用 PROCESS_TEXT Activity 组件。
     */
    fun setTextActionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_TEXT_ACTION_PREFIX + "clipboard", enabled).apply()
        setComponentEnabled(context, COMPONENT_TEXT_CLIPBOARD, enabled)
    }

    // ==================== 分享菜单 ====================

    /**
     * 是否启用分享菜单入口
     */
    fun isShareMenuEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHARE_PREFIX + "enabled", true)
    }

    /**
     * 设置分享菜单入口开关
     *
     * 通过 PackageManager 启用/禁用 ShareReceiverActivity 组件。
     * 禁用后，ScrcpyBluetooth 不再出现在系统分享菜单中。
     */
    fun setShareMenuEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHARE_PREFIX + "enabled", enabled).apply()
        setComponentEnabled(context, COMPONENT_SHARE_RECEIVER, enabled)
    }

    // ==================== 全部删除/恢复 ====================

    /**
     * 删除所有快捷方式
     *
     * 清空桌面长按快捷方式、禁用文本选中操作条、禁用分享菜单入口。
     */
    fun removeAll(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit().apply {
            ALL_SHORTCUTS.forEach {
                putBoolean(KEY_LAUNCHER_PREFIX + it.id, false)
            }
            putBoolean(KEY_TEXT_ACTION_PREFIX + "clipboard", false)
            putBoolean(KEY_SHARE_PREFIX + "enabled", false)
        }.apply()

        // 清空桌面快捷方式
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        shortcutManager?.removeAllDynamicShortcuts()

        // 禁用组件
        setComponentEnabled(context, COMPONENT_TEXT_CLIPBOARD, false)
        setComponentEnabled(context, COMPONENT_SHARE_RECEIVER, false)

        Logger.i(TAG, "All shortcuts removed")
    }

    /**
     * 恢复所有快捷方式到默认状态（全部启用）
     */
    fun restoreDefaults(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit().apply {
            ALL_SHORTCUTS.forEach {
                putBoolean(KEY_LAUNCHER_PREFIX + it.id, true)
            }
            putBoolean(KEY_TEXT_ACTION_PREFIX + "clipboard", true)
            putBoolean(KEY_SHARE_PREFIX + "enabled", true)
        }.apply()

        refreshLauncherShortcuts(context)
        setComponentEnabled(context, COMPONENT_TEXT_CLIPBOARD, true)
        setComponentEnabled(context, COMPONENT_SHARE_RECEIVER, true)

        Logger.i(TAG, "All shortcuts restored to defaults")
    }

    // ==================== 工具方法 ====================

    /**
     * 启用/禁用指定的 Activity 组件
     *
     * 通过 PackageManager.setComponentEnabledSetting() 实现。
     * 禁用后，系统不再向该组件分发 Intent（从分享菜单和文本操作条中消失）。
     */
    private fun setComponentEnabled(context: Context, className: String, enabled: Boolean) {
        try {
            val componentName = ComponentName(context, className)
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                componentName, newState, PackageManager.DONT_KILL_APP
            )
            Logger.i(TAG, "Component $className ${if (enabled) "enabled" else "disabled"}")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to set component state: $className", e)
        }
    }

    /**
     * 检查指定组件是否启用
     */
    fun isComponentEnabled(context: Context, className: String): Boolean {
        return try {
            val componentName = ComponentName(context, className)
            val state = context.packageManager.getComponentEnabledSetting(componentName)
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } catch (e: Exception) {
            true
        }
    }

    /**
     * 在 Application.onCreate 中调用，初始化快捷方式
     */
    fun initialize(context: Context) {
        refreshLauncherShortcuts(context)
        // 文本操作条和分享菜单的组件状态在 Manifest 中默认启用，
        // 只有用户主动禁用后才需要同步状态
        val prefs = getPrefs(context)
        if (prefs.contains(KEY_TEXT_ACTION_PREFIX + "clipboard")) {
            val enabled = prefs.getBoolean(KEY_TEXT_ACTION_PREFIX + "clipboard", true)
            setComponentEnabled(context, COMPONENT_TEXT_CLIPBOARD, enabled)
        }
        if (prefs.contains(KEY_SHARE_PREFIX + "enabled")) {
            val enabled = prefs.getBoolean(KEY_SHARE_PREFIX + "enabled", true)
            setComponentEnabled(context, COMPONENT_SHARE_RECEIVER, enabled)
        }
    }
}
