package com.scrcpybt.common.sync

/**
 * 同步文件元信息。
 *
 * 描述同步文件夹中一个文件或目录的完整状态，
 * 包括块级哈希（用于增量同步）和版本跟踪。
 *
 * @property relativePath 相对于同步文件夹根目录的路径
 * @property size 文件大小（字节，目录为 0）
 * @property lastModified 最后修改时间（epoch 毫秒）
 * @property isDirectory 是否为目录
 * @property permissions Unix 文件权限（可选）
 * @property blocks 文件块列表（目录为空，文件包含 SHA-256 哈希）
 * @property isDeleted 删除标记（墓碑机制，用于跟踪删除传播）
 * @property version Lamport 时钟版本号（用于冲突解决）
 */
data class FileInfo(
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val permissions: Int = 0,
    val blocks: List<BlockInfo> = emptyList(),
    val isDeleted: Boolean = false,
    val version: Long = 0
) {
    init {
        require(relativePath.isNotBlank()) { "Relative path cannot be blank" }
        require(size >= 0) { "File size must be non-negative" }
        require(lastModified >= 0) { "Last modified time must be non-negative" }
        require(version >= 0) { "Version must be non-negative" }
        if (isDirectory) {
            require(blocks.isEmpty()) { "Directories should not have blocks" }
        }
    }

    /** 计算所有块的总大小（完整文件应等于 size） */
    fun calculateBlocksSize(): Long = blocks.sumOf { it.size.toLong() }

    /** 检查文件信息是否完整（所有块均存在） */
    fun isComplete(): Boolean = !isDirectory && calculateBlocksSize() == size

    /** 根据索引获取块信息 */
    fun getBlock(index: Int): BlockInfo? = blocks.getOrNull(index)

    /** 根据偏移量查找块信息 */
    fun findBlockByOffset(offset: Long): BlockInfo? = blocks.find { it.offset == offset }
}
