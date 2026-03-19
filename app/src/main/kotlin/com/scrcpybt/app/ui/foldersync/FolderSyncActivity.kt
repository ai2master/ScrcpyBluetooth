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
 * Comprehensive UI for folder synchronization configuration and monitoring.
 *
 * Features:
 * - Folder pair configuration (local/remote paths)
 * - Folder type selection (Send Only, Receive Only, Send-Receive)
 * - Versioning configuration
 * - Power condition settings
 * - .syncignore pattern editing
 * - Sync status display
 * - Manual sync trigger
 * - Service binding for live updates
 */
class FolderSyncActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FolderSyncActivity"
        private const val REQUEST_LOCAL_FOLDER = 2001
        private const val REQUEST_SYNCIGNORE_EDIT = 2002
    }

    // UI Components - Basic Info
    private lateinit var etLocalFolder: EditText
    private lateinit var etRemoteFolder: EditText
    private lateinit var btnPickLocalFolder: ImageButton

    // UI Components - Folder Type
    private lateinit var spinnerFolderType: Spinner
    private lateinit var tvFolderTypeDesc: TextView

    // UI Components - Versioning
    private lateinit var spinnerVersioningType: Spinner
    private lateinit var layoutVersioningParams: LinearLayout
    private lateinit var etVersioningParam1: EditText
    private lateinit var tvVersioningParam1Label: TextView

    // UI Components - Power Conditions
    private lateinit var cbSyncOnlyWhileCharging: CheckBox
    private lateinit var etMinBatteryPercent: EditText
    private lateinit var cbRespectBatterySaver: CheckBox
    private lateinit var etTimeWindowStart: EditText
    private lateinit var etTimeWindowEnd: EditText

    // UI Components - Advanced Settings
    private lateinit var etRescanInterval: EditText
    private lateinit var cbFsWatcher: CheckBox
    private lateinit var etFsWatcherDelay: EditText
    private lateinit var cbIgnoreDelete: CheckBox
    private lateinit var spinnerPullOrder: Spinner
    private lateinit var etBlockSize: EditText
    private lateinit var etMaxConflicts: EditText
    private lateinit var etMinDiskFree: EditText

    // UI Components - Sync Control
    private lateinit var btnSaveConfig: Button
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnManualSync: Button
    private lateinit var btnEditSyncIgnore: Button

    // UI Components - Status Display
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvLastSyncTime: TextView
    private lateinit var tvFileCount: TextView
    private lateinit var tvPowerStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvCurrentFile: TextView
    private lateinit var lvSyncLog: ListView

    // State
    private lateinit var database: SyncDatabase
    private var currentFolderId: String? = null
    private var currentConfig: SyncConfig? = null
    private val syncLog = mutableListOf<String>()
    private lateinit var logAdapter: ArrayAdapter<String>

    // Service binding
    private var syncService: SyncService? = null
    private var serviceBound = false

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

    private fun loadOrCreateConfig() {
        // For simplicity, we use a single default folder config
        // In a real app, you might load a specific folder by ID from intent
        currentFolderId = "default_sync_folder"

        val config = database.getFolderConfig(currentFolderId!!)
        if (config != null) {
            loadConfig(config)
        } else {
            // Create default config
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
