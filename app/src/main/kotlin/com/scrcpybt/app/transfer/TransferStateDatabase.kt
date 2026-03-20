package com.scrcpybt.app.transfer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.scrcpybt.common.util.Logger

/**
 * 文件传输状态数据库：基于 SQLite 的断点续传状态持久化 | File transfer state database for resume capability
 *
 * 核心功能 | Core Functions:
 * - 持久化存储文件传输状态（支持断电恢复）| Persist file transfer state (survive power loss)
 * - 支持按设备指纹查询中断的传输 | Query interrupted transfers by device fingerprint
 * - 提供进度更新、状态标记等原子操作 | Provide atomic operations for progress update and status marking
 *
 * 表结构 | Table Schema:
 * - transfer_state 表存储所有传输状态 | transfer_state table stores all transfer states
 * - 主键: transfer_id (传输唯一标识符) | Primary key: transfer_id (transfer unique identifier)
 * - 索引: device_fingerprint, status（加速查询）| Indexes: device_fingerprint, status (speed up queries)
 *
 * 使用场景 | Use Cases:
 * - FileTransferService 定期调用 saveTransferState() 保存进度 | FileTransferService periodically calls saveTransferState() to save progress
 * - 重连时调用 getInterruptedTransfers() 查询未完成传输 | Call getInterruptedTransfers() on reconnect to query incomplete transfers
 * - 传输完成后调用 markCompleted() 标记完成 | Call markCompleted() after transfer completes
 *
 * 安全特性 | Security Features:
 * - 使用参数化查询防止 SQL 注入 | Use parameterized queries to prevent SQL injection
 * - 事务保证数据一致性 | Transaction ensures data consistency
 *
 * @param context Android Context
 * @see TransferState
 * @see TransferSettings
 */
class TransferStateDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    /**
     * 数据库创建回调 | Database creation callback
     */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_SQL)
        Logger.i(TAG, "Transfer state database created")
    }

    /**
     * 数据库升级回调 | Database upgrade callback
     *
     * 当前策略：删除旧表并重建（生产环境需考虑数据迁移）| Current strategy: drop and recreate (consider data migration in production)
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
        Logger.i(TAG, "Transfer state database upgraded from v$oldVersion to v$newVersion")
    }

    /**
     * 保存或更新传输状态 | Save or update a transfer state
     *
     * 使用 CONFLICT_REPLACE 策略，存在则更新，不存在则插入。| Use CONFLICT_REPLACE strategy: update if exists, insert if not.
     *
     * @param state 传输状态对象 | Transfer state object
     */
    fun saveTransferState(state: TransferState) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TRANSFER_ID, state.transferId)
            put(COLUMN_FILE_NAME, state.fileName)
            put(COLUMN_REMOTE_PATH, state.remotePath)
            put(COLUMN_LOCAL_PATH, state.localPath)
            put(COLUMN_TOTAL_SIZE, state.totalSize)
            put(COLUMN_TRANSFERRED_BYTES, state.transferredBytes)
            put(COLUMN_CHUNK_INDEX, state.chunkIndex)
            put(COLUMN_STATUS, state.status)
            put(COLUMN_DIRECTION, state.direction)
            put(COLUMN_DEVICE_FINGERPRINT, state.deviceFingerprint)
            put(COLUMN_CREATED_AT, state.createdAt)
            put(COLUMN_UPDATED_AT, state.updatedAt)
            put(COLUMN_CHECKSUM, state.checksum)
            put(COLUMN_AUTO_RESUME, if (state.autoResume) 1 else 0)
        }

        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Logger.d(TAG, "Saved transfer state: ${state.transferId}")
    }

    /**
     * 根据传输 ID 获取传输状态 | Get a transfer state by ID
     *
     * @param transferId 传输唯一标识符 | Transfer unique identifier
     * @return 传输状态对象，不存在返回 null | Transfer state object, or null if not exists
     */
    fun getTransferState(transferId: String): TransferState? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            null,
            "$COLUMN_TRANSFER_ID = ?",
            arrayOf(transferId),
            null, null, null
        )

        return cursor.use {
            if (it.moveToFirst()) {
                TransferState(
                    transferId = it.getString(it.getColumnIndexOrThrow(COLUMN_TRANSFER_ID)),
                    fileName = it.getString(it.getColumnIndexOrThrow(COLUMN_FILE_NAME)),
                    remotePath = it.getString(it.getColumnIndexOrThrow(COLUMN_REMOTE_PATH)),
                    localPath = it.getString(it.getColumnIndexOrThrow(COLUMN_LOCAL_PATH)),
                    totalSize = it.getLong(it.getColumnIndexOrThrow(COLUMN_TOTAL_SIZE)),
                    transferredBytes = it.getLong(it.getColumnIndexOrThrow(COLUMN_TRANSFERRED_BYTES)),
                    chunkIndex = it.getInt(it.getColumnIndexOrThrow(COLUMN_CHUNK_INDEX)),
                    status = it.getString(it.getColumnIndexOrThrow(COLUMN_STATUS)),
                    direction = it.getString(it.getColumnIndexOrThrow(COLUMN_DIRECTION)),
                    deviceFingerprint = it.getString(it.getColumnIndexOrThrow(COLUMN_DEVICE_FINGERPRINT)),
                    createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
                    updatedAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
                    checksum = it.getString(it.getColumnIndexOrThrow(COLUMN_CHECKSUM)),
                    autoResume = it.getInt(it.getColumnIndexOrThrow(COLUMN_AUTO_RESUME)) == 1
                )
            } else null
        }
    }

    /**
     * 获取指定设备的所有中断传输 | Get all interrupted transfers for a device
     *
     * 查询状态为 INTERRUPTED 的传输记录，按创建时间升序排列。| Query transfers with INTERRUPTED status, ordered by creation time ascending.
     *
     * @param deviceFingerprint 设备指纹 | Device fingerprint
     * @return 中断传输列表 | List of interrupted transfers
     */
    fun getInterruptedTransfers(deviceFingerprint: String): List<TransferState> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            null,
            "$COLUMN_DEVICE_FINGERPRINT = ? AND $COLUMN_STATUS = ?",
            arrayOf(deviceFingerprint, TransferState.STATUS_INTERRUPTED),
            null, null, "$COLUMN_CREATED_AT ASC"
        )

        val transfers = mutableListOf<TransferState>()
        cursor.use {
            while (it.moveToNext()) {
                transfers.add(
                    TransferState(
                        transferId = it.getString(it.getColumnIndexOrThrow(COLUMN_TRANSFER_ID)),
                        fileName = it.getString(it.getColumnIndexOrThrow(COLUMN_FILE_NAME)),
                        remotePath = it.getString(it.getColumnIndexOrThrow(COLUMN_REMOTE_PATH)),
                        localPath = it.getString(it.getColumnIndexOrThrow(COLUMN_LOCAL_PATH)),
                        totalSize = it.getLong(it.getColumnIndexOrThrow(COLUMN_TOTAL_SIZE)),
                        transferredBytes = it.getLong(it.getColumnIndexOrThrow(COLUMN_TRANSFERRED_BYTES)),
                        chunkIndex = it.getInt(it.getColumnIndexOrThrow(COLUMN_CHUNK_INDEX)),
                        status = it.getString(it.getColumnIndexOrThrow(COLUMN_STATUS)),
                        direction = it.getString(it.getColumnIndexOrThrow(COLUMN_DIRECTION)),
                        deviceFingerprint = it.getString(it.getColumnIndexOrThrow(COLUMN_DEVICE_FINGERPRINT)),
                        createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
                        updatedAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
                        checksum = it.getString(it.getColumnIndexOrThrow(COLUMN_CHECKSUM)),
                        autoResume = it.getInt(it.getColumnIndexOrThrow(COLUMN_AUTO_RESUME)) == 1
                    )
                )
            }
        }
        return transfers
    }

    /**
     * 获取应在重连时自动恢复的传输 | Get transfers that should be auto-resumed on reconnect
     *
     * 查询条件：状态为 INTERRUPTED 且 autoResume=true。| Query condition: status=INTERRUPTED and autoResume=true.
     *
     * @param deviceFingerprint 设备指纹 | Device fingerprint
     * @return 待自动恢复的传输列表 | List of transfers to auto-resume
     */
    fun getAutoResumeTransfers(deviceFingerprint: String): List<TransferState> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            null,
            "$COLUMN_DEVICE_FINGERPRINT = ? AND $COLUMN_STATUS = ? AND $COLUMN_AUTO_RESUME = ?",
            arrayOf(deviceFingerprint, TransferState.STATUS_INTERRUPTED, "1"),
            null, null, "$COLUMN_CREATED_AT ASC"
        )

        val transfers = mutableListOf<TransferState>()
        cursor.use {
            while (it.moveToNext()) {
                transfers.add(
                    TransferState(
                        transferId = it.getString(it.getColumnIndexOrThrow(COLUMN_TRANSFER_ID)),
                        fileName = it.getString(it.getColumnIndexOrThrow(COLUMN_FILE_NAME)),
                        remotePath = it.getString(it.getColumnIndexOrThrow(COLUMN_REMOTE_PATH)),
                        localPath = it.getString(it.getColumnIndexOrThrow(COLUMN_LOCAL_PATH)),
                        totalSize = it.getLong(it.getColumnIndexOrThrow(COLUMN_TOTAL_SIZE)),
                        transferredBytes = it.getLong(it.getColumnIndexOrThrow(COLUMN_TRANSFERRED_BYTES)),
                        chunkIndex = it.getInt(it.getColumnIndexOrThrow(COLUMN_CHUNK_INDEX)),
                        status = it.getString(it.getColumnIndexOrThrow(COLUMN_STATUS)),
                        direction = it.getString(it.getColumnIndexOrThrow(COLUMN_DIRECTION)),
                        deviceFingerprint = it.getString(it.getColumnIndexOrThrow(COLUMN_DEVICE_FINGERPRINT)),
                        createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
                        updatedAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
                        checksum = it.getString(it.getColumnIndexOrThrow(COLUMN_CHECKSUM)),
                        autoResume = it.getInt(it.getColumnIndexOrThrow(COLUMN_AUTO_RESUME)) == 1
                    )
                )
            }
        }
        return transfers
    }

    /**
     * 更新传输进度 | Update transfer progress
     *
     * 仅更新 transferredBytes、chunkIndex 和 updatedAt 字段。| Only update transferredBytes, chunkIndex, and updatedAt fields.
     *
     * @param transferId 传输 ID | Transfer ID
     * @param transferredBytes 已传输字节数 | Transferred bytes count
     * @param chunkIndex 当前块索引 | Current chunk index
     */
    fun updateProgress(transferId: String, transferredBytes: Long, chunkIndex: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TRANSFERRED_BYTES, transferredBytes)
            put(COLUMN_CHUNK_INDEX, chunkIndex)
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_NAME, values, "$COLUMN_TRANSFER_ID = ?", arrayOf(transferId))
    }

    /**
     * 标记传输为已完成 | Mark transfer as completed
     *
     * @param transferId 传输 ID | Transfer ID
     */
    fun markCompleted(transferId: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_STATUS, TransferState.STATUS_COMPLETED)
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_NAME, values, "$COLUMN_TRANSFER_ID = ?", arrayOf(transferId))
        Logger.i(TAG, "Transfer marked as completed: $transferId")
    }

    /**
     * 标记传输为失败 | Mark transfer as failed with reason
     *
     * 失败原因临时存储在 checksum 字段（未来可考虑增加专用字段）。| Failure reason temporarily stored in checksum field (consider dedicated field in future).
     *
     * @param transferId 传输 ID | Transfer ID
     * @param reason 失败原因 | Failure reason
     */
    fun markFailed(transferId: String, reason: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_STATUS, TransferState.STATUS_FAILED)
            put(COLUMN_CHECKSUM, reason) // 存储失败原因 | Store failure reason in checksum field
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_NAME, values, "$COLUMN_TRANSFER_ID = ?", arrayOf(transferId))
        Logger.w(TAG, "Transfer marked as failed: $transferId - $reason")
    }

    /**
     * 标记传输为中断（连接丢失）| Mark transfer as interrupted (connection lost)
     *
     * @param transferId 传输 ID | Transfer ID
     */
    fun markInterrupted(transferId: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_STATUS, TransferState.STATUS_INTERRUPTED)
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_NAME, values, "$COLUMN_TRANSFER_ID = ?", arrayOf(transferId))
        Logger.i(TAG, "Transfer marked as interrupted: $transferId")
    }

    /**
     * 删除传输状态记录 | Delete a transfer state
     *
     * @param transferId 传输 ID | Transfer ID
     */
    fun deleteTransferState(transferId: String) {
        val db = writableDatabase
        db.delete(TABLE_NAME, "$COLUMN_TRANSFER_ID = ?", arrayOf(transferId))
        Logger.d(TAG, "Deleted transfer state: $transferId")
    }

    companion object {
        private const val TAG = "TransferStateDatabase"
        private const val DATABASE_NAME = "transfer_state.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_NAME = "transfer_state"
        private const val COLUMN_TRANSFER_ID = "transfer_id"
        private const val COLUMN_FILE_NAME = "file_name"
        private const val COLUMN_REMOTE_PATH = "remote_path"
        private const val COLUMN_LOCAL_PATH = "local_path"
        private const val COLUMN_TOTAL_SIZE = "total_size"
        private const val COLUMN_TRANSFERRED_BYTES = "transferred_bytes"
        private const val COLUMN_CHUNK_INDEX = "chunk_index"
        private const val COLUMN_STATUS = "status"
        private const val COLUMN_DIRECTION = "direction"
        private const val COLUMN_DEVICE_FINGERPRINT = "device_fingerprint"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_UPDATED_AT = "updated_at"
        private const val COLUMN_CHECKSUM = "checksum"
        private const val COLUMN_AUTO_RESUME = "auto_resume"

        private const val CREATE_TABLE_SQL = """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_TRANSFER_ID TEXT PRIMARY KEY,
                $COLUMN_FILE_NAME TEXT NOT NULL,
                $COLUMN_REMOTE_PATH TEXT NOT NULL,
                $COLUMN_LOCAL_PATH TEXT NOT NULL,
                $COLUMN_TOTAL_SIZE INTEGER NOT NULL,
                $COLUMN_TRANSFERRED_BYTES INTEGER NOT NULL DEFAULT 0,
                $COLUMN_CHUNK_INDEX INTEGER NOT NULL DEFAULT 0,
                $COLUMN_STATUS TEXT NOT NULL,
                $COLUMN_DIRECTION TEXT NOT NULL,
                $COLUMN_DEVICE_FINGERPRINT TEXT,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_UPDATED_AT INTEGER NOT NULL,
                $COLUMN_CHECKSUM TEXT,
                $COLUMN_AUTO_RESUME INTEGER DEFAULT 1
            )
        """
    }
}
