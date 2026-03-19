package com.scrcpybt.app.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.scrcpybt.common.util.Logger
import org.json.JSONObject

/**
 * SQLite-based history recording for all operations.
 * Uses android.database.sqlite.SQLiteOpenHelper.
 *
 * Table: transfer_history
 * Columns:
 *   id INTEGER PRIMARY KEY AUTOINCREMENT
 *   type TEXT NOT NULL  -- "clipboard", "file_transfer", "folder_sync", "share_forward", "screen_mirror"
 *   direction TEXT NOT NULL  -- "send" or "receive"
 *   device_name TEXT
 *   device_fingerprint TEXT
 *   timestamp INTEGER NOT NULL  -- epoch millis
 *   details TEXT  -- JSON blob with type-specific details
 *   status TEXT NOT NULL  -- "success", "failed", "interrupted"
 *   bytes_transferred INTEGER DEFAULT 0
 *   file_name TEXT  -- for file transfers
 *   file_path TEXT  -- local path
 */
class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

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

        // Create index on timestamp for faster queries
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_HISTORY($COL_TIMESTAMP DESC)")
        db.execSQL("CREATE INDEX idx_type ON $TABLE_HISTORY($COL_TYPE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // For now, just drop and recreate
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        onCreate(db)
    }

    /**
     * Record a clipboard transfer operation.
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
     * Record a file transfer operation.
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
     * Record a folder sync operation.
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
     * Record a share forward operation.
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
     * Record a screen mirror session.
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
     * Get history entries with optional filtering.
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
     * Clear all history entries.
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
