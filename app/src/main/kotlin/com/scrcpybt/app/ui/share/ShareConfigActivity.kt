package com.scrcpybt.app.ui.share

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.scrcpybt.app.R

/**
 * 分享转发配置界面（运行在被控设备上）
 *
 * 功能说明：
 * - 配置分享转发的目标应用
 * - 当控制端发起分享操作时，自动将内容转发到被控设备的指定应用
 * - 支持选择任何能接收分享的已安装应用
 *
 * 配置项：
 * - 启用/禁用分享转发功能
 * - 目标应用包名
 * - 目标组件名（Activity）
 */
class ShareConfigActivity : AppCompatActivity() {
    companion object {
        /** SharedPreferences 名称 */
        private const val PREFS_NAME = "share_config"
        /** 启用状态的键名 */
        private const val KEY_ENABLED = "enabled"
        /** 目标应用的键名 */
        private const val KEY_TARGET_APP = "target_app"
        /** 组件名的键名 */
        private const val KEY_COMPONENT = "component"
    }

    /** 启用开关 */
    private lateinit var switchEnable: SwitchCompat
    /** 目标应用下拉框 */
    private lateinit var spinnerTargetApp: Spinner
    /** 组件名输入框 */
    private lateinit var etComponentName: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_config)

        supportActionBar?.apply {
            title = "Share Forwarding Config"
            setDisplayHomeAsUpEnabled(true)
        }

        switchEnable = findViewById(R.id.switch_share_enable)
        spinnerTargetApp = findViewById(R.id.spinner_target_app)
        etComponentName = findViewById(R.id.et_component_name)
        val btnSave = findViewById<Button>(R.id.btn_save_config)

        // 加载所有能接收分享的已安装应用
        val apps = getShareCapableApps()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, apps)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTargetApp.adapter = adapter

        spinnerTargetApp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // 根据选择的应用自动填充组件名
                val appName = apps[position]
                etComponentName.setText("$appName/.MainActivity")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 加载已保存的配置
        loadConfig()

        btnSave.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * 获取所有能接收分享的应用包名列表
     *
     * @return 应用包名列表
     */
    private fun getShareCapableApps(): List<String> {
        val pm = packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
        }
        val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return activities.map { it.activityInfo.packageName }.distinct()
    }

    /**
     * 从 SharedPreferences 加载已保存的配置
     */
    private fun loadConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switchEnable.isChecked = prefs.getBoolean(KEY_ENABLED, false)
        val targetApp = prefs.getString(KEY_TARGET_APP, "")
        val component = prefs.getString(KEY_COMPONENT, "")

        // 尝试选中已保存的应用
        val apps = (spinnerTargetApp.adapter as ArrayAdapter<String>)
        val position = (0 until apps.count).firstOrNull { apps.getItem(it) == targetApp } ?: 0
        spinnerTargetApp.setSelection(position)

        etComponentName.setText(component)
    }

    /**
     * 保存配置到 SharedPreferences
     */
    private fun saveConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, switchEnable.isChecked)
            putString(KEY_TARGET_APP, spinnerTargetApp.selectedItem.toString())
            putString(KEY_COMPONENT, etComponentName.text.toString())
            apply()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
