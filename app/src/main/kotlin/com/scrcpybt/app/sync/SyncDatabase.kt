package com.scrcpybt.app.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.scrcpybt.common.sync.BlockInfo
import com.scrcpybt.common.sync.FileInfo
import com.scrcpybt.common.sync.SyncConfig
import com.scrcpybt.common.sync.SyncState
import org.json.JSONArray
import org.json.JSONObject

/**
 * SQLite database for sync folder configurations and state.
 *
 * Table: sync_folders
 *   id TEXT PRIMARY KEY
 *   config TEXT NOT NULL  -- JSON serialized SyncConfig
 *   local_sequence INTEGER DEFAULT 0
 *   remote_sequence INTEGER DEFAULT 0
 *   last_sync_time INTEGER
 *   status TEXT DEFAULT 'idle'  -- idle, scanning, syncing, error, paused
 *
 * Table: sync_files
 *   folder_id TEXT NOT NULL
 *   relative_path TEXT NOT NULL
 *   size INTEGER
 *   last_modified INTEGER
 *   is_directory INTEGER
 *   block_hashes TEXT  -- JSON array of BlockInfo
 *   version INTEGER DEFAULT 0
 *   is_deleted INTEGER DEFAULT 0
 *   PRIMARY KEY (folder_id, relative_path)
 */
class SyncDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "SyncDatabase"
        private const val DB_NAME = "sync.db"
        private const val DB_VERSION = 1

        // Tables
        private const val TABLE_FOLDERS = "sync_folders"
        private const val TABLE_FILES = "sync_files"

        // Folder columns
        private const val COL_ID = "id"
        private const val COL_CONFIG = "config"
        private const val COL_LOCAL_SEQ = "local_sequence"
        private const val COL_REMOTE_SEQ = "remote_sequence"
        private const val COL_LAST_SYNC = "last_sync_time"
        private const val COL_STATUS = "status"

        // File columns
        private const val COL_FOLDER_ID = "folder_id"
        private const val COL_REL_PATH = "relative_path"
        private const val COL_SIZE = "size"
        private const val COL_LAST_MOD = "last_modified"
        private const val COL_IS_DIR = "is_directory"
        private const val COL_BLOCKS = "block_hashes"
        private const val COL_VERSION = "version"
        private const val COL_IS_DELETED = "is_deleted"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_FOLDERS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_CONFIG TEXT NOT NULL,
                $COL_LOCAL_SEQ INTEGER DEFAULT 0,
                $COL_REMOTE_SEQ INTEGER DEFAULT 0,
                $COL_LAST_SYNC INTEGER,
                $COL_STATUS TEXT DEFAULT 'idle'
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_FILES (
                $COL_FOLDER_ID TEXT NOT NULL,
                $COL_REL_PATH TEXT NOT NULL,
                $COL_SIZE INTEGER,
                $COL_LAST_MOD INTEGER,
                $COL_IS_DIR INTEGER,
                $COL_BLOCKS TEXT,
                $COL_VERSION INTEGER DEFAULT 0,
                $COL_IS_DELETED INTEGER DEFAULT 0,
                PRIMARY KEY ($COL_FOLDER_ID, $COL_REL_PATH)
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_files_folder ON $TABLE_FILES($COL_FOLDER_ID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FILES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FOLDERS")
        onCreate(db)
    }

    /**
     * Save or update folder configuration.
     */
    fun saveFolderConfig(config: SyncConfig) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, config.id)
            put(COL_CONFIG, configToJson(config))
        }

        db.insertWithOnConflict(TABLE_FOLDERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Log.d(TAG, "Saved folder config: ${config.id}")
    }

    /**
     * Get folder configuration by ID.
     */
    fun getFolderConfig(folderId: String): SyncConfig? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FOLDERS,
            arrayOf(COL_CONFIG),
            "$COL_ID = ?",
            arrayOf(folderId),
            null, null, null
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val json = it.getString(0)
                jsonToConfig(json)
            } else {
                null
            }
        }
    }

    /**
     * Get all folder configurations.
     */
    fun getAllFolderConfigs(): List<SyncConfig> {
        val configs = mutableListOf<SyncConfig>()
        val db = readableDatabase
        val cursor = db.query(TABLE_FOLDERS, arrayOf(COL_CONFIG), null, null, null, null, null)

        cursor.use {
            while (it.moveToNext()) {
                val json = it.getString(0)
                jsonToConfig(json)?.let { config -> configs.add(config) }
            }
        }

        return configs
    }

    /**
     * Delete folder configuration and all its files.
     */
    fun deleteFolderConfig(folderId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_FOLDERS, "$COL_ID = ?", arrayOf(folderId))
            db.delete(TABLE_FILES, "$COL_FOLDER_ID = ?", arrayOf(folderId))
            db.setTransactionSuccessful()
            Log.d(TAG, "Deleted folder config: $folderId")
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Save or update file info.
     */
    fun saveFileInfo(folderId: String, fileInfo: FileInfo) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_FOLDER_ID, folderId)
            put(COL_REL_PATH, fileInfo.relativePath)
            put(COL_SIZE, fileInfo.size)
            put(COL_LAST_MOD, fileInfo.lastModified)
            put(COL_IS_DIR, if (fileInfo.isDirectory) 1 else 0)
            put(COL_BLOCKS, blocksToJson(fileInfo.blocks))
            put(COL_VERSION, fileInfo.version)
            put(COL_IS_DELETED, if (fileInfo.isDeleted) 1 else 0)
        }

        db.insertWithOnConflict(TABLE_FILES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Get file info.
     */
    fun getFileInfo(folderId: String, relativePath: String): FileInfo? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FILES,
            arrayOf(COL_SIZE, COL_LAST_MOD, COL_IS_DIR, COL_BLOCKS, COL_VERSION, COL_IS_DELETED),
            "$COL_FOLDER_ID = ? AND $COL_REL_PATH = ?",
            arrayOf(folderId, relativePath),
            null, null, null
        )

        return cursor.use {
            if (it.moveToFirst()) {
                FileInfo(
                    relativePath = relativePath,
                    size = it.getLong(0),
                    lastModified = it.getLong(1),
                    isDirectory = it.getInt(2) != 0,
                    blocks = jsonToBlocks(it.getString(3)),
                    version = it.getLong(4),
                    isDeleted = it.getInt(5) != 0
                )
            } else {
                null
            }
        }
    }

    /**
     * Get all file infos for a folder.
     */
    fun getAllFileInfos(folderId: String): Map<String, FileInfo> {
        val files = mutableMapOf<String, FileInfo>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FILES,
            arrayOf(COL_REL_PATH, COL_SIZE, COL_LAST_MOD, COL_IS_DIR, COL_BLOCKS, COL_VERSION, COL_IS_DELETED),
            "$COL_FOLDER_ID = ?",
            arrayOf(folderId),
            null, null, null
        )

        cursor.use {
            while (it.moveToNext()) {
                val relativePath = it.getString(0)
                files[relativePath] = FileInfo(
                    relativePath = relativePath,
                    size = it.getLong(1),
                    lastModified = it.getLong(2),
                    isDirectory = it.getInt(3) != 0,
                    blocks = jsonToBlocks(it.getString(4)),
                    version = it.getLong(5),
                    isDeleted = it.getInt(6) != 0
                )
            }
        }

        return files
    }

    /**
     * Update sync state (sequences and last sync time).
     */
    fun updateSyncState(folderId: String, localSeq: Long, remoteSeq: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_LOCAL_SEQ, localSeq)
            put(COL_REMOTE_SEQ, remoteSeq)
            put(COL_LAST_SYNC, System.currentTimeMillis())
        }

        db.update(TABLE_FOLDERS, values, "$COL_ID = ?", arrayOf(folderId))
        Log.d(TAG, "Updated sync state: $folderId (local=$localSeq, remote=$remoteSeq)")
    }

    /**
     * Get sync state for a folder.
     */
    fun getSyncState(folderId: String): SyncState? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FOLDERS,
            arrayOf(COL_LOCAL_SEQ, COL_REMOTE_SEQ),
            "$COL_ID = ?",
            arrayOf(folderId),
            null, null, null
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val files = getAllFileInfos(folderId)
                SyncState(
                    folderId = folderId,
                    localSequence = it.getLong(0),
                    remoteSequence = it.getLong(1),
                    files = files
                )
            } else {
                null
            }
        }
    }

    /**
     * Update folder status.
     */
    fun updateFolderStatus(folderId: String, status: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_STATUS, status)
        }
        db.update(TABLE_FOLDERS, values, "$COL_ID = ?", arrayOf(folderId))
    }

    /**
     * Get folder status.
     */
    fun getFolderStatus(folderId: String): String? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FOLDERS,
            arrayOf(COL_STATUS),
            "$COL_ID = ?",
            arrayOf(folderId),
            null, null, null
        )

        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    // JSON serialization helpers

    private fun configToJson(config: SyncConfig): String {
        return JSONObject().apply {
            put("id", config.id)
            put("localPath", config.localPath)
            put("remotePath", config.remotePath)
            put("folderType", config.folderType.name)
            put("rescanIntervalSec", config.rescanIntervalSec)
            put("fsWatcherEnabled", config.fsWatcherEnabled)
            put("fsWatcherDelaySec", config.fsWatcherDelaySec)
            put("ignoreDelete", config.ignoreDelete)
            put("pullOrder", config.pullOrder.name)
            put("blockSizeKb", config.blockSizeKb)
            put("maxConflicts", config.maxConflicts)
            put("versioningType", config.versioningType.name)
            put("versioningParams", JSONObject(config.versioningParams))
            put("minDiskFreeMb", config.minDiskFreeMb)
            put("paused", config.paused)
            put("syncOnlyWhileCharging", config.syncOnlyWhileCharging)
            put("minBatteryPercent", config.minBatteryPercent)
            put("syncTimeWindowStart", config.syncTimeWindowStart)
            put("syncTimeWindowEnd", config.syncTimeWindowEnd)
            put("respectBatterySaver", config.respectBatterySaver)
        }.toString()
    }

    private fun jsonToConfig(json: String): SyncConfig? {
        return try {
            val obj = JSONObject(json)
            val versioningParamsJson = obj.optJSONObject("versioningParams")
            val versioningParams = mutableMapOf<String, String>()
            versioningParamsJson?.keys()?.forEach { key ->
                versioningParams[key] = versioningParamsJson.getString(key)
            }

            SyncConfig(
                id = obj.getString("id"),
                localPath = obj.getString("localPath"),
                remotePath = obj.getString("remotePath"),
                folderType = SyncConfig.FolderType.valueOf(obj.getString("folderType")),
                rescanIntervalSec = obj.getInt("rescanIntervalSec"),
                fsWatcherEnabled = obj.getBoolean("fsWatcherEnabled"),
                fsWatcherDelaySec = obj.getInt("fsWatcherDelaySec"),
                ignoreDelete = obj.getBoolean("ignoreDelete"),
                pullOrder = SyncConfig.PullOrder.valueOf(obj.getString("pullOrder")),
                blockSizeKb = obj.getInt("blockSizeKb"),
                maxConflicts = obj.getInt("maxConflicts"),
                versioningType = SyncConfig.VersioningType.valueOf(obj.getString("versioningType")),
                versioningParams = versioningParams,
                minDiskFreeMb = obj.getLong("minDiskFreeMb"),
                paused = obj.getBoolean("paused"),
                syncOnlyWhileCharging = obj.getBoolean("syncOnlyWhileCharging"),
                minBatteryPercent = obj.getInt("minBatteryPercent"),
                syncTimeWindowStart = obj.getInt("syncTimeWindowStart"),
                syncTimeWindowEnd = obj.getInt("syncTimeWindowEnd"),
                respectBatterySaver = obj.getBoolean("respectBatterySaver")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config JSON", e)
            null
        }
    }

    private fun blocksToJson(blocks: List<BlockInfo>): String {
        val array = JSONArray()
        blocks.forEach { block ->
            array.put(JSONObject().apply {
                put("offset", block.offset)
                put("size", block.size)
                put("hash", block.hash)
            })
        }
        return array.toString()
    }

    private fun jsonToBlocks(json: String?): List<BlockInfo> {
        if (json.isNullOrEmpty()) return emptyList()

        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                BlockInfo(
                    offset = obj.getLong("offset"),
                    size = obj.getInt("size"),
                    hash = obj.getString("hash")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse blocks JSON", e)
            emptyList()
        }
    }
}
