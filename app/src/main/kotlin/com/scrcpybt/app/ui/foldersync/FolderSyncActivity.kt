package com.scrcpybt.app.ui.foldersync

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.scrcpybt.app.R
import com.scrcpybt.app.sync.*
import com.scrcpybt.common.sync.SyncConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 文件夹同步配置和监控界面（完整功能版）
 * Comprehensive UI for folder synchronization configuration and monitoring
 *
 * ### 核心功能 | Core Features
 * - 文件夹对配置（本地/远程路径）| Folder pair configuration (local/remote paths)
 * - 文件夹类型选择（仅发送、仅接收、双向同步）| Folder type selection (Send Only, Receive Only, Send-Receive)
 * - 版本控制配置（简单、分阶段、回收站、外部命令）| Versioning configuration (Simple, Staggered, Trashcan, External)
 * - 电源条件设置（仅充电时同步、最低电量、时间窗口）| Power condition settings (sync while charging, min battery, time window)
 * - .syncignore 模式编辑（类似 .gitignore）| .syncignore pattern editing (similar to .gitignore)
 * - 同步状态实时显示 | Real-time sync status display
 * - 手动触发同步 | Manual sync trigger
 * - 服务绑定实时更新 | Service binding for live updates
 *
 * ### 设计理念 | Design Philosophy
 * 参考 Syncthing 的文件夹同步模型，提供企业级的同步配置能力：
 * Inspired by Syncthing's folder sync model, providing enterprise-level sync configuration:
 * - 精细的冲突解决策略 | Fine-grained conflict resolution strategy
 * - 灵活的版本控制机制 | Flexible versioning mechanism
 * - 电源感知的后台同步 | Power-aware background sync
 * - 忽略模式支持（.syncignore）| Ignore pattern support (.syncignore)
 *
 * ### 技术实现 | Technical Implementation
 * - 通过 SyncService 执行后台同步任务 | Execute background sync tasks via SyncService
 * - 使用 SyncDatabase 持久化配置和同步状态 | Persist configuration and sync state using SyncDatabase
 * - 支持文件系统监视器（FileObserver）实时检测变化 | Support filesystem watcher (FileObserver) for real-time change detection
 * - 分块传输和断点续传（规划中）| Chunked transfer and resume (planned)
 *
 * @see SyncService
 * @see SyncDatabase
 * @see SyncConfig
 */
class FolderSyncActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FolderSyncActivity"
        /** 本地文件夹选择请求码 | Local folder selection request code */
        private const val REQUEST_LOCAL_FOLDER = 2001
        /** .syncignore 编辑请求码 | .syncignore edit request code */
        private const val REQUEST_SYNCIGNORE_EDIT = 2002
    }

    // === UI 组件 - 基本信息 | UI Components - Basic Info ===
    /** 本地文件夹路径输入框 | Local folder path input */
    private lateinit var etLocalFolder: EditText
    /** 远程文件夹路径输入框 | Remote folder path input */
    private lateinit var etRemoteFolder: EditText
    /** 选择本地文件夹按钮 | Pick local folder button */
    private lateinit var btnPickLocalFolder: ImageButton

    // === UI 组件 - 文件夹类型 | UI Components - Folder Type ===
    /** 文件夹类型下拉框 | Folder type spinner */
    private lateinit var spinnerFolderType: Spinner
    /** 文件夹类型描述文本 | Folder type description text */
    private lateinit var tvFolderTypeDesc: TextView

    // === UI 组件 - 版本控制 | UI Components - Versioning ===
    /** 版本控制类型下拉框 | Versioning type spinner */
    private lateinit var spinnerVersioningType: Spinner
    /** 版本控制参数面板 | Versioning parameters panel */
    private lateinit var layoutVersioningParams: LinearLayout
    /** 版本控制参数1输入框 | Versioning parameter 1 input */
    private lateinit var etVersioningParam1: EditText
    /** 版本控制参数1标签 | Versioning parameter 1 label */
    private lateinit var tvVersioningParam1Label: TextView

    // === UI 组件 - 电源条件 | UI Components - Power Conditions ===
    /** 仅在充电时同步复选框 | Sync only while charging checkbox */
    private lateinit var cbSyncOnlyWhileCharging: CheckBox
    /** 最低电量百分比输入框 | Minimum battery percent input */
    private lateinit var etMinBatteryPercent: EditText
    /** 遵守省电模式复选框 | Respect battery saver checkbox */
    private lateinit var cbRespectBatterySaver: CheckBox
    /** 时间窗口起始时间输入框（小时）| Time window start input (hour) */
    private lateinit var etTimeWindowStart: EditText
    /** 时间窗口结束时间输入框（小时）| Time window end input (hour) */
    private lateinit var etTimeWindowEnd: EditText

    // === UI 组件 - 高级设置 | UI Components - Advanced Settings ===
    /** 重新扫描间隔输入框（秒）| Rescan interval input (seconds) */
    private lateinit var etRescanInterval: EditText
    /** 文件系统监视器开关 | Filesystem watcher checkbox */
    private lateinit var cbFsWatcher: CheckBox
    /** 文件系统监视器延迟输入框（秒）| Filesystem watcher delay input (seconds) */
    private lateinit var etFsWatcherDelay: EditText
    /** 忽略删除操作复选框 | Ignore delete operations checkbox */
    private lateinit var cbIgnoreDelete: CheckBox
    /** 拉取顺序下拉框 | Pull order spinner */
    private lateinit var spinnerPullOrder: Spinner
    /** 块大小输入框（KB）| Block size input (KB) */
    private lateinit var etBlockSize: EditText
    /** 最大冲突数输入框 | Max conflicts input */
    private lateinit var etMaxConflicts: EditText
    /** 最小可用磁盘空间输入框（MB）| Minimum disk free input (MB) */
    private lateinit var etMinDiskFree: EditText

    // === UI 组件 - 同步控制 | UI Components - Sync Control ===
    /** 保存配置按钮 | Save configuration button */
    private lateinit var btnSaveConfig: Button
    /** 启动服务按钮 | Start service button */
    private lateinit var btnStartService: Button
    /** 停止服务按钮 | Stop service button */
    private lateinit var btnStopService: Button
    /** 手动同步按钮 | Manual sync button */
    private lateinit var btnManualSync: Button
    /** 编辑 .syncignore 按钮 | Edit .syncignore button */
    private lateinit var btnEditSyncIgnore: Button

    // === UI 组件 - 状态显示 | UI Components - Status Display ===
    /** 同步状态文本 | Sync status text */
    private lateinit var tvSyncStatus: TextView
    /** 上次同步时间文本 | Last sync time text */
    private lateinit var tvLastSyncTime: TextView
    /** 文件数量文本 | File count text */
    private lateinit var tvFileCount: TextView
    /** 电源状态文本 | Power status text */
    private lateinit var tvPowerStatus: TextView
    /** 同步进度条 | Sync progress bar */
    private lateinit var progressBar: ProgressBar
    /** 当前文件名文本 | Current file name text */
    private lateinit var tvCurrentFile: TextView
    /** 同步日志列表 | Sync log list */
    private lateinit var lvSyncLog: ListView

    // === 状态变量 | State Variables ===
    /** 同步数据库 | Sync database */
    private lateinit var database: SyncDatabase
    /** 当前文件夹ID | Current folder ID */
    private var currentFolderId: String? = null
    /** 当前配置 | Current configuration */
    private var currentConfig: SyncConfig? = null
    /** 同步日志列表 | Sync log list */
    private val syncLog = mutableListOf<String>()
    /** 日志适配器 | Log adapter */
    private lateinit var logAdapter: ArrayAdapter<String>

    // === 服务绑定 | Service Binding ===
    /** 同步服务实例 | Sync service instance */
    private var syncService: SyncService? = null
    /** 服务是否已绑定 | Service bound flag */
    private var serviceBound = false

    /**
     * 服务连接回调：绑定到 SyncService 以接收实时状态更新
     * Service connection callback: bind to SyncService for real-time status updates
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? SyncService.LocalBinder
            syncService = localBinder?.getService()
            serviceBound = true
            syncService?.setStatusListener(syncStatusListener)
            updateServiceStatus()
            addLog("Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            syncService?.setStatusListener(null)
            syncService = null
            serviceBound = false
            addLog("Service disconnected")
        }
    }

    /**
     * 同步状态监听器：接收 SyncService 的实时状态更新
     * Sync status listener: receive real-time status updates from SyncService
     */
    private val syncStatusListener = object : SyncService.SyncStatusListener {
        override fun onSyncStarted(folderId: String) {
            if (folderId == currentFolderId) {
                runOnUiThread {
                    tvSyncStatus.text = getString(R.string.sync_status_syncing)
                    progressBar.visibility = View.VISIBLE
                    addLog("Sync started")
                }
            }
        }

        override fun onSyncProgress(folderId: String, current: Int, total: Int, currentFile: String) {
            if (folderId == currentFolderId) {
                runOnUiThread {
                    progressBar.progress = if (total > 0) (current * 100 / total) else 0
                    tvCurrentFile.text = currentFile
                    tvCurrentFile.visibility = View.VISIBLE
                    addLog("Progress: $current/$total - $currentFile")
                }
            }
        }

        override fun onSyncCompleted(folderId: String, result: SyncResult) {
            if (folderId == currentFolderId) {
                runOnUiThread {
                    when (result) {
                        is SyncResult.Success -> {
                            tvSyncStatus.text = getString(R.string.sync_status_idle)
                            addLog("Sync completed: ${result.filesTransferred} files transferred, ${result.filesDeleted} deleted, ${result.conflictsResolved} conflicts")
                            updateLastSyncTime()
                        }
                        is SyncResult.Error -> {
                            tvSyncStatus.text = getString(R.string.sync_status_error, result.message)
                            addLog("Sync error: ${result.message}")
                        }
                        is SyncResult.Skipped -> {
                            tvSyncStatus.text = getString(R.string.sync_status_skipped)
                            addLog("Sync skipped: ${result.reason}")
                        }
                    }
                    progressBar.visibility = View.GONE
                    tvCurrentFile.visibility = View.GONE
                }
            }
        }

        override fun onSyncError(folderId: String, error: String) {
            if (folderId == currentFolderId) {
                runOnUiThread {
                    tvSyncStatus.text = getString(R.string.sync_status_error, error)
                    progressBar.visibility = View.GONE
                    addLog("Error: $error")
                }
            }
        }

        override fun onFolderChange(folderId: String) {
            if (folderId == currentFolderId) {
                runOnUiThread {
                    addLog("Folder changes detected")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_sync)

        supportActionBar?.apply {
            title = getString(R.string.folder_sync_title)
            setDisplayHomeAsUpEnabled(true)
        }

        database = SyncDatabase(this)
        initViews()
        setupListeners()
        loadOrCreateConfig()
        bindToService()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            syncService?.setStatusListener(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun initViews() {
        // Basic info
        etLocalFolder = findViewById(R.id.et_local_folder)
        etRemoteFolder = findViewById(R.id.et_remote_folder)
        btnPickLocalFolder = findViewById(R.id.btn_pick_local_folder)

        // Folder type
        spinnerFolderType = findViewById(R.id.spinner_folder_type)
        tvFolderTypeDesc = findViewById(R.id.tv_folder_type_desc)

        // Versioning
        spinnerVersioningType = findViewById(R.id.spinner_versioning_type)
        layoutVersioningParams = findViewById(R.id.layout_versioning_params)
        etVersioningParam1 = findViewById(R.id.et_versioning_param1)
        tvVersioningParam1Label = findViewById(R.id.tv_versioning_param1_label)

        // Power conditions
        cbSyncOnlyWhileCharging = findViewById(R.id.cb_sync_only_while_charging)
        etMinBatteryPercent = findViewById(R.id.et_min_battery_percent)
        cbRespectBatterySaver = findViewById(R.id.cb_respect_battery_saver)
        etTimeWindowStart = findViewById(R.id.et_time_window_start)
        etTimeWindowEnd = findViewById(R.id.et_time_window_end)

        // Advanced settings
        etRescanInterval = findViewById(R.id.et_rescan_interval)
        cbFsWatcher = findViewById(R.id.cb_fs_watcher)
        etFsWatcherDelay = findViewById(R.id.et_fs_watcher_delay)
        cbIgnoreDelete = findViewById(R.id.cb_ignore_delete)
        spinnerPullOrder = findViewById(R.id.spinner_pull_order)
        etBlockSize = findViewById(R.id.et_block_size)
        etMaxConflicts = findViewById(R.id.et_max_conflicts)
        etMinDiskFree = findViewById(R.id.et_min_disk_free)

        // Sync control
        btnSaveConfig = findViewById(R.id.btn_save_config)
        btnStartService = findViewById(R.id.btn_start_service)
        btnStopService = findViewById(R.id.btn_stop_service)
        btnManualSync = findViewById(R.id.btn_manual_sync)
        btnEditSyncIgnore = findViewById(R.id.btn_edit_syncignore)

        // Status display
        tvSyncStatus = findViewById(R.id.tv_sync_status)
        tvLastSyncTime = findViewById(R.id.tv_last_sync_time)
        tvFileCount = findViewById(R.id.tv_file_count)
        tvPowerStatus = findViewById(R.id.tv_power_status)
        progressBar = findViewById(R.id.progress_sync)
        tvCurrentFile = findViewById(R.id.tv_current_file)
        lvSyncLog = findViewById(R.id.lv_sync_log)

        // Setup adapters
        setupSpinners()

        logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, syncLog)
        lvSyncLog.adapter = logAdapter
    }

    private fun setupSpinners() {
        // Folder type spinner
        val folderTypes = arrayOf(
            getString(R.string.sync_folder_type_send_only),
            getString(R.string.sync_folder_type_receive_only),
            getString(R.string.sync_folder_type_send_receive)
        )
        spinnerFolderType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, folderTypes).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // Versioning type spinner
        val versioningTypes = arrayOf(
            getString(R.string.sync_versioning_none),
            getString(R.string.sync_versioning_trashcan),
            getString(R.string.sync_versioning_simple),
            getString(R.string.sync_versioning_staggered),
            getString(R.string.sync_versioning_external)
        )
        spinnerVersioningType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, versioningTypes).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // Pull order spinner
        val pullOrders = arrayOf(
            getString(R.string.sync_pull_order_random),
            getString(R.string.sync_pull_order_alphabetic),
            getString(R.string.sync_pull_order_smallest_first),
            getString(R.string.sync_pull_order_largest_first),
            getString(R.string.sync_pull_order_oldest_first),
            getString(R.string.sync_pull_order_newest_first)
        )
        spinnerPullOrder.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pullOrders).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun setupListeners() {
        btnPickLocalFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, REQUEST_LOCAL_FOLDER)
        }

        spinnerFolderType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFolderTypeDescription(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerVersioningType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateVersioningParamsVisibility(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSaveConfig.setOnClickListener { saveConfiguration() }
        btnStartService.setOnClickListener { startSyncService() }
        btnStopService.setOnClickListener { stopSyncService() }
        btnManualSync.setOnClickListener { triggerManualSync() }
        btnEditSyncIgnore.setOnClickListener { editSyncIgnore() }
    }

    /**
     * 加载或创建默认配置
     * Load or create default configuration
     *
     * 简化设计：使用单个默认文件夹配置
     * Simplified design: use a single default folder configuration
     * 在实际应用中，可以从 Intent 中读取特定的文件夹 ID
     * In a real app, you might load a specific folder by ID from intent
     */
    private fun loadOrCreateConfig() {
        // 简化设计：使用单个默认文件夹配置 | For simplicity, we use a single default folder config
        // 实际应用中可以从 Intent 加载特定文件夹 | In a real app, you might load a specific folder by ID from intent
        currentFolderId = "default_sync_folder"

        val config = database.getFolderConfig(currentFolderId!!)
        if (config != null) {
            loadConfig(config)
        } else {
            // 创建默认配置 | Create default config
            val defaultConfig = SyncConfig(
                id = currentFolderId!!,
                localPath = "/sdcard/SyncFolder",
                remotePath = "/sdcard/RemoteSync",
                folderType = SyncConfig.FolderType.SEND_RECEIVE,
                rescanIntervalSec = 3600,
                fsWatcherEnabled = true,
                fsWatcherDelaySec = 10,
                ignoreDelete = false,
                pullOrder = SyncConfig.PullOrder.RANDOM,
                blockSizeKb = 128,
                maxConflicts = 10,
                versioningType = SyncConfig.VersioningType.SIMPLE,
                versioningParams = mapOf("keep" to "5"),
                minDiskFreeMb = 100,
                paused = false,
                syncOnlyWhileCharging = false,
                minBatteryPercent = 0,
                syncTimeWindowStart = -1,
                syncTimeWindowEnd = -1,
                respectBatterySaver = true
            )
            database.saveFolderConfig(defaultConfig)
            loadConfig(defaultConfig)
        }

        addLog("Configuration loaded")
    }

    /**
     * 加载配置到 UI 控件
     * Load configuration into UI controls
     *
     * @param config 同步配置 | Sync configuration
     */
    private fun loadConfig(config: SyncConfig) {
        currentConfig = config

        etLocalFolder.setText(config.localPath)
        etRemoteFolder.setText(config.remotePath)

        spinnerFolderType.setSelection(config.folderType.ordinal)

        spinnerVersioningType.setSelection(config.versioningType.ordinal)
        when (config.versioningType) {
            SyncConfig.VersioningType.SIMPLE -> {
                etVersioningParam1.setText(config.versioningParams["keep"] ?: "5")
            }
            SyncConfig.VersioningType.TRASHCAN -> {
                etVersioningParam1.setText(config.versioningParams["cleanoutDays"] ?: "0")
            }
            SyncConfig.VersioningType.STAGGERED -> {
                etVersioningParam1.setText(config.versioningParams["maxAge"] ?: "365")
            }
            SyncConfig.VersioningType.EXTERNAL -> {
                etVersioningParam1.setText(config.versioningParams["command"] ?: "")
            }
            else -> {}
        }

        cbSyncOnlyWhileCharging.isChecked = config.syncOnlyWhileCharging
        etMinBatteryPercent.setText(config.minBatteryPercent.toString())
        cbRespectBatterySaver.isChecked = config.respectBatterySaver
        etTimeWindowStart.setText(if (config.syncTimeWindowStart >= 0) config.syncTimeWindowStart.toString() else "")
        etTimeWindowEnd.setText(if (config.syncTimeWindowEnd >= 0) config.syncTimeWindowEnd.toString() else "")

        etRescanInterval.setText(config.rescanIntervalSec.toString())
        cbFsWatcher.isChecked = config.fsWatcherEnabled
        etFsWatcherDelay.setText(config.fsWatcherDelaySec.toString())
        cbIgnoreDelete.isChecked = config.ignoreDelete
        spinnerPullOrder.setSelection(config.pullOrder.ordinal)
        etBlockSize.setText(config.blockSizeKb.toString())
        etMaxConflicts.setText(config.maxConflicts.toString())
        etMinDiskFree.setText(config.minDiskFreeMb.toString())

        updateLastSyncTime()
        updateFileCount()
    }

    /**
     * 保存配置到数据库
     * Save configuration to database
     *
     * 从 UI 控件读取所有配置项，验证后保存到数据库
     * Read all configuration items from UI controls, validate and save to database
     */
    private fun saveConfiguration() {
        try {
            val localPath = etLocalFolder.text.toString()
            val remotePath = etRemoteFolder.text.toString()

            if (localPath.isEmpty() || remotePath.isEmpty()) {
                Toast.makeText(this, R.string.sync_error_empty_paths, Toast.LENGTH_SHORT).show()
                return
            }

            val folderType = when (spinnerFolderType.selectedItemPosition) {
                0 -> SyncConfig.FolderType.SEND_ONLY
                1 -> SyncConfig.FolderType.RECEIVE_ONLY
                2 -> SyncConfig.FolderType.SEND_RECEIVE
                else -> SyncConfig.FolderType.SEND_RECEIVE
            }

            val versioningType = SyncConfig.VersioningType.values()[spinnerVersioningType.selectedItemPosition]
            val versioningParams = mutableMapOf<String, String>()
            when (versioningType) {
                SyncConfig.VersioningType.SIMPLE -> {
                    versioningParams["keep"] = etVersioningParam1.text.toString().ifEmpty { "5" }
                }
                SyncConfig.VersioningType.TRASHCAN -> {
                    versioningParams["cleanoutDays"] = etVersioningParam1.text.toString().ifEmpty { "0" }
                }
                SyncConfig.VersioningType.STAGGERED -> {
                    versioningParams["maxAge"] = etVersioningParam1.text.toString().ifEmpty { "365" }
                }
                SyncConfig.VersioningType.EXTERNAL -> {
                    versioningParams["command"] = etVersioningParam1.text.toString()
                }
                else -> {}
            }

            val pullOrder = SyncConfig.PullOrder.values()[spinnerPullOrder.selectedItemPosition]

            val config = SyncConfig(
                id = currentFolderId!!,
                localPath = localPath,
                remotePath = remotePath,
                folderType = folderType,
                rescanIntervalSec = etRescanInterval.text.toString().toIntOrNull() ?: 3600,
                fsWatcherEnabled = cbFsWatcher.isChecked,
                fsWatcherDelaySec = etFsWatcherDelay.text.toString().toIntOrNull() ?: 10,
                ignoreDelete = cbIgnoreDelete.isChecked,
                pullOrder = pullOrder,
                blockSizeKb = etBlockSize.text.toString().toIntOrNull() ?: 128,
                maxConflicts = etMaxConflicts.text.toString().toIntOrNull() ?: 10,
                versioningType = versioningType,
                versioningParams = versioningParams,
                minDiskFreeMb = etMinDiskFree.text.toString().toLongOrNull() ?: 100,
                paused = currentConfig?.paused ?: false,
                syncOnlyWhileCharging = cbSyncOnlyWhileCharging.isChecked,
                minBatteryPercent = etMinBatteryPercent.text.toString().toIntOrNull() ?: 0,
                syncTimeWindowStart = etTimeWindowStart.text.toString().toIntOrNull() ?: -1,
                syncTimeWindowEnd = etTimeWindowEnd.text.toString().toIntOrNull() ?: -1,
                respectBatterySaver = cbRespectBatterySaver.isChecked
            )

            database.saveFolderConfig(config)
            currentConfig = config

            Toast.makeText(this, R.string.sync_config_saved, Toast.LENGTH_SHORT).show()
            addLog("Configuration saved")

        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.sync_error_save_config, e.message), Toast.LENGTH_LONG).show()
            addLog("Error saving config: ${e.message}")
        }
    }

    private fun startSyncService() {
        SyncService.start(this)
        addLog("Starting sync service")
        Toast.makeText(this, R.string.sync_service_starting, Toast.LENGTH_SHORT).show()
    }

    private fun stopSyncService() {
        SyncService.stop(this)
        addLog("Stopping sync service")
        Toast.makeText(this, R.string.sync_service_stopping, Toast.LENGTH_SHORT).show()
    }

    private fun triggerManualSync() {
        if (currentFolderId == null) {
            Toast.makeText(this, R.string.sync_error_no_folder, Toast.LENGTH_SHORT).show()
            return
        }

        SyncService.resync(this, currentFolderId!!)
        addLog("Manual sync triggered")
        Toast.makeText(this, R.string.sync_manual_triggered, Toast.LENGTH_SHORT).show()
    }

    private fun editSyncIgnore() {
        val localPath = etLocalFolder.text.toString()
        if (localPath.isEmpty()) {
            Toast.makeText(this, R.string.sync_error_no_local_path, Toast.LENGTH_SHORT).show()
            return
        }

        val ignoreFile = File(localPath, ".syncignore")
        val currentContent = if (ignoreFile.exists()) {
            ignoreFile.readText()
        } else {
            "# .syncignore patterns\n# One pattern per line\n# Use .gitignore syntax\n\n"
        }

        val input = EditText(this).apply {
            setText(currentContent)
            minLines = 10
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.sync_edit_syncignore)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                try {
                    File(localPath).mkdirs()
                    ignoreFile.writeText(input.text.toString())
                    Toast.makeText(this, R.string.sync_syncignore_saved, Toast.LENGTH_SHORT).show()
                    addLog(".syncignore saved")
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.sync_error_save_syncignore, e.message), Toast.LENGTH_LONG).show()
                    addLog("Error saving .syncignore: ${e.message}")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bindToService() {
        val intent = Intent(this, SyncService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateServiceStatus() {
        if (serviceBound && currentFolderId != null) {
            val status = syncService?.getSyncStatus(currentFolderId!!)
            tvSyncStatus.text = status ?: getString(R.string.sync_status_unknown)
        }
    }

    private fun updateFolderTypeDescription(position: Int) {
        val description = when (position) {
            0 -> getString(R.string.sync_folder_type_send_only_desc)
            1 -> getString(R.string.sync_folder_type_receive_only_desc)
            2 -> getString(R.string.sync_folder_type_send_receive_desc)
            else -> ""
        }
        tvFolderTypeDesc.text = description
    }

    private fun updateVersioningParamsVisibility(position: Int) {
        when (position) {
            0 -> { // None
                layoutVersioningParams.visibility = View.GONE
            }
            1 -> { // Trashcan
                layoutVersioningParams.visibility = View.VISIBLE
                tvVersioningParam1Label.text = getString(R.string.sync_versioning_cleanout_days)
            }
            2 -> { // Simple
                layoutVersioningParams.visibility = View.VISIBLE
                tvVersioningParam1Label.text = getString(R.string.sync_versioning_keep_versions)
            }
            3 -> { // Staggered
                layoutVersioningParams.visibility = View.VISIBLE
                tvVersioningParam1Label.text = getString(R.string.sync_versioning_max_age_days)
            }
            4 -> { // External
                layoutVersioningParams.visibility = View.VISIBLE
                tvVersioningParam1Label.text = getString(R.string.sync_versioning_command)
            }
        }
    }

    private fun updateLastSyncTime() {
        if (currentFolderId == null) return

        val state = database.getSyncState(currentFolderId!!)
        if (state != null) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            tvLastSyncTime.text = getString(R.string.sync_last_sync_time, dateFormat.format(Date()))
        } else {
            tvLastSyncTime.text = getString(R.string.sync_never_synced)
        }
    }

    private fun updateFileCount() {
        if (currentFolderId == null) return

        val files = database.getAllFileInfos(currentFolderId!!)
        tvFileCount.text = getString(R.string.sync_file_count, files.size)
    }

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        syncLog.add(0, "[$timestamp] $message")
        if (syncLog.size > 100) {
            syncLog.removeAt(syncLog.size - 1)
        }
        logAdapter.notifyDataSetChanged()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK && data != null) {
            when (requestCode) {
                REQUEST_LOCAL_FOLDER -> {
                    data.data?.let { uri ->
                        // Convert content:// URI to file path if possible
                        // For simplicity, we just use a default path
                        // In a real app, you'd use Storage Access Framework properly
                        val path = "/sdcard/SyncFolder"
                        etLocalFolder.setText(path)
                        addLog("Local folder selected: $path")
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
