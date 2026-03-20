package com.scrcpybt.app.ui.shortcut

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.scrcpybt.app.R
import com.scrcpybt.app.util.ShortcutHelper

/**
 * 快捷方式自定义管理界面 | Shortcut configuration management activity
 *
 * 允许用户独立开关每个快捷入口：
 * Allows users to independently toggle each shortcut entry:
 * - 桌面长按快捷方式（剪贴板传输、文件传输、文件夹同步、分享穿透设置）
 *   Launcher shortcuts (clipboard, file transfer, folder sync, share forwarding)
 * - 文本选中操作条（"发送到远端剪贴板"）
 *   Text selection action ("Send to remote clipboard")
 * - 系统分享菜单（"发送到远端设备"）
 *   System share menu ("Send to remote device")
 *
 * 并提供"全部删除"和"恢复默认"操作。
 * Provides "Remove all" and "Restore defaults" operations.
 */
class ShortcutConfigActivity : AppCompatActivity() {

    /** 开关控件映射表，用于刷新状态 | Switch controls map for state refresh */
    private val switchMap = mutableMapOf<String, Switch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortcut_config)

        supportActionBar?.apply {
            title = getString(R.string.shortcut_config_title)
            setDisplayHomeAsUpEnabled(true)
        }

        val container = findViewById<LinearLayout>(R.id.shortcut_container)

        // === 桌面长按快捷方式区域 ===
        addSectionHeader(container, getString(R.string.shortcut_section_launcher))

        addSwitch(container, ShortcutHelper.SHORTCUT_CLIPBOARD,
            getString(R.string.shortcut_clipboard),
            getString(R.string.shortcut_clipboard_desc),
            ShortcutHelper.isLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_CLIPBOARD)
        ) { enabled ->
            ShortcutHelper.setLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_CLIPBOARD, enabled)
        }

        addSwitch(container, ShortcutHelper.SHORTCUT_FILE_TRANSFER,
            getString(R.string.shortcut_file_transfer),
            getString(R.string.shortcut_file_transfer_desc),
            ShortcutHelper.isLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_FILE_TRANSFER)
        ) { enabled ->
            ShortcutHelper.setLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_FILE_TRANSFER, enabled)
        }

        addSwitch(container, ShortcutHelper.SHORTCUT_FOLDER_SYNC,
            getString(R.string.shortcut_folder_sync),
            getString(R.string.shortcut_folder_sync_desc),
            ShortcutHelper.isLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_FOLDER_SYNC)
        ) { enabled ->
            ShortcutHelper.setLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_FOLDER_SYNC, enabled)
        }

        addSwitch(container, ShortcutHelper.SHORTCUT_SHARE_FORWARD,
            getString(R.string.shortcut_share_forward),
            getString(R.string.shortcut_share_forward_desc),
            ShortcutHelper.isLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_SHARE_FORWARD)
        ) { enabled ->
            ShortcutHelper.setLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_SHARE_FORWARD, enabled)
        }

        // === 文本选中操作条区域 ===
        addSectionHeader(container, getString(R.string.shortcut_section_text_action))

        addSwitch(container, "text_action_clipboard",
            getString(R.string.shortcut_text_action_clipboard),
            getString(R.string.shortcut_text_action_clipboard_desc),
            ShortcutHelper.isTextActionEnabled(this)
        ) { enabled ->
            ShortcutHelper.setTextActionEnabled(this, enabled)
        }

        // === 分享菜单区域 ===
        addSectionHeader(container, getString(R.string.shortcut_section_share))

        addSwitch(container, "share_menu",
            getString(R.string.shortcut_share_menu),
            getString(R.string.shortcut_share_menu_desc),
            ShortcutHelper.isShareMenuEnabled(this)
        ) { enabled ->
            ShortcutHelper.setShareMenuEnabled(this, enabled)
        }

        // === 操作按钮 ===
        val btnRemoveAll = findViewById<Button>(R.id.btn_remove_all)
        val btnRestoreDefaults = findViewById<Button>(R.id.btn_restore_defaults)

        btnRemoveAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.shortcut_remove_all_title)
                .setMessage(R.string.shortcut_remove_all_message)
                .setPositiveButton(R.string.ok) { _, _ ->
                    ShortcutHelper.removeAll(this)
                    refreshAllSwitches()
                    Toast.makeText(this, R.string.shortcut_all_removed, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        btnRestoreDefaults.setOnClickListener {
            ShortcutHelper.restoreDefaults(this)
            refreshAllSwitches()
            Toast.makeText(this, R.string.shortcut_defaults_restored, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 添加分类标题 | Add section header
     *
     * @param container 容器布局 | Container layout
     * @param title 标题文本 | Title text
     */
    private fun addSectionHeader(container: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        }
        container.addView(tv)
    }

    /**
     * 添加开关控件 | Add switch control
     *
     * @param container 容器布局 | Container layout
     * @param id 开关标识符 | Switch identifier
     * @param title 标题文本 | Title text
     * @param description 描述文本 | Description text
     * @param checked 初始选中状态 | Initial checked state
     * @param onChange 状态变化回调 | State change callback
     */
    private fun addSwitch(
        container: LinearLayout,
        id: String,
        title: String,
        description: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = title
            textSize = 15f
        }

        val tvDesc = TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }

        textLayout.addView(tvTitle)
        textLayout.addView(tvDesc)

        val switch = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked ->
                onChange(isChecked)
            }
        }
        switchMap[id] = switch

        layout.addView(textLayout)
        layout.addView(switch)
        container.addView(layout)
    }

    /**
     * 刷新所有开关状态 | Refresh all switch states
     */
    private fun refreshAllSwitches() {
        switchMap[ShortcutHelper.SHORTCUT_CLIPBOARD]?.isChecked =
            ShortcutHelper.isLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_CLIPBOARD)
        switchMap[ShortcutHelper.SHORTCUT_FILE_TRANSFER]?.isChecked =
            ShortcutHelper.isLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_FILE_TRANSFER)
        switchMap[ShortcutHelper.SHORTCUT_FOLDER_SYNC]?.isChecked =
            ShortcutHelper.isLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_FOLDER_SYNC)
        switchMap[ShortcutHelper.SHORTCUT_SHARE_FORWARD]?.isChecked =
            ShortcutHelper.isLauncherShortcutEnabled(this, ShortcutHelper.SHORTCUT_SHARE_FORWARD)
        switchMap["text_action_clipboard"]?.isChecked = ShortcutHelper.isTextActionEnabled(this)
        switchMap["share_menu"]?.isChecked = ShortcutHelper.isShareMenuEnabled(this)
    }

    /**
     * 将 dp 转换为像素 | Convert dp to pixels
     */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
