package com.scrcpybt.app.sync

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.scrcpybt.common.protocol.message.FolderSyncMessage
import com.scrcpybt.common.protocol.stream.MessageReader
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.sync.FileInfo
import com.scrcpybt.common.sync.IgnorePattern
import com.scrcpybt.common.sync.SyncConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min

/**
 * Core sync engine implementing Syncthing-like synchronization.
 *
 * Lifecycle:
 * 1. Load SyncConfig from database
 * 2. Check run conditions (power, time window, battery)
 * 3. Scan local folder → generate FileInfo with block hashes
 * 4. Exchange manifests with remote
 * 5. Calculate diff (which blocks need transfer)
 * 6. Transfer blocks according to pullOrder
 * 7. Apply received blocks to local files
 * 8. Handle conflicts
 * 9. Apply versioning to replaced files
 * 10. Update sync state
 */
class SyncEngine(
    private val context: Context,
    private val config: SyncConfig,
    private val writer: MessageWriter,
    private val reader: MessageReader,
    private val database: SyncDatabase,
    private val versioner: FileVersioner,
    private val powerChecker: PowerConditionChecker
) {
    private val blockHasher = BlockHasher(config.blockSizeKb * 1024)
    private val ignorePattern = IgnorePattern()
    private var syncId = 0
    private var localSequence = 0L
    private var remoteSequence = 0L

    companion object {
        private const val TAG = "SyncEngine"
        private const val SYNCIGNORE_FILE = ".syncignore"
    }

    init {
        // Load ignore patterns
        val ignoreFile = File(config.localPath, SYNCIGNORE_FILE)
        if (ignoreFile.exists()) {
            ignorePattern.loadFromFile(ignoreFile)
        }

        // Load state from database
        val state = database.getSyncState(config.id)
        localSequence = state?.localSequence ?: 0
        remoteSequence = state?.remoteSequence ?: 0
        syncId = Random().nextInt(Int.MAX_VALUE)
    }

    /**
     * Perform a complete sync cycle.
     */
    suspend fun sync(): SyncResult {
        try {
            Log.i(TAG, "Starting sync for folder: ${config.id}")

            // 1. Check run conditions
            if (!canSync()) {
                return SyncResult.Skipped("Run conditions not met")
            }

            // 2. Check disk space
            if (!hasEnoughDiskSpace()) {
                return SyncResult.Error("Insufficient disk space")
            }

            // 3. Scan local folder
            val localFiles = scan()
            Log.i(TAG, "Scanned ${localFiles.size} local files")

            // 4. Exchange manifests
            sendManifest(localFiles)
            val remoteFiles = receiveManifest()
            Log.i(TAG, "Received ${remoteFiles.size} remote files")

            // 5. Calculate diff
            val diff = calculateDiff(localFiles, remoteFiles)
            Log.i(TAG, "Calculated diff: ${diff.totalOperations()} operations")

            if (diff.isEmpty()) {
                return SyncResult.Success(0, 0, 0)
            }

            // 6. Apply diff
            applyDiff(diff)

            // 7. Save state
            saveState(localFiles)

            return SyncResult.Success(
                filesTransferred = diff.toDownload.size + diff.toUpload.size,
                filesDeleted = diff.toDelete.size + diff.toDeleteRemote.size,
                conflictsResolved = diff.conflicts.size
            )

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            return SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Scan local folder and generate FileInfo with block hashes.
     */
    fun scan(): Map<String, FileInfo> {
        val localFolder = File(config.localPath)
        if (!localFolder.exists() || !localFolder.isDirectory) {
            Log.w(TAG, "Local folder does not exist: ${config.localPath}")
            return emptyMap()
        }

        val files = mutableMapOf<String, FileInfo>()
        scanDirectory(localFolder, localFolder, files)
        return files
    }

    private fun scanDirectory(rootFolder: File, currentDir: File, files: MutableMap<String, FileInfo>) {
        val entries = currentDir.listFiles() ?: return

        for (entry in entries) {
            val relativePath = entry.relativeTo(rootFolder).path.replace('\\', '/')

            // Skip ignored files
            if (ignorePattern.isIgnored(relativePath)) {
                Log.d(TAG, "Ignoring: $relativePath")
                continue
            }

            // Skip .stversions directory
            if (relativePath.startsWith(".stversions/")) {
                continue
            }

            if (entry.isDirectory) {
                files[relativePath] = FileInfo(
                    relativePath = relativePath,
                    size = 0,
                    lastModified = entry.lastModified(),
                    isDirectory = true,
                    version = localSequence + 1
                )
                scanDirectory(rootFolder, entry, files)
            } else if (entry.isFile) {
                val blocks = blockHasher.hashFile(entry)
                files[relativePath] = FileInfo(
                    relativePath = relativePath,
                    size = entry.length(),
                    lastModified = entry.lastModified(),
                    isDirectory = false,
                    blocks = blocks,
                    version = localSequence + 1
                )
            }
        }
    }

    /**
     * Calculate diff between local and remote file states.
     */
    fun calculateDiff(local: Map<String, FileInfo>, remote: Map<String, FileInfo>): SyncDiff {
        val toDownload = mutableListOf<FileDownload>()
        val toUpload = mutableListOf<FileUpload>()
        val toDelete = mutableListOf<String>()
        val toDeleteRemote = mutableListOf<String>()
        val conflicts = mutableListOf<ConflictPair>()

        // Analyze files that exist remotely
        for ((path, remoteFile) in remote) {
            val localFile = local[path]

            when {
                localFile == null -> {
                    // File exists on remote but not local
                    if (config.folderType != SyncConfig.FolderType.SEND_ONLY) {
                        if (remoteFile.isDeleted) {
                            // Remote was deleted, nothing to do
                        } else {
                            // Download new file from remote
                            toDownload.add(FileDownload(remoteFile, emptyList()))
                        }
                    }
                }

                localFile.isDeleted && !remoteFile.isDeleted -> {
                    // Local deleted, remote not deleted
                    if (config.folderType == SyncConfig.FolderType.SEND_RECEIVE) {
                        if (!config.ignoreDelete) {
                            toDeleteRemote.add(path)
                        }
                    }
                }

                !localFile.isDeleted && remoteFile.isDeleted -> {
                    // Remote deleted, local not deleted
                    if (config.folderType != SyncConfig.FolderType.SEND_ONLY) {
                        if (!config.ignoreDelete) {
                            toDelete.add(path)
                        }
                    }
                }

                !localFile.isDeleted && !remoteFile.isDeleted -> {
                    // Both exist, check if they differ
                    if (localFile.isDirectory != remoteFile.isDirectory) {
                        // Type mismatch (file vs directory)
                        conflicts.add(ConflictPair(localFile, remoteFile))
                    } else if (!localFile.isDirectory) {
                        // Both are files, check if content differs
                        if (filesAreDifferent(localFile, remoteFile)) {
                            if (localFile.lastModified == remoteFile.lastModified) {
                                // Same timestamp but different content = conflict
                                conflicts.add(ConflictPair(localFile, remoteFile))
                            } else if (localFile.lastModified > remoteFile.lastModified) {
                                // Local is newer
                                if (config.folderType == SyncConfig.FolderType.SEND_RECEIVE) {
                                    val blocksToSend = findDifferentBlocks(localFile, remoteFile)
                                    toUpload.add(FileUpload(localFile, blocksToSend))
                                }
                            } else {
                                // Remote is newer
                                if (config.folderType != SyncConfig.FolderType.SEND_ONLY) {
                                    val neededBlocks = findDifferentBlocks(remoteFile, localFile)
                                    toDownload.add(FileDownload(remoteFile, neededBlocks))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Analyze files that exist only locally
        for ((path, localFile) in local) {
            if (path !in remote && !localFile.isDeleted) {
                // File exists locally but not remotely
                if (config.folderType != SyncConfig.FolderType.RECEIVE_ONLY) {
                    toUpload.add(FileUpload(localFile, emptyList()))
                }
            }
        }

        // Sort based on pull order
        sortDownloads(toDownload)

        return SyncDiff(toDownload, toUpload, toDelete, toDeleteRemote, conflicts)
    }

    private fun filesAreDifferent(file1: FileInfo, file2: FileInfo): Boolean {
        if (file1.size != file2.size) return true
        if (file1.blocks.size != file2.blocks.size) return true

        for (i in file1.blocks.indices) {
            if (file1.blocks[i].hash != file2.blocks[i].hash) {
                return true
            }
        }

        return false
    }

    private fun findDifferentBlocks(source: FileInfo, target: FileInfo): List<Int> {
        return blockHasher.calculateNeededBlocks(target.blocks, source.blocks)
    }

    private fun sortDownloads(downloads: MutableList<FileDownload>) {
        when (config.pullOrder) {
            SyncConfig.PullOrder.ALPHABETIC -> downloads.sortBy { it.fileInfo.relativePath }
            SyncConfig.PullOrder.SMALLEST_FIRST -> downloads.sortBy { it.fileInfo.size }
            SyncConfig.PullOrder.LARGEST_FIRST -> downloads.sortByDescending { it.fileInfo.size }
            SyncConfig.PullOrder.OLDEST_FIRST -> downloads.sortBy { it.fileInfo.lastModified }
            SyncConfig.PullOrder.NEWEST_FIRST -> downloads.sortByDescending { it.fileInfo.lastModified }
            SyncConfig.PullOrder.RANDOM -> downloads.shuffle()
        }
    }

    /**
     * Apply the calculated diff.
     */
    fun applyDiff(diff: SyncDiff) {
        // Handle conflicts first
        for (conflict in diff.conflicts) {
            handleConflict(conflict)
        }

        // Download files from remote
        for (download in diff.toDownload) {
            downloadFile(download)
        }

        // Upload files to remote
        for (upload in diff.toUpload) {
            uploadFile(upload)
        }

        // Delete local files
        for (path in diff.toDelete) {
            deleteLocalFile(path)
        }

        // Request remote to delete files
        for (path in diff.toDeleteRemote) {
            deleteRemoteFile(path)
        }
    }

    /**
     * Handle a conflict between local and remote files.
     */
    fun handleConflict(conflict: ConflictPair): ConflictResolution {
        Log.w(TAG, "Conflict detected: ${conflict.localFile.relativePath}")

        if (config.maxConflicts == 0) {
            // Conflicts disabled, keep local
            return ConflictResolution.KeepLocal
        }

        val resolution = resolveConflict(conflict)

        when (resolution) {
            ConflictResolution.KeepLocal -> {
                // Local wins, create conflict copy of remote
                createConflictCopy(conflict.remoteFile, "remote")
            }
            ConflictResolution.KeepRemote -> {
                // Remote wins, create conflict copy of local
                createConflictCopy(conflict.localFile, "local")
                downloadFile(FileDownload(conflict.remoteFile, emptyList()))
            }
            ConflictResolution.KeepBoth -> {
                // Keep both as conflict copies
                createConflictCopy(conflict.localFile, "local")
                createConflictCopy(conflict.remoteFile, "remote")
            }
            ConflictResolution.KeepNewer -> {
                if (conflict.localFile.lastModified >= conflict.remoteFile.lastModified) {
                    createConflictCopy(conflict.remoteFile, "remote")
                } else {
                    createConflictCopy(conflict.localFile, "local")
                    downloadFile(FileDownload(conflict.remoteFile, emptyList()))
                }
            }
            ConflictResolution.KeepLarger -> {
                if (conflict.localFile.size >= conflict.remoteFile.size) {
                    createConflictCopy(conflict.remoteFile, "remote")
                } else {
                    createConflictCopy(conflict.localFile, "local")
                    downloadFile(FileDownload(conflict.remoteFile, emptyList()))
                }
            }
        }

        return resolution
    }

    private fun resolveConflict(conflict: ConflictPair): ConflictResolution {
        // Default: keep newer version
        return ConflictResolution.KeepNewer
    }

    private fun createConflictCopy(fileInfo: FileInfo, source: String) {
        val localFile = File(config.localPath, fileInfo.relativePath)
        if (!localFile.exists()) return

        val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val nameWithoutExt = localFile.nameWithoutExtension
        val ext = localFile.extension
        val conflictName = if (ext.isNotEmpty()) {
            "$nameWithoutExt.sync-conflict-$timestamp-$source.$ext"
        } else {
            "$nameWithoutExt.sync-conflict-$timestamp-$source"
        }

        val conflictFile = File(localFile.parentFile, conflictName)
        localFile.copyTo(conflictFile, overwrite = true)
        Log.i(TAG, "Created conflict copy: ${conflictFile.name}")
    }

    private fun downloadFile(download: FileDownload) {
        Log.i(TAG, "Downloading: ${download.fileInfo.relativePath}")

        // Request blocks from remote
        val message = FolderSyncMessage(
            subType = FolderSyncMessage.SUB_SYNC_BLOCK_REQUEST,
            syncId = syncId,
            localPath = config.localPath,
            remotePath = config.remotePath,
            blockRequests = listOf(
                FolderSyncMessage.BlockRequest(
                    download.fileInfo.relativePath,
                    download.neededBlocks
                )
            )
        )
        writer.writeMessage(message)

        // Receive and apply blocks
        receiveBlocks(download.fileInfo, download.neededBlocks)
    }

    private fun receiveBlocks(fileInfo: FileInfo, neededBlocks: List<Int>) {
        val localFile = File(config.localPath, fileInfo.relativePath)
        localFile.parentFile?.mkdirs()

        // Version old file if exists
        if (localFile.exists()) {
            versioner.versionFile(localFile)
        }

        val blocksToReceive = if (neededBlocks.isEmpty()) {
            fileInfo.blocks.indices.toList()
        } else {
            neededBlocks
        }

        // 增量块写入使用 RandomAccessFile（需要 seek），全量写入使用 BufferedOutputStream（更高效）
        val isFullDownload = neededBlocks.isEmpty()
        if (isFullDownload) {
            BufferedOutputStream(FileOutputStream(localFile), 65536).use { bos ->
                for (blockIdx in blocksToReceive) {
                    val response = reader.readMessage() as? FolderSyncMessage
                        ?: throw IllegalStateException("Expected FolderSyncMessage")
                    if (response.subType != FolderSyncMessage.SUB_SYNC_BLOCK_DATA) {
                        throw IllegalStateException("Expected SYNC_BLOCK_DATA")
                    }
                    val blockData = response.blockData
                        ?: throw IllegalStateException("Block data is null")
                    bos.write(blockData.data)
                }
            }
        } else {
            RandomAccessFile(localFile, "rw").use { raf ->
                raf.setLength(fileInfo.size)
                for (blockIdx in blocksToReceive) {
                    val response = reader.readMessage() as? FolderSyncMessage
                        ?: throw IllegalStateException("Expected FolderSyncMessage")
                    if (response.subType != FolderSyncMessage.SUB_SYNC_BLOCK_DATA) {
                        throw IllegalStateException("Expected SYNC_BLOCK_DATA")
                    }
                    val blockData = response.blockData
                        ?: throw IllegalStateException("Block data is null")
                    raf.seek(blockData.offset)
                    raf.write(blockData.data)
                }
            }
        }

        // Set modification time
        localFile.setLastModified(fileInfo.lastModified)
        Log.i(TAG, "Downloaded: ${fileInfo.relativePath}")
    }

    private fun uploadFile(upload: FileUpload) {
        Log.i(TAG, "Uploading: ${upload.fileInfo.relativePath}")

        val localFile = File(config.localPath, upload.fileInfo.relativePath)
        if (!localFile.exists()) {
            Log.w(TAG, "File no longer exists: ${upload.fileInfo.relativePath}")
            return
        }

        val blocksToSend = if (upload.blocksToSend.isEmpty()) {
            upload.fileInfo.blocks.indices.toList()
        } else {
            upload.blocksToSend
        }

        RandomAccessFile(localFile, "r").use { raf ->
            for (blockIdx in blocksToSend) {
                val block = upload.fileInfo.blocks[blockIdx]
                val data = ByteArray(block.size)
                raf.seek(block.offset)
                raf.readFully(data)

                val message = FolderSyncMessage(
                    subType = FolderSyncMessage.SUB_SYNC_BLOCK_DATA,
                    syncId = syncId,
                    localPath = config.localPath,
                    remotePath = config.remotePath,
                    blockData = FolderSyncMessage.BlockData(
                        upload.fileInfo.relativePath,
                        blockIdx,
                        block.offset,
                        data
                    )
                )
                writer.writeMessage(message)
            }
        }

        Log.i(TAG, "Uploaded: ${upload.fileInfo.relativePath}")
    }

    private fun deleteLocalFile(path: String) {
        val file = File(config.localPath, path)
        if (file.exists()) {
            versioner.versionFile(file)
            file.delete()
            Log.i(TAG, "Deleted local: $path")
        }
    }

    private fun deleteRemoteFile(path: String) {
        val message = FolderSyncMessage(
            subType = FolderSyncMessage.SUB_SYNC_DELETE,
            syncId = syncId,
            localPath = config.localPath,
            remotePath = config.remotePath,
            manifestEntries = listOf(
                FolderSyncMessage.ManifestEntry(
                    relativePath = path,
                    size = 0,
                    lastModified = System.currentTimeMillis(),
                    isDirectory = false,
                    isDeleted = true
                )
            )
        )
        writer.writeMessage(message)
        Log.i(TAG, "Requested deletion on remote: $path")
    }

    private fun sendManifest(files: Map<String, FileInfo>) {
        val entries = files.values.map { fileInfo ->
            FolderSyncMessage.ManifestEntry(
                relativePath = fileInfo.relativePath,
                size = fileInfo.size,
                lastModified = fileInfo.lastModified,
                isDirectory = fileInfo.isDirectory,
                isDeleted = fileInfo.isDeleted,
                version = fileInfo.version,
                blocks = fileInfo.blocks
            )
        }

        val message = FolderSyncMessage(
            subType = FolderSyncMessage.SUB_SYNC_MANIFEST,
            syncId = syncId,
            localPath = config.localPath,
            remotePath = config.remotePath,
            sequence = localSequence,
            manifestEntries = entries
        )

        writer.writeMessage(message)
        Log.i(TAG, "Sent manifest with ${entries.size} entries")
    }

    private fun receiveManifest(): Map<String, FileInfo> {
        val response = reader.readMessage() as? FolderSyncMessage
            ?: throw IllegalStateException("Expected FolderSyncMessage")

        if (response.subType != FolderSyncMessage.SUB_SYNC_MANIFEST) {
            throw IllegalStateException("Expected SYNC_MANIFEST")
        }

        remoteSequence = response.sequence

        return response.manifestEntries.associate {
            it.relativePath to it.toFileInfo()
        }
    }

    private fun canSync(): Boolean {
        if (config.paused) return false
        return powerChecker.canSync()
    }

    private fun hasEnoughDiskSpace(): Boolean {
        val stat = StatFs(config.localPath)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val requiredBytes = config.minDiskFreeMb * 1024 * 1024
        return availableBytes >= requiredBytes
    }

    private fun saveState(files: Map<String, FileInfo>) {
        localSequence++
        database.updateSyncState(config.id, localSequence, remoteSequence)

        for (fileInfo in files.values) {
            database.saveFileInfo(config.id, fileInfo)
        }
    }
}

/**
 * Result of a sync operation.
 */
sealed class SyncResult {
    data class Success(
        val filesTransferred: Int,
        val filesDeleted: Int,
        val conflictsResolved: Int
    ) : SyncResult()

    data class Error(val message: String) : SyncResult()
    data class Skipped(val reason: String) : SyncResult()
}
