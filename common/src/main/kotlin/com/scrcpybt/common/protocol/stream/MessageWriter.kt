package com.scrcpybt.common.protocol.stream

import com.scrcpybt.common.protocol.MessageHeader
import com.scrcpybt.common.protocol.message.Message
import com.scrcpybt.common.protocol.message.MessageCodec
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 消息写入器。
 *
 * 将消息按协议格式写入输出流：序列化载荷 → 生成消息头 → 写入头+载荷 → flush。
 * 使用 16KB 缓冲区的 [BufferedOutputStream] 合并小写操作。
 *
 * 所有写操作通过 @Synchronized 保证线程安全，
 * 防止多线程并发写入导致消息头和载荷交错。
 *
 * @param outputStream 底层输出流（蓝牙 RFCOMM 或 Unix socket）
 */
class MessageWriter(private val outputStream: OutputStream) {
    /** 带缓冲的输出流（16KB 缓冲区） */
    private val output = BufferedOutputStream(outputStream, 16384)
    /** 消息序列号计数器（原子递增） */
    private val sequenceCounter = AtomicInteger(0)

    /**
     * 序列化并写入一条消息（自动生成消息头）。
     * 用于普通消息发送场景。
     */
    @Synchronized
    fun writeMessage(message: Message, flags: Byte = 0) {
        val payload = MessageCodec.serialize(message)
        val header = MessageHeader(message.type, flags, payload.size, sequenceCounter.getAndIncrement())
        output.write(header.toBytes())
        output.write(payload)
        output.flush()
    }

    /**
     * 写入原始消息字节（已含头部，不做序列化）。
     * 用于中继端透明转发：直接转发从上游读取的原始字节。
     */
    @Synchronized
    fun writeRawMessage(rawMessage: ByteArray) {
        output.write(rawMessage)
        output.flush()
    }

    /**
     * 写入指定的消息头和载荷。
     * 用于加密通道：由 [com.scrcpybt.common.crypto.EncryptedChannel] 自行构造头部。
     */
    @Synchronized
    fun write(header: MessageHeader, payload: ByteArray) {
        output.write(header.toBytes())
        output.write(payload)
        output.flush()
    }

    /** 获取底层输出流（用于传输切换） */
    fun getOutputStream(): OutputStream = outputStream

    /** 关闭底层输出流 */
    fun close() = output.close()
}
