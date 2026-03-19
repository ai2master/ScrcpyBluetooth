package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 分享转发消息。
 *
 * 将控制端(A)的 Android 分享 Intent 转发到被控端(C)。
 * 被控端预配置了目标应用/组件，控制端只发送内容本身，
 * 不选择目标应用——这在被控端侧配置。
 *
 * 支持三种分享类型：
 * - 文本分享（ACTION_SEND + text/plain）
 * - 单文件分享（ACTION_SEND + 文件 URI）
 * - 多文件分享（ACTION_SEND_MULTIPLE）
 *
 * @property subType 子类型（文本/文件开始/分块/结束/完成）
 * @property shareId 分享会话 ID
 * @property mimeType MIME 类型
 * @property text 文本内容（文本分享时）
 * @property fileName 文件名（文件分享时）
 * @property fileSize 文件大小
 * @property fileIndex 当前文件序号（多文件时）
 * @property totalFiles 文件总数
 * @property chunkData 文件分块数据
 */
data class ShareForwardMessage(
    val subType: Byte = SUB_SHARE_TEXT,
    val shareId: Int = 0,
    val mimeType: String = "text/plain",
    val text: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val fileIndex: Int = 0,
    val totalFiles: Int = 1,
    val chunkData: ByteArray? = null
) : Message() {
    override val type = MessageType.SHARE_FORWARD

    override fun serialize(): ByteArray {
        val mimeBytes = mimeType.toByteArray(StandardCharsets.UTF_8)
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val nameBytes = fileName.toByteArray(StandardCharsets.UTF_8)
        val chunkLen = chunkData?.size ?: 0
        val buf = ByteBuffer.allocate(
            1 + 4 + 4 + mimeBytes.size + 4 + textBytes.size + 4 + nameBytes.size + 8 + 4 + 4 + 4 + chunkLen
        ).order(ByteOrder.BIG_ENDIAN)
        buf.put(subType); buf.putInt(shareId)
        buf.putInt(mimeBytes.size); buf.put(mimeBytes)
        buf.putInt(textBytes.size); buf.put(textBytes)
        buf.putInt(nameBytes.size); buf.put(nameBytes)
        buf.putLong(fileSize); buf.putInt(fileIndex); buf.putInt(totalFiles)
        buf.putInt(chunkLen); chunkData?.let { buf.put(it) }
        return buf.array()
    }

    companion object {
        /** 文本分享 */
        const val SUB_SHARE_TEXT: Byte = 0
        /** 文件分享开始 */
        const val SUB_SHARE_FILE_BEGIN: Byte = 1
        /** 文件分享分块数据 */
        const val SUB_SHARE_FILE_CHUNK: Byte = 2
        /** 文件分享结束 */
        const val SUB_SHARE_FILE_END: Byte = 3
        /** 整个分享会话完成 */
        const val SUB_SHARE_COMPLETE: Byte = 4

        /** 字符串字段最大长度 */
        private const val MAX_STRING_SIZE = 1 * 1024 * 1024  // 1MB
        private const val MAX_NAME_SIZE = 1024               // 1KB

        private fun checkLen(len: Int, max: Int, field: String) {
            if (len < 0 || len > max) throw java.io.IOException("$field 长度非法: $len (上限 $max)")
        }

        fun deserialize(data: ByteArray): ShareForwardMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val sub = buf.get(); val sid = buf.int
            val mimeLen = buf.int; checkLen(mimeLen, MAX_NAME_SIZE, "mimeType"); val mimeBytes = ByteArray(mimeLen); buf.get(mimeBytes)
            val textLen = buf.int; checkLen(textLen, MAX_STRING_SIZE, "text"); val textBytes = ByteArray(textLen); buf.get(textBytes)
            val nameLen = buf.int; checkLen(nameLen, MAX_NAME_SIZE, "fileName"); val nameBytes = ByteArray(nameLen); buf.get(nameBytes)
            val fSize = buf.long; val fIdx = buf.int; val total = buf.int
            val chunkLen = buf.int; val chunk = if (chunkLen > 0) ByteArray(chunkLen).also { buf.get(it) } else null
            return ShareForwardMessage(sub, sid, String(mimeBytes, StandardCharsets.UTF_8), String(textBytes, StandardCharsets.UTF_8), String(nameBytes, StandardCharsets.UTF_8), fSize, fIdx, total, chunk)
        }
    }

    override fun equals(other: Any?) = other is ShareForwardMessage && shareId == other.shareId && subType == other.subType
    override fun hashCode() = shareId * 31 + subType
}
