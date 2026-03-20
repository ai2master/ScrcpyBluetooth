package com.scrcpybt.app.transfer

/**
 * 文件传输状态数据模型：支持断点续传的状态持久化 | File transfer state data model for resume capability
 *
 * 核心功能 | Core Functions:
 * - 记录文件传输的完整进度（已传输字节、块索引、校验和）| Record complete transfer progress (transferred bytes, chunk index, checksum)
 * - 支持中断后恢复传输（存储在 TransferStateDatabase）| Support resume after interruption (stored in TransferStateDatabase)
 * - 区分传输方向和状态 | Distinguish transfer direction and status
 *
 * 断点续传机制 | Resume Mechanism:
 * 1. 传输过程中定期保存状态到数据库 | Periodically save state to database during transfer
 * 2. 中断后根据 transferId 恢复状态 | Restore state by transferId after interruption
 * 3. 从 chunkIndex 位置继续传输 | Continue transfer from chunkIndex position
 * 4. 校验 checksum 确保数据完整性 | Verify checksum to ensure data integrity
 *
 * 状态流转 | State Transitions:
 * - ACTIVE: 正在传输 | Currently transferring
 * - PAUSED: 用户手动暂停 | Manually paused by user
 * - INTERRUPTED: 连接中断 | Connection interrupted
 * - COMPLETED: 传输完成 | Transfer completed
 * - FAILED: 传输失败 | Transfer failed
 *
 * @property transferId 传输唯一标识符（UUID）| Transfer unique identifier (UUID)
 * @property fileName 文件名 | File name
 * @property remotePath 远端路径 | Remote path
 * @property localPath 本地路径 | Local path
 * @property totalSize 文件总大小（字节）| Total file size (bytes)
 * @property transferredBytes 已传输字节数 | Transferred bytes count
 * @property chunkIndex 当前块索引（用于断点续传）| Current chunk index (for resume)
 * @property status 传输状态 | Transfer status
 * @property direction 传输方向（send/receive）| Transfer direction (send/receive)
 * @property deviceFingerprint 设备指纹（用于匹配设备）| Device fingerprint (for device matching)
 * @property createdAt 创建时间戳 | Creation timestamp
 * @property updatedAt 更新时间戳 | Update timestamp
 * @property checksum 文件校验和（SHA-256）| File checksum (SHA-256)
 * @property autoResume 是否自动恢复 | Whether to auto-resume
 *
 * @see TransferStateDatabase
 * @see TransferSettings
 */
data class TransferState(
    val transferId: String,
    val fileName: String,
    val remotePath: String,
    val localPath: String,
    val totalSize: Long,
    var transferredBytes: Long,
    var chunkIndex: Int,
    var status: String,
    val direction: String,
    val deviceFingerprint: String,
    val createdAt: Long,
    var updatedAt: Long,
    var checksum: String?,
    var autoResume: Boolean = true
) {
    companion object {
        // 状态常量 | Status constants
        const val STATUS_ACTIVE = "active" // 正在传输 | Currently transferring
        const val STATUS_PAUSED = "paused" // 用户暂停 | User paused
        const val STATUS_INTERRUPTED = "interrupted" // 连接中断 | Connection interrupted
        const val STATUS_COMPLETED = "completed" // 传输完成 | Transfer completed
        const val STATUS_FAILED = "failed" // 传输失败 | Transfer failed

        // 方向常量 | Direction constants
        const val DIRECTION_SEND = "send" // 发送到远端 | Send to remote
        const val DIRECTION_RECEIVE = "receive" // 从远端接收 | Receive from remote
    }
}
