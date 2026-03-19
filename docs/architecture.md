# ScrcpyBluetooth 技术架构

## 概述

ScrcpyBluetooth 是一个 Android 对 Android 的屏幕镜像和远程控制系统，采用三方架构设计，通过蓝牙 RFCOMM 或 USB ADB 隧道传输加密数据。

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.22 |
| 构建 | Gradle 8.5 + AGP 8.2.2 |
| 最低版本 | Android 9.0 (API 28) |
| 加密 | AES-256-GCM + ECDH P-256 + HKDF-SHA256 (BouncyCastle) |
| 压缩 | LZ4 (lz4-java) |
| UI | AndroidX AppCompat + Material Design |

## 模块结构

```
ScrcpyBluetooth/
├── app/         # 控制端 Android 应用（UI + 蓝牙传输 + 文件同步）
├── common/      # 共享库（协议、编解码、加密、工具类）
└── server/      # 被控端进程（屏幕捕获、输入注入、文件处理）
```

### app 模块 (com.scrcpybt.app)

控制端 Android 应用，运行在控制设备上。

```
app/src/main/kotlin/com/scrcpybt/app/
├── service/        # 前台服务
│   ├── ControllerService    # 控制端主服务（连接、帧接收、输入发送）
│   └── RelayService         # 中继端服务（A↔C 数据透明转发）
├── transport/      # 传输层
│   └── bluetooth/           # 蓝牙 RFCOMM 客户端/服务端/管理器
├── sync/           # 文件同步
│   ├── SyncEngine           # 增量同步引擎（块级哈希比较）
│   └── BlockHasher          # SHA-256 块哈希计算
├── ui/             # UI 层
│   ├── MainActivity         # 主界面
│   ├── ControlActivity      # 远程控制界面
│   └── PermissionActivity   # 权限管理面板
└── util/           # 工具类
    ├── NotificationHelper   # 通知管理
    └── PermissionHelper     # 运行时权限优雅降级
```

### common 模块 (com.scrcpybt.common)

控制端和被控端共享的基础库。

```
common/src/main/kotlin/com/scrcpybt/common/
├── protocol/       # 通信协议
│   ├── MessageType          # 消息类型枚举（12 种）
│   ├── MessageHeader        # 消息头（magic + type + flags + length + sequence）
│   ├── ProtocolConstants    # 协议常量
│   ├── message/             # 各类消息的序列化/反序列化
│   └── stream/              # MessageReader / MessageWriter 流式读写
├── codec/          # 视频编解码
│   ├── FrameCodec           # 256 色编码管线主类
│   ├── color/               # 颜色量化（MedianCut）+ 映射（ColorMapper）+ 调色板
│   ├── delta/               # 帧差检测（DeltaEncoder）+ XOR 编码
│   └── compression/         # LZ4 压缩/解压
├── crypto/         # 端到端加密
│   ├── EncryptedChannel     # AES-256-GCM 加密通道
│   ├── KeyExchange          # ECDH P-256 密钥交换
│   ├── KeyDerivation        # HKDF-SHA256 密钥派生
│   └── CryptoConstants      # 加密参数常量
├── transport/      # 传输抽象
│   └── Connection           # 连接接口（蓝牙/USB 统一抽象）
└── util/           # 工具
    ├── Logger               # 日志
    └── ByteBufferPool       # 字节缓冲池
```

### server 模块 (com.scrcpybt.server)

被控端进程，通过 `app_process` 以 Shell 权限运行。

```
server/src/main/kotlin/com/scrcpybt/server/
├── Server.kt                # 服务主入口
├── ServerMain.kt            # JVM 入口点
├── capture/                 # 屏幕捕获
│   ├── ScreenCapture        # VirtualDisplay 屏幕录制
│   └── FrameProducer        # 帧生产者（捕获→编码→队列）
├── input/                   # 输入注入
│   └── InputInjector        # 触摸/按键事件注入（带速率限制）
├── encoder/                 # 帧编码器封装
│   └── FrameEncoder         # 编码管线封装
├── file/                    # 文件操作
│   ├── FileTransferHandler  # 文件传输处理（含路径安全校验）
│   └── FolderSyncHandler    # 文件夹同步处理（含路径安全校验）
├── share/                   # 分享转发
│   └── ShareForwardHandler  # ACTION_SEND 转发（命令注入防护）
├── session/                 # 会话管理
│   └── SessionManager       # 设备认证 + 会话追踪（TOFU 模型）
└── auth/                    # 设备认证
    └── DeviceAuthenticator  # 基于功能粒度的 TOFU 认证
```

## 核心架构

### 三方连接模型

```
┌──────────┐    Bluetooth/USB    ┌──────────┐    Bluetooth/USB    ┌──────────┐
│  控制端 A │ ◄────────────────► │  中继端 B │ ◄────────────────► │  被控端 C │
│  (APP)   │                     │  (APP)   │                     │ (Server) │
└──────────┘                     └──────────┘                     └──────────┘
     直连模式：A ◄──────────────────────────────────────────────► C
```

### 数据流（直连模式）

```
被控端 C:
  ScreenCapture → FrameProducer → FrameCodec.encode() → EncryptedChannel.send()
       ↑                                                        │
  InputInjector ← EncryptedChannel.receive() ← ─ ─ ─ ─ ─ ─ ─ ─┘
                                                    蓝牙 RFCOMM
控制端 A:                                               │
  EncryptedChannel.receive() → FrameCodec.decode() → UI 渲染
       │                                                ↑
  EncryptedChannel.send() ← TouchEvent/KeyEvent ← ─ ─ ─┘
```

### 安全架构

1. **端到端加密**：ECDH P-256 密钥交换 → HKDF 派生 AES-256 密钥 → AES-GCM 加密所有消息
2. **Nonce 安全**：发起方/被发起方使用不同 nonce 基底，计数器单调递增，杜绝 nonce 重用
3. **TOFU 认证**：首次连接时信任设备指纹，后续连接验证一致性
4. **路径安全**：所有文件操作使用 `canonicalFile` 防止路径遍历攻击
5. **输入限速**：令牌桶算法限制输入事件注入速率（200/秒）
6. **消息校验**：载荷长度上限 16MB，字符串字段独立限长

### 视频编码管线

```
ARGB_8888 像素
    ↓ MedianCutQuantizer（每 90 帧重建调色板）
256 色索引帧
    ↓ DeltaEncoder（16×16 块级脏区检测）
XOR 差异数据
    ↓ LZ4Compressor
压缩帧消息 → 网络传输
```

## 性能优化

| 优化项 | 方案 |
|--------|------|
| Bitmap 复用 | `setPixels()` 代替每帧 `createBitmap()` |
| Cipher 复用 | AES-GCM Cipher 实例和 nonce 缓冲区复用 |
| ColorMapper | 预拆分 RGB 分量 + 精确匹配早退 |
| 编码解耦 | FrameProducer 编码异步提交到独立线程 |
| 中继 flush | C→A 视频方向使用 BufferedOutputStream + 条件 flush |
| WakeLock | 25 分钟自动续期，避免 30 分钟超时 |
