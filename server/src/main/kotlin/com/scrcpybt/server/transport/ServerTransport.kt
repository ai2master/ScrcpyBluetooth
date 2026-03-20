package com.scrcpybt.server.transport

import com.scrcpybt.common.transport.Connection
import java.io.IOException

/**
 * 服务端传输层接口
 *
 * 定义服务端传输层的通用行为，用于接受来自控制端客户端的连接。
 *
 * ### 实现类
 * - BluetoothServerTransport: 蓝牙 RFCOMM 服务端
 * - UnixSocketServerTransport: Unix socket 服务端（ADB forward）
 *
 * Server transport interface that defines common behavior for accepting incoming
 * connections from controller clients via different transport methods.
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 * @see BluetoothServerTransport
 * @see UnixSocketServerTransport
 * @see Connection
 */
interface ServerTransport {

    /**
     * 等待并接受传入连接
     *
     * 阻塞直到建立连接为止。
     *
     * @return 已建立的连接对象
     * @throws IOException 如果连接失败
     */
    fun accept(): Connection

    /**
     * 关闭服务端传输层并释放资源
     */
    fun close()
}
