package com.scrcpybt.common.protocol.stream

import com.scrcpybt.common.protocol.MessageHeader
import com.scrcpybt.common.protocol.ProtocolConstants
import com.scrcpybt.common.protocol.message.Message
import com.scrcpybt.common.protocol.message.MessageCodec
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 消息读取器。
 *
 * 从输入流中按协议格式读取消息：先读取 14 字节消息头，
 * 再根据头部指定的载荷长度读取载荷数据。
 *
 * 使用 16KB 缓冲区的 [BufferedInputStream] 减少系统调用次数。
 * [readExact] 方法处理 TCP/蓝牙流的分片读取，确保读满指定字节。
 *
 * @param inputStream 底层输入流（蓝牙 RFCOMM 或 Unix socket）
 */
class MessageReader(inputStream: InputStream) {
    /** 带缓冲的输入流（16KB 缓冲区） */
    private val input = BufferedInputStream(inputStream, 16384)

    /** 读取 14 字节消息头并解析 */
    fun readHeader(): MessageHeader {
        val headerBytes = readExact(ProtocolConstants.HEADER_SIZE)
        return MessageHeader.fromBytes(headerBytes)
    }

    /**
     * 读取指定长度的载荷数据。
     * 安全检查：拒绝超过 MAX_PAYLOAD_SIZE 的请求，防止 OOM DoS 攻击。
     */
    fun readPayload(length: Int): ByteArray {
        if (length < 0 || length > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw IOException("非法载荷长度: $length (上限 ${ProtocolConstants.MAX_PAYLOAD_SIZE})")
        }
        return readExact(length)
    }

    /**
     * 读取一条完整消息（头部 + 载荷 → 反序列化）。
     * 用于普通消息接收场景。
     */
    fun readMessage(): Message {
        val header = readHeader()
        val payload = readPayload(header.payloadLength)
        return MessageCodec.deserialize(header.type, payload)
    }

    /**
     * 读取一条原始消息（头部 + 载荷，不反序列化）。
     * 用于中继端透明转发：不解析载荷，直接转发原始字节。
     */
    fun readRawMessage(): ByteArray {
        val headerBytes = readExact(ProtocolConstants.HEADER_SIZE)
        val header = MessageHeader.fromBytes(headerBytes)
        val payload = readExact(header.payloadLength)
        return headerBytes + payload
    }

    /**
     * 精确读取指定长度的字节。
     * 处理流的分片读取：循环读取直到填满缓冲区，
     * 遇到流结束则抛出 IOException。
     */
    private fun readExact(length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read == -1) throw IOException("End of stream (needed $length, got $offset)")
            offset += read
        }
        return data
    }

    /** 关闭底层输入流 */
    fun close() = input.close()
}
