package com.scrcpybt.common.util

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 字节缓冲区对象池。
 *
 * 避免帧编解码过程中频繁分配大字节数组导致 GC 压力。
 * 使用 [ConcurrentLinkedQueue] 实现无锁并发安全的对象回收。
 *
 * 使用方式：
 * ```
 * val pool = ByteBufferPool(width * height)
 * val buf = pool.acquire()  // 从池中获取或新建
 * // ... 使用 buf ...
 * pool.release(buf)          // 归还到池中复用
 * ```
 *
 * @property bufferSize 每个缓冲区的固定大小（字节）
 * @property maxPooled 池中最大缓存数量（默认 8）
 */
class ByteBufferPool(private val bufferSize: Int, private val maxPooled: Int = 8) {
    /** 空闲缓冲区队列 */
    private val pool = ConcurrentLinkedQueue<ByteArray>()

    /** 获取一个缓冲区（优先复用，无可用则新建） */
    fun acquire(): ByteArray = pool.poll() ?: ByteArray(bufferSize)

    /** 归还缓冲区到池中（仅接受正确大小且池未满时） */
    fun release(buffer: ByteArray) { if (buffer.size == bufferSize && pool.size < maxPooled) pool.offer(buffer) }
}
