package com.scrcpybt.app.transfer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.scrcpybt.common.util.Logger

/**
 * SQLite database to persist transfer states for resume capability.
 */
class TransferStateDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_SQL)
        Logger.i(TAG, "Transfer state database created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
        Logger.i(TAG, "Transfer state database upgraded from v$oldVersion to v$newVersion")
    }

    /**
     * Save or update a transfer state.
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
     * Get a transfer state by ID.
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
     * Get all interrupted transfers for a device.
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
     * Get transfers that should be auto-resumed on reconnect.
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
     * Update transfer progress.
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
     * Mark transfer as completed.
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
     * Mark transfer as failed with reason.
     */
    fun markFailed(transferId: String, reason: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_STATUS, TransferState.STATUS_FAILED)
            put(COLUMN_CHECKSUM, reason) // Store failure reason in checksum field
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_NAME, values, "$COLUMN_TRANSFER_ID = ?", arrayOf(transferId))
        Logger.w(TAG, "Transfer marked as failed: $transferId - $reason")
    }

    /**
     * Mark transfer as interrupted (connection lost).
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
     * Delete a transfer state.
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
