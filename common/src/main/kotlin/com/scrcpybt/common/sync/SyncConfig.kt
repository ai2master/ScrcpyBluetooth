package com.scrcpybt.common.sync

/**
 * 同步文件夹配置。
 *
 * 描述一对同步文件夹的完整配置信息，包括路径、同步方向、
 * 扫描间隔、块大小、版本控制策略、电源条件等。
 * 可序列化为 JSON 用于存储和传输。
 *
 * @property id 文件夹 ID（UUID 格式，唯一标识）
 * @property localPath 本地文件夹路径
 * @property remotePath 远端文件夹路径
 * @property folderType 同步类型（仅发送/仅接收/双向）
 * @property rescanIntervalSec 全量扫描间隔（秒，默认 1 小时）
 * @property fsWatcherEnabled 是否启用文件系统监听
 * @property fsWatcherDelaySec 文件变化累积延迟（秒，合并短时间内的多次变更）
 * @property ignoreDelete 是否忽略删除操作
 * @property pullOrder 拉取文件的排序策略
 * @property blockSizeKb 块大小（KB，默认 128KB）
 * @property maxConflicts 最大冲突保留数（-1=无限, 0=禁用）
 * @property versioningType 版本控制策略
 * @property versioningParams 版本控制参数（如保留天数等）
 * @property minDiskFreeMb 最小磁盘剩余空间（MB）
 * @property paused 是否暂停同步
 * @property syncOnlyWhileCharging 仅在充电时同步
 * @property minBatteryPercent 最低电量百分比（0=不限制）
 * @property syncTimeWindowStart 同步时间窗口开始（小时，-1=不限制）
 * @property syncTimeWindowEnd 同步时间窗口结束
 * @property respectBatterySaver 是否遵守系统省电模式
 */
data class SyncConfig(
    val id: String,
    val localPath: String,
    val remotePath: String,
    val folderType: FolderType,
    val rescanIntervalSec: Int = 3600,
    val fsWatcherEnabled: Boolean = true,
    val fsWatcherDelaySec: Int = 10,
    val ignoreDelete: Boolean = false,
    val pullOrder: PullOrder = PullOrder.RANDOM,
    val blockSizeKb: Int = 128,
    val maxConflicts: Int = 10,
    val versioningType: VersioningType = VersioningType.NONE,
    val versioningParams: Map<String, String> = emptyMap(),
    val minDiskFreeMb: Long = 100,
    val paused: Boolean = false,
    val syncOnlyWhileCharging: Boolean = false,
    val minBatteryPercent: Int = 0,
    val syncTimeWindowStart: Int = -1,
    val syncTimeWindowEnd: Int = -1,
    val respectBatterySaver: Boolean = true
) {
    /** 同步文件夹类型 */
    enum class FolderType {
        /** 仅发送：本地变更推送到远端，忽略远端变更 */
        SEND_ONLY,
        /** 仅接收：拉取远端变更，不推送本地变更 */
        RECEIVE_ONLY,
        /** 双向同步：本地和远端变更互相同步 */
        SEND_RECEIVE
    }

    /** 拉取文件排序策略 */
    enum class PullOrder {
        RANDOM, ALPHABETIC, SMALLEST_FIRST, LARGEST_FIRST, OLDEST_FIRST, NEWEST_FIRST
    }

    /** 版本控制策略（旧文件版本保留方式） */
    enum class VersioningType {
        /** 不保留旧版本 */
        NONE,
        /** 回收站模式：移动旧版本到 .stversions/，可配置自动清理天数 */
        TRASHCAN,
        /** 简单模式：保留 N 个带时间戳的旧版本 */
        SIMPLE,
        /** 阶梯模式：按递增时间间隔保留版本 */
        STAGGERED,
        /** 外部命令模式：调用用户自定义命令处理旧版本 */
        EXTERNAL
    }
}
