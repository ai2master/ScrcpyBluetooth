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
 * 快捷方式管理器：统一管理应用快捷入口 | Shortcut manager for unified shortcut entry management
 *
 * 核心功能 | Core Functions:
 * - 统一管理三种快捷入口（桌面长按、文本选中、分享菜单）| Manage three types of shortcut entries uniformly
 * - 支持动态启用/禁用各快捷方式 | Support dynamically enabling/disabling shortcuts
 * - 配置持久化存储 | Persistent configuration storage
 *
 * 三种快捷入口类型 | Three Types of Shortcut Entries:
 * 1. **桌面长按快捷方式** (App Shortcuts) — 通过 ShortcutManager 动态管理 | Managed dynamically via ShortcutManager
 *    - 长按应用图标显示快捷菜单 | Long press app icon to show shortcut menu
 *    - 支持固定到桌面 | Support pinning to home screen
 *
 * 2. **文本选中操作条** (PROCESS_TEXT) — 通过 PackageManager 启用/禁用组件 | Enable/disable component via PackageManager
 *    - 在任何应用选中文本后显示在操作条 | Show in action bar after selecting text in any app
 *    - 快速发送文本到远端剪贴板 | Quickly send text to remote clipboard
 *
 * 3. **分享菜单** (ACTION_SEND) — 通过 PackageManager 启用/禁用组件 | Enable/disable component via PackageManager
 *    - 在系统分享菜单中显示应用图标 | Show app icon in system share menu
 *    - 快速分享文件/文本到远端设备 | Quickly share files/text to remote device
 *
 * 自定义机制 | Customization Mechanism:
 * - 所有快捷方式均可通过 ShortcutConfigActivity 独立开关 | All shortcuts can be toggled independently via ShortcutConfigActivity
 * - 配置持久化在 SharedPreferences("shortcut_config") 中 | Configuration persisted in SharedPreferences("shortcut_config")
 * - 支持一键清除/恢复所有快捷方式 | Support one-click clear/restore all shortcuts
 *
 * 快捷方式列表 | Shortcut List:
 *
 * | ID | 桌面长按 | 文本选中 | 分享菜单 |
 * |----|---------|---------|---------|
 * | clipboard | 剪贴板传输 | 发送到远端剪贴板 | 发送到远端剪贴板 |
 * | file_transfer | 文件传输 | — | 分享文件到远端 |
 * | folder_sync | 文件夹同步 | — | — |
 * | share_forward | 分享穿透 | — | 分享到远端设备 |
 *
 * 使用场景 | Use Cases:
 * - Application.onCreate() 调用 initialize() 初始化快捷方式 | Call initialize() in Application.onCreate() to initialize shortcuts
 * - 设置界面调用 set*Enabled() 方法切换开关 | Call set*Enabled() methods in settings UI to toggle switches
 * - 用户反馈快捷方式过多时可选择性禁用 | Selectively disable when user feedback too many shortcuts
 *
 * 安全考虑 | Security Considerations:
 * - 组件禁用后从系统菜单完全消失，防止误触 | Components completely disappear from system menus when disabled, prevent accidental touch
 * - 配置存储在应用私有目录，其他应用无法访问 | Configuration stored in app private directory, inaccessible to other apps
 *
 * @see android.content.pm.ShortcutManager
 * @see android.content.pm.PackageManager
 */
object ShortcutHelper {

    private const val TAG = "ShortcutHelper"
    private const val PREFS_NAME = "shortcut_config" // SharedPreferences 名称 | SharedPreferences name

    // 桌面长按快捷方式 ID | Launcher shortcut IDs
    const val SHORTCUT_CLIPBOARD = "shortcut_clipboard"
    const val SHORTCUT_FILE_TRANSFER = "shortcut_file_transfer"
    const val SHORTCUT_FOLDER_SYNC = "shortcut_folder_sync"
    const val SHORTCUT_SHARE_FORWARD = "shortcut_share_forward"

    // 文本选中操作条组件类名 | Text action component class name
    private const val COMPONENT_TEXT_CLIPBOARD =
        "com.scrcpybt.app.ui.textaction.SendTextToRemoteActivity"

    // 分享菜单组件类名 | Share menu component class name
    private const val COMPONENT_SHARE_RECEIVER =
        "com.scrcpybt.app.ui.share.ShareReceiverActivity"

    // SharedPreferences key 前缀 | SharedPreferences key prefixes
    private const val KEY_LAUNCHER_PREFIX = "launcher_"
    private const val KEY_TEXT_ACTION_PREFIX = "text_action_"
    private const val KEY_SHARE_PREFIX = "share_"

    /**
     * 快捷方式定义数据类 | Shortcut definition data class
     *
     * @property id 快捷方式 ID | Shortcut ID
     * @property labelRes 标签资源名 | Label resource name
     * @property hasLauncher 是否支持桌面长按快捷方式 | Whether supports launcher shortcut
     * @property hasTextAction 是否支持文本选中操作 | Whether supports text action
     * @property hasShare 是否支持分享菜单 | Whether supports share menu
     */
    data class ShortcutDef(
        val id: String,
        val labelRes: String,
        val hasLauncher: Boolean = true,
        val hasTextAction: Boolean = false,
        val hasShare: Boolean = false
    )

    /**
     * 所有可配置的快捷方式定义列表 | List of all configurable shortcut definitions
     */
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

    /**
     * 获取 SharedPreferences 实例 | Get SharedPreferences instance
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ==================== 桌面长按快捷方式 | Launcher Shortcuts ====================

    /**
     * 检查某个桌面长按快捷方式是否启用 | Check if a launcher shortcut is enabled
     *
     * @param context Android Context
     * @param shortcutId 快捷方式 ID | Shortcut ID
     * @return true 启用，false 禁用 | true if enabled, false otherwise
     */
    fun isLauncherShortcutEnabled(context: Context, shortcutId: String): Boolean {
        return getPrefs(context).getBoolean(KEY_LAUNCHER_PREFIX + shortcutId, true)
    }

    /**
     * 设置桌面长按快捷方式开关 | Set launcher shortcut enabled state
     *
     * 设置后立即刷新 ShortcutManager 中的快捷方式列表。| Immediately refresh shortcut list in ShortcutManager after setting.
     *
     * @param context Android Context
     * @param shortcutId 快捷方式 ID | Shortcut ID
     * @param enabled 是否启用 | Whether to enable
     */
    fun setLauncherShortcutEnabled(context: Context, shortcutId: String, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LAUNCHER_PREFIX + shortcutId, enabled).apply()
        refreshLauncherShortcuts(context)
    }

    /**
     * 刷新所有桌面长按快捷方式 | Refresh all launcher shortcuts
     *
     * 根据当前配置，动态更新 ShortcutManager 中的快捷方式列表。| Dynamically update shortcut list in ShortcutManager based on current configuration.
     * 禁用的快捷方式将从列表中移除。| Disabled shortcuts will be removed from the list.
     *
     * @param context Android Context
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

    /**
     * 构建快捷方式信息对象 | Build shortcut info object
     *
     * @param context Android Context
     * @param id 快捷方式 ID | Shortcut ID
     * @param shortLabel 短标签（显示在图标下方）| Short label (displayed below icon)
     * @param longLabel 长标签（长按显示）| Long label (displayed on long press)
     * @param iconRes 图标资源 ID | Icon resource ID
     * @param activityClassName 目标 Activity 类名 | Target Activity class name
     * @return ShortcutInfo 对象 | ShortcutInfo object
     */
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

    // ==================== 文本选中操作条 | Text Action ====================

    /**
     * 检查文本选中操作条是否启用 | Check if text action is enabled
     *
     * @param context Android Context
     * @return true 启用，false 禁用 | true if enabled, false otherwise
     */
    fun isTextActionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_TEXT_ACTION_PREFIX + "clipboard", true)
    }

    /**
     * 设置文本选中操作条开关 | Set text action enabled state
     *
     * 通过 PackageManager 启用/禁用 PROCESS_TEXT Activity 组件。| Enable/disable PROCESS_TEXT Activity component via PackageManager.
     * 禁用后不再出现在文本选中操作条。| Will not appear in text selection action bar when disabled.
     *
     * @param context Android Context
     * @param enabled 是否启用 | Whether to enable
     */
    fun setTextActionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_TEXT_ACTION_PREFIX + "clipboard", enabled).apply()
        setComponentEnabled(context, COMPONENT_TEXT_CLIPBOARD, enabled)
    }

    // ==================== 分享菜单 | Share Menu ====================

    /**
     * 检查分享菜单入口是否启用 | Check if share menu is enabled
     *
     * @param context Android Context
     * @return true 启用，false 禁用 | true if enabled, false otherwise
     */
    fun isShareMenuEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHARE_PREFIX + "enabled", true)
    }

    /**
     * 设置分享菜单入口开关 | Set share menu enabled state
     *
     * 通过 PackageManager 启用/禁用 ShareReceiverActivity 组件。| Enable/disable ShareReceiverActivity component via PackageManager.
     * 禁用后，ScrcpyBluetooth 不再出现在系统分享菜单中。| ScrcpyBluetooth will not appear in system share menu when disabled.
     *
     * @param context Android Context
     * @param enabled 是否启用 | Whether to enable
     */
    fun setShareMenuEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHARE_PREFIX + "enabled", enabled).apply()
        setComponentEnabled(context, COMPONENT_SHARE_RECEIVER, enabled)
    }

    // ==================== 批量操作 | Batch Operations ====================

    /**
     * 删除所有快捷方式 | Remove all shortcuts
     *
     * 清空桌面长按快捷方式、禁用文本选中操作条、禁用分享菜单入口。| Clear launcher shortcuts, disable text action, disable share menu.
     * 用于用户需要完全清除应用快捷入口的场景。| Used when user needs to completely clear app shortcut entries.
     *
     * @param context Android Context
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
     * 恢复所有快捷方式到默认状态（全部启用）| Restore all shortcuts to default state (all enabled)
     *
     * 重新启用所有快捷方式，恢复到应用初始安装状态。| Re-enable all shortcuts, restore to app initial install state.
     *
     * @param context Android Context
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

    // ==================== 工具方法 | Utility Methods ====================

    /**
     * 启用/禁用指定的 Activity 组件 | Enable/disable specified Activity component
     *
     * 通过 PackageManager.setComponentEnabledSetting() 实现。| Implemented via PackageManager.setComponentEnabledSetting().
     * 禁用后，系统不再向该组件分发 Intent（从分享菜单和文本操作条中消失）。| When disabled, system no longer distributes Intent to this component (disappear from share menu and text action bar).
     *
     * @param context Android Context
     * @param className 组件类名（完整路径）| Component class name (full path)
     * @param enabled 是否启用 | Whether to enable
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
     * 检查指定组件是否启用 | Check if specified component is enabled
     *
     * @param context Android Context
     * @param className 组件类名（完整路径）| Component class name (full path)
     * @return true 启用或默认状态，false 禁用 | true if enabled or default, false if disabled
     */
    fun isComponentEnabled(context: Context, className: String): Boolean {
        return try {
            val componentName = ComponentName(context, className)
            val state = context.packageManager.getComponentEnabledSetting(componentName)
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } catch (e: Exception) {
            true // 异常时默认认为启用 | Default to enabled on exception
        }
    }

    /**
     * 初始化快捷方式（在 Application.onCreate 中调用）| Initialize shortcuts (call in Application.onCreate)
     *
     * 执行初始化操作：| Perform initialization:
     * 1. 刷新桌面长按快捷方式 | Refresh launcher shortcuts
     * 2. 同步文本操作条和分享菜单组件状态 | Sync text action and share menu component state
     *
     * 组件状态说明：| Component state notes:
     * - 文本操作条和分享菜单的组件在 Manifest 中默认启用 | Text action and share menu components are enabled by default in Manifest
     * - 只有用户主动禁用后才需要同步状态 | Only need to sync state after user actively disables
     * - 首次安装时使用 Manifest 默认值 | Use Manifest default value on first install
     *
     * @param context Android Context
     */
    fun initialize(context: Context) {
        refreshLauncherShortcuts(context)
        // 同步文本操作条组件状态 | Sync text action component state
        val prefs = getPrefs(context)
        if (prefs.contains(KEY_TEXT_ACTION_PREFIX + "clipboard")) {
            val enabled = prefs.getBoolean(KEY_TEXT_ACTION_PREFIX + "clipboard", true)
            setComponentEnabled(context, COMPONENT_TEXT_CLIPBOARD, enabled)
        }
        // 同步分享菜单组件状态 | Sync share menu component state
        if (prefs.contains(KEY_SHARE_PREFIX + "enabled")) {
            val enabled = prefs.getBoolean(KEY_SHARE_PREFIX + "enabled", true)
            setComponentEnabled(context, COMPONENT_SHARE_RECEIVER, enabled)
        }
    }
}
