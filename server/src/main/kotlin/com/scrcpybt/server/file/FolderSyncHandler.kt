package com.scrcpybt.server.file

import com.scrcpybt.common.protocol.message.FolderSyncMessage
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.sync.BlockInfo
import com.scrcpybt.common.sync.IgnorePattern
import com.scrcpybt.common.util.Logger
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Handles folder synchronization on the controlled device with block-level sync support.
 *
 * This is the server-side (controlled device) handler that:
 * - Scans local folders and generates block-level manifests
 * - Responds to block requests from controller
 * - Receives and applies blocks from controller
 * - Handles deletions and conflicts
 * - Applies .syncignore patterns
 */
class FolderSyncHandler {

    companion object {
        private const val TAG = "FolderSyncHandler"
        private const val SYNCIGNORE_FILE = ".syncignore"
        private const val DEFAULT_BLOCK_SIZE = 128 * 1024  // 128KB
    }

    /**
     * 安全路径验证：防止路径遍历攻击。
     * 确保 relativePath 解析后仍在 baseDir 内部。
     *
     * @throws SecurityException 检测到路径遍历时抛出
     */
    private fun safeResolve(baseDir: File, relativePath: String): File {
        val resolved = File(baseDir, relativePath).canonicalFile
        val base = baseDir.canonicalFile
        if (!resolved.path.startsWith(base.path + File.separator) && resolved != base) {
            throw SecurityException("路径遍历攻击被阻止: $relativePath")
        }
        return resolved
    }

    private val ignorePatterns = mutableMapOf<String, IgnorePattern>()

    /**
     * Handle folder sync message from controller.
     */
    fun handleFolderSync(msg: FolderSyncMessage, writer: MessageWriter) {
        try {
            when (msg.subType) {
                FolderSyncMessage.SUB_SYNC_REQUEST -> handleSyncRequest(msg, writer)
                FolderSyncMessage.SUB_SYNC_MANIFEST -> handleManifest(msg, writer)
                FolderSyncMessage.SUB_SYNC_BLOCK_REQUEST -> handleBlockRequest(msg, writer)
                FolderSyncMessage.SUB_SYNC_BLOCK_DATA -> handleBlockData(msg)
                FolderSyncMessage.SUB_SYNC_DELETE -> handleDelete(msg)
                FolderSyncMessage.SUB_SYNC_COMPLETE -> handleSyncComplete(msg)
                FolderSyncMessage.SUB_SYNC_ERROR -> handleSyncError(msg)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error handling folder sync", e)
            sendError(msg, writer, e.message ?: "Unknown error")
        }
    }

    /**
     * Handle initial sync request - scan folder and send manifest.
     */
    private fun handleSyncRequest(msg: FolderSyncMessage, writer: MessageWriter) {
        val remotePath = msg.remotePath
        if (remotePath.isEmpty()) {
            Logger.w(TAG, "Sync request missing remote path")
            return
        }

        val folder = File(remotePath)
        if (!folder.exists() || !folder.isDirectory) {
            Logger.w(TAG, "Requested folder not found: $remotePath")
            sendError(msg, writer, "Folder not found: $remotePath")
            return
        }

        Logger.i(TAG, "Generating manifest for folder: $remotePath")

        // Load ignore patterns
        val ignorePattern = loadIgnorePattern(folder)

        // Generate manifest with block hashes
        val blockSize = if (msg.folderType == FolderSyncMessage.FOLDER_TYPE_SEND_RECEIVE) {
            DEFAULT_BLOCK_SIZE
        } else {
            DEFAULT_BLOCK_SIZE
        }

        val manifest = scanFolder(folder, ignorePattern, blockSize)

        // Send manifest
        val response = FolderSyncMessage(
            subType = FolderSyncMessage.SUB_SYNC_MANIFEST,
            syncId = msg.syncId,
            localPath = msg.localPath,
            remotePath = msg.remotePath,
            folderType = msg.folderType,
            sequence = System.currentTimeMillis(),  // Use timestamp as sequence
            manifestEntries = manifest
        )
        writer.writeMessage(response)

        Logger.i(TAG, "Sent manifest: ${manifest.size} entries")
    }

    /**
     * Handle manifest from controller (controller sends its local state).
     */
    private fun handleManifest(msg: FolderSyncMessage, writer: MessageWriter) {
        Logger.i(TAG, "Received manifest from controller: ${msg.manifestEntries.size} entries")
        // Controller will calculate diff and request blocks
        // Server just waits for block requests
    }

    /**
     * Handle request for specific blocks.
     */
    private fun handleBlockRequest(msg: FolderSyncMessage, writer: MessageWriter) {
        val remotePath = msg.remotePath
        if (remotePath.isEmpty()) {
            Logger.w(TAG, "Block request missing remote path")
            return
        }

        val rootFolder = File(remotePath)

        for (request in msg.blockRequests) {
            val file = safeResolve(rootFolder, request.relativePath)

            if (!file.exists() || !file.isFile) {
                Logger.w(TAG, "Requested file not found: ${request.relativePath}")
                continue
            }

            Logger.i(TAG, "Sending ${request.blockIndices.size} blocks for: ${request.relativePath}")

            // Read and send requested blocks
            RandomAccessFile(file, "r").use { raf ->
                val blockSize = DEFAULT_BLOCK_SIZE

                // If no specific blocks requested, send all
                val blocks = if (request.blockIndices.isEmpty()) {
                    val totalBlocks = ((file.length() + blockSize - 1) / blockSize).toInt()
                    (0 until totalBlocks).toList()
                } else {
                    request.blockIndices
                }

                for (blockIdx in blocks) {
                    val offset = blockIdx.toLong() * blockSize
                    if (offset >= file.length()) {
                        Logger.w(TAG, "Block index $blockIdx out of range for ${request.relativePath}")
                        continue
                    }

                    val remaining = file.length() - offset
                    val blockLen = minOf(blockSize.toLong(), remaining).toInt()
                    val data = ByteArray(blockLen)

                    raf.seek(offset)
                    raf.readFully(data)

                    // Send block
                    val response = FolderSyncMessage(
                        subType = FolderSyncMessage.SUB_SYNC_BLOCK_DATA,
                        syncId = msg.syncId,
                        localPath = msg.localPath,
                        remotePath = msg.remotePath,
                        blockData = FolderSyncMessage.BlockData(
                            relativePath = request.relativePath,
                            blockIndex = blockIdx,
                            offset = offset,
                            data = data
                        )
                    )
                    writer.writeMessage(response)
                }
            }
        }

        Logger.i(TAG, "Finished sending blocks")
    }

    /**
     * Handle incoming block data from controller.
     */
    private fun handleBlockData(msg: FolderSyncMessage) {
        val blockData = msg.blockData ?: return
        val remotePath = msg.remotePath

        if (remotePath.isEmpty()) {
            Logger.w(TAG, "Block data missing remote path")
            return
        }

        val rootFolder = File(remotePath)
        val file = safeResolve(rootFolder, blockData.relativePath)

        Logger.d(TAG, "Receiving block ${blockData.blockIndex} for: ${blockData.relativePath}")

        // Create parent directories
        file.parentFile?.mkdirs()

        // Write block to file
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(blockData.offset)
            raf.write(blockData.data)
        }

        Logger.d(TAG, "Wrote block at offset ${blockData.offset}, size ${blockData.data.size}")
    }

    /**
     * Handle deletion request.
     */
    private fun handleDelete(msg: FolderSyncMessage) {
        val remotePath = msg.remotePath
        if (remotePath.isEmpty()) return

        val rootFolder = File(remotePath)

        for (entry in msg.manifestEntries) {
            if (!entry.isDeleted) continue

            val file = safeResolve(rootFolder, entry.relativePath)
            if (file.exists()) {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
                Logger.i(TAG, "Deleted: ${entry.relativePath}")
            }
        }
    }

    /**
     * Handle sync complete notification.
     */
    private fun handleSyncComplete(msg: FolderSyncMessage) {
        Logger.i(TAG, "Folder sync completed: ${msg.remotePath}")
    }

    /**
     * Handle sync error.
     */
    private fun handleSyncError(msg: FolderSyncMessage) {
        Logger.e(TAG, "Sync error: ${msg.errorMessage}")
    }

    /**
     * Scan folder and generate manifest with block hashes.
     */
    private fun scanFolder(
        folder: File,
        ignorePattern: IgnorePattern,
        blockSize: Int
    ): List<FolderSyncMessage.ManifestEntry> {
        val entries = mutableListOf<FolderSyncMessage.ManifestEntry>()
        scanDirectory(folder, folder, ignorePattern, blockSize, entries)
        return entries
    }

    private fun scanDirectory(
        rootFolder: File,
        currentDir: File,
        ignorePattern: IgnorePattern,
        blockSize: Int,
        entries: MutableList<FolderSyncMessage.ManifestEntry>
    ) {
        val files = currentDir.listFiles() ?: return

        for (file in files) {
            val relativePath = file.relativeTo(rootFolder).path.replace('\\', '/')

            // Skip ignored files
            if (ignorePattern.isIgnored(relativePath)) {
                Logger.d(TAG, "Ignoring: $relativePath")
                continue
            }

            // Skip .stversions directory
            if (relativePath.startsWith(".stversions/")) {
                continue
            }

            if (file.isDirectory) {
                entries.add(
                    FolderSyncMessage.ManifestEntry(
                        relativePath = relativePath,
                        size = 0,
                        lastModified = file.lastModified(),
                        isDirectory = true,
                        isDeleted = false,
                        version = 0,
                        blocks = emptyList()
                    )
                )
                scanDirectory(rootFolder, file, ignorePattern, blockSize, entries)
            } else if (file.isFile) {
                val blocks = hashFile(file, blockSize)
                entries.add(
                    FolderSyncMessage.ManifestEntry(
                        relativePath = relativePath,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        isDirectory = false,
                        isDeleted = false,
                        version = 0,
                        blocks = blocks
                    )
                )
            }
        }
    }

    /**
     * Hash file into blocks.
     */
    private fun hashFile(file: File, blockSize: Int): List<BlockInfo> {
        if (!file.exists() || !file.isFile) {
            return emptyList()
        }

        val blocks = mutableListOf<BlockInfo>()
        val buffer = ByteArray(blockSize)
        var offset = 0L

        RandomAccessFile(file, "r").use { raf ->
            while (true) {
                val bytesRead = raf.read(buffer)
                if (bytesRead <= 0) break

                val hash = hashBlock(buffer, 0, bytesRead)
                blocks.add(BlockInfo(offset, bytesRead, hash))
                offset += bytesRead
            }
        }

        return blocks
    }

    /**
     * Hash a block using SHA-256.
     */
    private fun hashBlock(data: ByteArray, offset: Int, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data, offset, length)
        return digest.digest().toHex()
    }

    /**
     * Load ignore pattern for folder.
     */
    private fun loadIgnorePattern(folder: File): IgnorePattern {
        val cacheKey = folder.absolutePath
        ignorePatterns[cacheKey]?.let { return it }

        val pattern = IgnorePattern()
        val ignoreFile = File(folder, SYNCIGNORE_FILE)
        if (ignoreFile.exists()) {
            pattern.loadFromFile(ignoreFile)
            Logger.i(TAG, "Loaded ignore patterns from: ${ignoreFile.path}")
        }

        ignorePatterns[cacheKey] = pattern
        return pattern
    }

    /**
     * Send error message to controller.
     */
    private fun sendError(msg: FolderSyncMessage, writer: MessageWriter, errorMessage: String) {
        val response = FolderSyncMessage(
            subType = FolderSyncMessage.SUB_SYNC_ERROR,
            syncId = msg.syncId,
            localPath = msg.localPath,
            remotePath = msg.remotePath,
            errorMessage = errorMessage
        )
        writer.writeMessage(response)
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
