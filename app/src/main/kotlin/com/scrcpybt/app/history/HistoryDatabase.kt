package com.scrcpybt.app.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.scrcpybt.common.util.Logger
import org.json.JSONObject

/**
 * 历史记录数据库：基于 SQLite 的所有操作历史持久化 | History database for all operations using SQLite
 *
 * 核心功能 | Core Functions:
 * - 统一记录所有功能的操作历史（剪贴板、文件传输、文件夹同步、分享转发、投屏）| Record operation history for all features uniformly
 * - 支持按类型、时间、设备筛选查询 | Support filtering by type, time, device
 * - 提供操作审计和统计分析数据源 | Provide data source for operation audit and statistics
 *
 * 表结构 | Table Schema:
 * - 表名: transfer_history | Table name: transfer_history
 * - 主键: id (自增) | Primary key: id (auto-increment)
 * - 索引: timestamp (降序), type | Indexes: timestamp (desc), type
 *
 * 字段说明 | Column Descriptions:
 * - id: 自增主键 | Auto-increment primary key
 * - type: 操作类型（clipboard/file_transfer/folder_sync/share_forward/screen_mirror）| Operation type
 * - direction: 方向（send/receive/bidirectional）| Direction
 * - device_name: 设备名称 | Device name
 * - device_fingerprint: 设备指纹 | Device fingerprint
 * - timestamp: 时间戳（毫秒）| Timestamp (milliseconds)
 * - details: JSON 格式详细信息 | JSON formatted details
 * - status: 状态（success/failed/interrupted）| Status
 * - bytes_transferred: 传输字节数 | Bytes transferred
 * - file_name: 文件名（文件传输时使用）| File name (for file transfers)
 * - file_path: 本地路径（文件传输时使用）| Local path (for file transfers)
 *
 * 使用场景 | Use Cases:
 * - 各功能模块调用对应的 record*() 方法记录历史 | Each feature module calls corresponding record*() method to record history
 * - 历史记录界面调用 getHistory() 查询展示 | History UI calls getHistory() to query and display
 * - 设置界面调用 clearHistory() 清空历史 | Settings UI calls clearHistory() to clear history
 *
 * @param context Android Context
 * @see HistoryEntry
 */
class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    /**
     * 数据库创建回调：创建表和索引 | Database creation callback: create table and indexes
     */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_HISTORY (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TYPE TEXT NOT NULL,
                $COL_DIRECTION TEXT NOT NULL,
                $COL_DEVICE_NAME TEXT,
                $COL_DEVICE_FINGERPRINT TEXT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_DETAILS TEXT,
                $COL_STATUS TEXT NOT NULL,
                $COL_BYTES_TRANSFERRED INTEGER DEFAULT 0,
                $COL_FILE_NAME TEXT,
                $COL_FILE_PATH TEXT
            )
        """)

        // 创建索引加速查询 | Create indexes for faster queries
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_HISTORY($COL_TIMESTAMP DESC)")
        db.execSQL("CREATE INDEX idx_type ON $TABLE_HISTORY($COL_TYPE)")
    }

    /**
     * 数据库升级回调 | Database upgrade callback
     *
     * 当前策略：删除旧表并重建（生产环境需考虑数据迁移）| Current strategy: drop and recreate (consider data migration in production)
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 暂时采用简单策略：删除旧表并重建 | Simple strategy for now: drop and recreate
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        onCreate(db)
    }

    /**
     * 记录剪贴板传输操作 | Record a clipboard transfer operation
     *
     * @param direction 方向（send/receive）| Direction (send/receive)
     * @param deviceName 设备名称 | Device name
     * @param charCount 字符数（用于统计传输量）| Character count (for statistics)
     * @param status 状态（success/failed）| Status (success/failed)
     */
    fun recordClipboard(direction: String, deviceName: String, charCount: Int, status: String) {
        val details = JSONObject().apply {
            put("charCount", charCount)
        }.toString()

        val values = ContentValues().apply {
            put(COL_TYPE, HistoryEntry.TYPE_CLIPBOARD)
            put(COL_DIRECTION, direction)
            put(COL_DEVICE_NAME, deviceName)
            put(COL_DEVICE_FINGERPRINT, "")
            put(COL_TIMESTAMP, System.currentTimeMillis())
            put(COL_DETAILS, details)
            put(COL_STATUS, status)
            put(COL_BYTES_TRANSFERRED, charCount.toLong())
        }

        writableDatabase.insert(TABLE_HISTORY, null, values)
        Logger.d(TAG, "Clipboard history recorded: $direction, $charCount chars, $status")
    }

    /**
     * 记录文件传输操作 | Record a file transfer operation
     *
     * @param direction 方向（send/receive）| Direction (send/receive)
     * @param deviceName 设备名称 | Device name
     * @param fileName 文件名 | File name
     * @param filePath 本地路径 | Local path
     * @param size 文件大小（字节）| File size (bytes)
     * @param status 状态（success/failed/interrupted）| Status (success/failed/interrupted)
     */
    fun recordFileTransfer(
        direction: String,
        deviceName: String,
        fileName: String,
        filePath: String,
        size: Long,
        status: String
    ) {
        val details = JSONObject().apply {
            put("fileName", fileName)
            put("filePath", filePath)
            put("size", size)
        }.toString()

        val values = ContentValues().apply {
            put(COL_TYPE, HistoryEntry.TYPE_FILE_TRANSFER)
            put(COL_DIRECTION, direction)
            put(COL_DEVICE_NAME, deviceName)
            put(COL_DEVICE_FINGERPRINT, "")
            put(COL_TIMESTAMP, System.currentTimeMillis())
            put(COL_DETAILS, details)
            put(COL_STATUS, status)
            put(COL_BYTES_TRANSFERRED, size)
            put(COL_FILE_NAME, fileName)
            put(COL_FILE_PATH, filePath)
        }

        writableDatabase.insert(TABLE_HISTORY, null, values)
        Logger.d(TAG, "File transfer history recorded: $direction, $fileName, $size bytes, $status")
    }

    /**
     * 记录文件夹同步操作 | Record a folder sync operation
     *
     * @param deviceName 设备名称 | Device name
     * @param localPath 本地路径 | Local path
     * @param remotePath 远端路径 | Remote path
     * @param filesCount 同步文件数量 | Synced files count
     * @param status 状态（success/failed）| Status (success/failed)
     */
    fun recordFolderSync(
        deviceName: String,
        localPath: String,
        remotePath: String,
        filesCount: Int,
        status: String
    ) {
        val details = JSONObject().apply {
            put("localPath", localPath)
            put("remotePath", remotePath)
            put("filesCount", filesCount)
        }.toString()

        val values = ContentValues().apply {
            put(COL_TYPE, HistoryEntry.TYPE_FOLDER_SYNC)
            put(COL_DIRECTION, HistoryEntry.DIR_BIDIRECTIONAL)
            put(COL_DEVICE_NAME, deviceName)
            put(COL_DEVICE_FINGERPRINT, "")
            put(COL_TIMESTAMP, System.currentTimeMillis())
            put(COL_DETAILS, details)
            put(COL_STATUS, status)
            put(COL_BYTES_TRANSFERRED, 0)
        }

        writableDatabase.insert(TABLE_HISTORY, null, values)
        Logger.d(TAG, "Folder sync history recorded: $filesCount files, $status")
    }

    /**
     * 记录分享转发操作 | Record a share forward operation
     *
     * @param deviceName 设备名称 | Device name
     * @param contentType 内容类型（text/image/file）| Content type (text/image/file)
     * @param status 状态（success/failed）| Status (success/failed)
     */
    fun recordShareForward(deviceName: String, contentType: String, status: String) {
        val details = JSONObject().apply {
            put("contentType", contentType)
        }.toString()

        val values = ContentValues().apply {
            put(COL_TYPE, HistoryEntry.TYPE_SHARE_FORWARD)
            put(COL_DIRECTION, HistoryEntry.DIR_SEND)
            put(COL_DEVICE_NAME, deviceName)
            put(COL_DEVICE_FINGERPRINT, "")
            put(COL_TIMESTAMP, System.currentTimeMillis())
            put(COL_DETAILS, details)
            put(COL_STATUS, status)
            put(COL_BYTES_TRANSFERRED, 0)
        }

        writableDatabase.insert(TABLE_HISTORY, null, values)
        Logger.d(TAG, "Share forward history recorded: $contentType, $status")
    }

    /**
     * 记录屏幕投屏会话 | Record a screen mirror session
     *
     * @param deviceName 设备名称 | Device name
     * @param durationMs 会话时长（毫秒）| Session duration (milliseconds)
     * @param status 状态（success/failed/interrupted）| Status (success/failed/interrupted)
     */
    fun recordScreenMirror(deviceName: String, durationMs: Long, status: String) {
        val details = JSONObject().apply {
            put("durationMs", durationMs)
        }.toString()

        val values = ContentValues().apply {
            put(COL_TYPE, HistoryEntry.TYPE_SCREEN_MIRROR)
            put(COL_DIRECTION, HistoryEntry.DIR_RECEIVE)
            put(COL_DEVICE_NAME, deviceName)
            put(COL_DEVICE_FINGERPRINT, "")
            put(COL_TIMESTAMP, System.currentTimeMillis())
            put(COL_DETAILS, details)
            put(COL_STATUS, status)
            put(COL_BYTES_TRANSFERRED, 0)
        }

        writableDatabase.insert(TABLE_HISTORY, null, values)
        Logger.d(TAG, "Screen mirror history recorded: ${durationMs}ms, $status")
    }

    /**
     * 获取历史记录列表（支持可选过滤）| Get history entries with optional filtering
     *
     * @param type 操作类型（null 表示所有类型）| Operation type (null for all types)
     * @param limit 最大返回条数（默认 100）| Max return count (default 100)
     * @return 历史记录列表（按时间降序）| History entries list (ordered by timestamp desc)
     */
    fun getHistory(type: String? = null, limit: Int = 100): List<HistoryEntry> {
        val entries = mutableListOf<HistoryEntry>()

        val selection = if (type != null) "$COL_TYPE = ?" else null
        val selectionArgs = if (type != null) arrayOf(type) else null

        val cursor = readableDatabase.query(
            TABLE_HISTORY,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        )

        cursor.use {
            while (it.moveToNext()) {
                entries.add(
                    HistoryEntry(
                        id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                        type = it.getString(it.getColumnIndexOrThrow(COL_TYPE)),
                        direction = it.getString(it.getColumnIndexOrThrow(COL_DIRECTION)),
                        deviceName = it.getString(it.getColumnIndexOrThrow(COL_DEVICE_NAME)) ?: "",
                        deviceFingerprint = it.getString(it.getColumnIndexOrThrow(COL_DEVICE_FINGERPRINT)) ?: "",
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP)),
                        details = it.getString(it.getColumnIndexOrThrow(COL_DETAILS)) ?: "",
                        status = it.getString(it.getColumnIndexOrThrow(COL_STATUS)),
                        bytesTransferred = it.getLong(it.getColumnIndexOrThrow(COL_BYTES_TRANSFERRED)),
                        fileName = it.getString(it.getColumnIndexOrThrow(COL_FILE_NAME)),
                        filePath = it.getString(it.getColumnIndexOrThrow(COL_FILE_PATH))
                    )
                )
            }
        }

        return entries
    }

    /**
     * 清空所有历史记录 | Clear all history entries
     *
     * 删除表中所有数据，不删除表结构。| Delete all data in table, but keep table structure.
     */
    fun clearHistory() {
        writableDatabase.delete(TABLE_HISTORY, null, null)
        Logger.i(TAG, "History cleared")
    }

    companion object {
        private const val TAG = "HistoryDatabase"
        private const val DB_NAME = "scrcpy_history.db"
        private const val DB_VERSION = 1

        private const val TABLE_HISTORY = "transfer_history"
        private const val COL_ID = "id"
        private const val COL_TYPE = "type"
        private const val COL_DIRECTION = "direction"
        private const val COL_DEVICE_NAME = "device_name"
        private const val COL_DEVICE_FINGERPRINT = "device_fingerprint"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_DETAILS = "details"
        private const val COL_STATUS = "status"
        private const val COL_BYTES_TRANSFERRED = "bytes_transferred"
        private const val COL_FILE_NAME = "file_name"
        private const val COL_FILE_PATH = "file_path"
    }
}
