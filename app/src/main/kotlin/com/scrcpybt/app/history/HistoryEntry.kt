package com.scrcpybt.app.history

/**
 * 历史记录条目数据模型：统一所有操作的历史记录格式 | History entry data model for all operations
 *
 * 核心功能 | Core Functions:
 * - 统一记录所有操作的历史（剪贴板、文件传输、文件夹同步、分享转发、投屏）| Record history for all operations uniformly
 * - 支持按类型、设备、时间范围查询 | Support queries by type, device, time range
 * - 提供完整的操作审计日志 | Provide complete operation audit log
 *
 * 记录类型 | Record Types:
 * - clipboard: 剪贴板传输 | Clipboard transfer
 * - file_transfer: 文件传输 | File transfer
 * - folder_sync: 文件夹同步 | Folder sync
 * - share_forward: 分享转发 | Share forward
 * - screen_mirror: 屏幕投屏 | Screen mirror
 *
 * 使用场景 | Use Cases:
 * - 历史记录界面展示所有操作历史 | Display all operation history in history UI
 * - 统计功能使用频率和数据流量 | Statistics of feature usage frequency and data traffic
 * - 审计和故障排查 | Auditing and troubleshooting
 *
 * @property id 自增主键 | Auto-increment primary key
 * @property type 操作类型（见 TYPE_* 常量）| Operation type (see TYPE_* constants)
 * @property direction 方向（send/receive/bidirectional）| Direction (send/receive/bidirectional)
 * @property deviceName 设备名称 | Device name
 * @property deviceFingerprint 设备指纹 | Device fingerprint
 * @property timestamp 时间戳（毫秒）| Timestamp (milliseconds)
 * @property details JSON 格式的详细信息 | JSON formatted details
 * @property status 状态（success/failed/interrupted）| Status (success/failed/interrupted)
 * @property bytesTransferred 传输字节数 | Bytes transferred
 * @property fileName 文件名（文件传输时使用）| File name (used in file transfer)
 * @property filePath 本地路径（文件传输时使用）| Local path (used in file transfer)
 *
 * @see HistoryDatabase
 */
data class HistoryEntry(
    val id: Long,
    val type: String,
    val direction: String,
    val deviceName: String,
    val deviceFingerprint: String,
    val timestamp: Long,
    val details: String,
    val status: String,
    val bytesTransferred: Long,
    val fileName: String?,
    val filePath: String?
) {
    companion object {
        // 操作类型常量 | Operation type constants
        const val TYPE_CLIPBOARD = "clipboard" // 剪贴板传输 | Clipboard transfer
        const val TYPE_FILE_TRANSFER = "file_transfer" // 文件传输 | File transfer
        const val TYPE_FOLDER_SYNC = "folder_sync" // 文件夹同步 | Folder sync
        const val TYPE_SHARE_FORWARD = "share_forward" // 分享转发 | Share forward
        const val TYPE_SCREEN_MIRROR = "screen_mirror" // 屏幕投屏 | Screen mirror

        // 方向常量 | Direction constants
        const val DIR_SEND = "send" // 发送 | Send
        const val DIR_RECEIVE = "receive" // 接收 | Receive
        const val DIR_BIDIRECTIONAL = "bidirectional" // 双向 | Bidirectional

        // 状态常量 | Status constants
        const val STATUS_SUCCESS = "success" // 成功 | Success
        const val STATUS_FAILED = "failed" // 失败 | Failed
        const val STATUS_INTERRUPTED = "interrupted" // 中断 | Interrupted
    }
}
