package com.scrcpybt.common.sync

/**
 * 同步状态，追踪两台设备之间某个文件夹的同步情况。
 *
 * 记录的信息：
 * - 各端已知的文件列表和状态
 * - 需要从远端获取的文件/块
 * - 变更序列号（类似 Syncthing 的索引交换机制）
 *
 * 使用不可变数据类 + copy() 实现状态更新，保证线程安全。
 *
 * @property folderId 同步文件夹 ID
 * @property localSequence 本地变更计数器（每次本地变更递增）
 * @property remoteSequence 已知的远端最新序列号
 * @property files 文件信息映射（相对路径 → FileInfo）
 * @property needFiles 需要从远端获取的完整文件集合
 * @property needBlocks 需要从远端获取的文件块映射（文件路径 → 块索引集合）
 */
data class SyncState(
    val folderId: String,
    val localSequence: Long,
    val remoteSequence: Long,
    val files: Map<String, FileInfo>,
    val needFiles: Set<String> = emptySet(),
    val needBlocks: Map<String, Set<Int>> = emptyMap()
) {
    /** 标记需要从远端获取完整文件 */
    fun needFile(relativePath: String): SyncState =
        copy(needFiles = needFiles + relativePath)

    /** 标记需要从远端获取文件的特定块 */
    fun needFileBlocks(relativePath: String, blockIndices: Set<Int>): SyncState {
        val currentBlocks = needBlocks[relativePath] ?: emptySet()
        return copy(needBlocks = needBlocks + (relativePath to (currentBlocks + blockIndices)))
    }

    /** 标记文件已接收完毕（从需求列表移除） */
    fun markFileReceived(relativePath: String): SyncState =
        copy(
            needFiles = needFiles - relativePath,
            needBlocks = needBlocks - relativePath
        )

    /** 标记特定块已接收 */
    fun markBlocksReceived(relativePath: String, blockIndices: Set<Int>): SyncState {
        val currentBlocks = needBlocks[relativePath] ?: emptySet()
        val remainingBlocks = currentBlocks - blockIndices
        return if (remainingBlocks.isEmpty()) {
            copy(needBlocks = needBlocks - relativePath)
        } else {
            copy(needBlocks = needBlocks + (relativePath to remainingBlocks))
        }
    }

    /** 更新文件信息 */
    fun updateFile(fileInfo: FileInfo): SyncState =
        copy(files = files + (fileInfo.relativePath to fileInfo))

    /** 从状态中移除文件（用于删除传播） */
    fun removeFile(relativePath: String): SyncState =
        copy(files = files - relativePath)

    /** 递增本地序列号 */
    fun incrementLocalSequence(): SyncState =
        copy(localSequence = localSequence + 1)

    /** 更新远端序列号 */
    fun updateRemoteSequence(newRemoteSequence: Long): SyncState =
        copy(remoteSequence = newRemoteSequence)
}
