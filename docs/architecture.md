# ScrcpyBluetooth 技术架构 | Technical Architecture

## 目录 | Table of Contents

- [概述 | Overview](#概述--overview)
- [技术栈 | Tech Stack](#技术栈--tech-stack)
- [模块结构 | Module Structure](#模块结构--module-structure)
- [三方连接模型 | Three-Party Connection Model](#三方连接模型--three-party-connection-model)
- [通信协议 | Communication Protocol](#通信协议--communication-protocol)
- [安全架构 | Security Architecture](#安全架构--security-architecture)
- [视频编码管线 | Video Encoding Pipeline](#视频编码管线--video-encoding-pipeline)
- [文件夹同步引擎 | Folder Sync Engine](#文件夹同步引擎--folder-sync-engine)
- [传输层抽象 | Transport Abstraction](#传输层抽象--transport-abstraction)
- [线程模型 | Threading Model](#线程模型--threading-model)
- [性能优化 | Performance Optimizations](#性能优化--performance-optimizations)
- [安全审计要点 | Security Audit Notes](#安全审计要点--security-audit-notes)
- [构建与 CI | Build & CI](#构建与-ci--build--ci)

---

## 概述 | Overview

ScrcpyBluetooth 是一个 Android 对 Android 的屏幕镜像和远程控制系统。整体架构采用**三方模型**（Controller A - Relay B - Controlled C），通过蓝牙 RFCOMM 或 USB ADB 隧道传输端到端加密的数据流。

ScrcpyBluetooth is an Android-to-Android screen mirroring and remote control system. The architecture follows a **three-party model** (Controller A - Relay B - Controlled C), transmitting end-to-end encrypted data streams over Bluetooth RFCOMM or USB ADB tunnels.

### 设计原则 | Design Principles

1. **零网络依赖** | Zero network dependency — 蓝牙/USB 直连，不经过任何服务器
2. **安全第一** | Security-first — ECDH + AES-256-GCM 端到端加密，TOFU 设备认证
3. **统一 APK** | Single APK — 控制端、被控端、中继端三种角色共用同一个安装包
4. **低带宽优化** | Bandwidth-efficient — 256 色编码 + 增量帧 + LZ4 压缩
5. **优雅降级** | Graceful degradation — 权限拒绝不崩溃，功能按需可用

---

## 技术栈 | Tech Stack

| 层级 | 技术 | 说明 |
|------|------|------|
| 语言 | Kotlin 1.9.22 | 全项目统一使用 Kotlin |
| 构建系统 | Gradle 8.5 + AGP 8.2.2 | Android Gradle Plugin |
| 最低 SDK | API 28 (Android 9.0) | 虚拟显示器需 API 29+ |
| 目标 SDK | API 34 (Android 14) | |
| JVM 目标 | Java 11 | Kotlin 和 Java 编译目标 |
| 加密库 | BouncyCastle 1.77 | ECDH、AES-GCM 实现 |
| 安全存储 | AndroidX Security-Crypto 1.1.0-alpha06 | EncryptedSharedPreferences |
| UI 框架 | AndroidX AppCompat + Material Design | Material 1.11.0 |
| 架构组件 | AndroidX Lifecycle 2.7.0 | ViewModel + LiveData |
| 压缩 | LZ4 (自实现) | 高速无损压缩 |

---

## 模块结构 | Module Structure

```
ScrcpyBluetooth/
├── app/         # Android 应用模块（所有角色的 UI + 客户端逻辑）
│                # Android application module (UI + client logic for all roles)
├── common/      # 共享库模块（协议、编解码、加密、数据结构）
│                # Shared library module (protocol, codec, crypto, data structures)
├── server/      # 服务端逻辑模块（屏幕捕获、输入注入、文件处理）
│                # Server logic module (screen capture, input injection, file handling)
├── gradle/      # Gradle Wrapper 配置
├── docs/        # 项目文档
└── .github/     # GitHub Actions CI 工作流
```

### app 模块 | App Module (`com.scrcpybt.app`)

控制端 Android 应用，包含所有三种角色的 UI 和客户端业务逻辑。

```
app/src/main/kotlin/com/scrcpybt/app/
├── ScrcpyApplication.kt            # Application 初始化入口
│                                    # Application initialization entry point
├── ui/                              # 用户界面层 | UI Layer
│   ├── MainActivity.kt             # 主界面：角色选择、传输方式选择、权限管理
│   ├── controller/                  # 控制端界面
│   │   ├── ControllerActivity.kt   # 远程控制主界面（显示镜像画面 + 导航按钮）
│   │   ├── DisplayRenderer.kt      # 屏幕渲染器（SurfaceView + Bitmap 绘制）
│   │   └── TouchHandler.kt         # 触摸事件处理（坐标映射 + 手势识别）
│   ├── controlled/                  # 被控端界面
│   │   ├── ControlledActivity.kt   # 被控端设置界面（虚拟显示器、解锁配置等）
│   │   └── ServerLauncher.kt       # Server 进程启动器（通过 ADB Shell）
│   ├── relay/                       # 中继端界面
│   │   └── RelayActivity.kt        # 中继端状态显示和控制
│   ├── clipboard/                   # 剪贴板功能界面
│   │   ├── ClipboardActivity.kt    # 剪贴板传输操作界面
│   │   └── ClipboardTrampolineActivity.kt  # 剪贴板跳板（处理系统级剪贴板访问）
│   ├── filetransfer/                # 文件传输界面
│   │   └── FileTransferActivity.kt # 文件选择和传输进度
│   ├── foldersync/                  # 文件夹同步界面
│   │   └── FolderSyncActivity.kt   # 同步配置（路径、方向、版本控制、电源条件等）
│   ├── share/                       # 分享转发界面
│   │   ├── ShareConfigActivity.kt  # 被控端分享转发配置
│   │   └── ShareReceiverActivity.kt # 控制端分享接收确认
│   ├── history/                     # 历史记录界面
│   │   ├── HistoryActivity.kt      # 操作历史列表（支持类型过滤）
│   │   └── HistoryAdapter.kt       # RecyclerView 适配器
│   ├── shortcut/                    # 快捷方式配置
│   │   └── ShortcutConfigActivity.kt # 桌面快捷方式、文本操作条、分享菜单开关
│   └── textaction/                  # 文本选中操作
│       └── SendTextToRemoteActivity.kt # 文本选中后发送到远端
│
├── service/                         # 前台服务层 | Foreground Service Layer
│   ├── ControllerService.kt        # 控制端主服务
│   │   # - 管理蓝牙/USB 连接生命周期
│   │   # - 接收帧数据并分发给 UI
│   │   # - 发送触摸/按键输入事件
│   │   # - 处理传输切换（蓝牙 ↔ USB）
│   │   # - 心跳维持和 WakeLock 管理
│   └── RelayService.kt             # 中继端服务
│       # - A→C 和 C→A 双向数据透明转发
│       # - A→C 方向使用 BufferedOutputStream + 条件 flush
│       # - C→A 方向保持每次读取后立即 flush（延迟敏感）
│
├── codec/                           # 解码层 | Decoder Layer
│   ├── FrameDecoder.kt             # 帧解码器（256色→ARGB Bitmap）
│   └── DecoderThread.kt            # 解码工作线程
│
├── handler/                         # 业务逻辑处理器 | Business Logic Handlers
│   ├── FileTransferController.kt   # 文件传输控制器（队列管理、分块、断点续传）
│   └── ClipboardTransferHandler.kt # 剪贴板传输处理器
│
├── transport/                       # 传输层实现 | Transport Layer Implementation
│   ├── bluetooth/                   # 蓝牙传输
│   │   ├── BluetoothManager.kt     # 蓝牙设备管理（扫描、配对列表）
│   │   ├── BluetoothConnection.kt  # 蓝牙 RFCOMM 连接封装
│   │   ├── BluetoothRfcommClient.kt # RFCOMM 客户端（控制端用）
│   │   └── BluetoothRfcommServer.kt # RFCOMM 服务端（被控端/中继端用）
│   ├── usb/                         # USB ADB 传输
│   │   ├── AdbConnection.kt        # ADB forward TCP 连接
│   │   ├── AdbForwardManager.kt    # ADB 端口转发管理
│   │   └── UsbDetector.kt          # USB 连接状态检测
│   └── TransportMonitor.kt         # 传输状态监控（心跳超时检测）
│
├── sync/                            # 文件夹同步客户端 | Folder Sync Client
│   ├── SyncEngine.kt               # 增量同步引擎（块级哈希比较 + 差异传输）
│   ├── SyncService.kt              # 同步前台服务（定时触发 + 条件检查）
│   ├── SyncDiff.kt                 # 文件差异计算（新增/修改/删除）
│   ├── SyncDatabase.kt             # 同步状态数据库（SQLite）
│   ├── FileVersioner.kt            # 文件版本控制（Trashcan/Simple/Staggered）
│   ├── FolderWatcher.kt            # 文件系统监视器（FileObserver）
│   └── PowerConditionChecker.kt    # 电源条件检查（充电状态、电量、省电模式）
│
├── auth/                            # 客户端认证 | Client Authentication
│   ├── FeatureAuthClient.kt        # 逐功能认证客户端（向被控端请求授权）
│   └── DeviceTrustStore.kt         # 设备信任存储（EncryptedSharedPreferences）
│
├── transfer/                        # 文件传输状态管理 | Transfer State Management
│   ├── TransferState.kt            # 传输状态数据类（持久化用）
│   ├── TransferStateDatabase.kt    # 传输状态数据库（支持断点续传）
│   └── TransferSettings.kt         # 传输配置（块大小、检查点间隔等）
│
├── history/                         # 历史记录 | History
│   ├── HistoryEntry.kt             # 历史条目数据类
│   └── HistoryDatabase.kt          # 历史记录 SQLite 数据库
│
├── util/                            # 工具类 | Utilities
│   ├── PermissionHelper.kt         # 运行时权限管理（优雅降级策略）
│   ├── NotificationHelper.kt       # 通知渠道和前台服务通知管理
│   ├── ShortcutHelper.kt           # 动态快捷方式管理
│   └── RootChecker.kt              # ROOT 权限检测
│
└── receiver/                        # 广播接收器 | Broadcast Receivers
    └── DisconnectReceiver.kt       # 通知栏断开连接按钮处理
```

### common 模块 | Common Module (`com.scrcpybt.common`)

控制端和被控端共享的基础库，不依赖任何 Android 特有 API。

```
common/src/main/kotlin/com/scrcpybt/common/
├── protocol/                        # 通信协议定义 | Protocol Definition
│   ├── ProtocolConstants.kt        # 协议全局常量
│   │   # PROTOCOL_VERSION = 1, MAGIC = 0x53425401 ("SBT\x01")
│   │   # MAX_PAYLOAD_SIZE = 16MB, MAX_STRING_LENGTH 系列
│   ├── MessageType.kt              # 消息类型枚举（12 种）
│   │   # HANDSHAKE, KEY_EXCHANGE, AUTH, FRAME, INPUT, CONTROL,
│   │   # HEARTBEAT, CLIPBOARD, FILE_TRANSFER, FOLDER_SYNC,
│   │   # SHARE_FORWARD, TRANSPORT_SWITCH
│   ├── MessageHeader.kt            # 14 字节消息头
│   │   # [magic:4B][type:1B][flags:1B][length:4B][sequence:4B]
│   ├── stream/
│   │   ├── MessageReader.kt        # 消息反序列化（流式读取，处理 TCP 分片）
│   │   └── MessageWriter.kt        # 消息序列化（线程安全，synchronized）
│   └── message/                     # 消息类型定义（每种消息的序列化/反序列化）
│       ├── Message.kt              # 抽象基类
│       ├── MessageCodec.kt         # 统一编解码入口（工厂方法模式）
│       ├── HandshakeMessage.kt     # 握手：设备名称、系统版本、屏幕尺寸、能力位
│       ├── KeyExchangeMessage.kt   # 密钥交换：ECDH 公钥 + 随机盐
│       ├── AuthMessage.kt          # 认证：TOFU 设备指纹 + 逐功能授权请求/响应
│       ├── FrameMessage.kt         # 帧数据：宽高(Short)、帧类型、调色板、像素数据
│       ├── InputMessage.kt         # 输入事件：触摸坐标、按键码、动作类型
│       ├── ControlMessage.kt       # 控制命令：返回、Home、最近任务、息屏等
│       ├── HeartbeatMessage.kt     # 心跳：时间戳 + 往返延迟计算
│       ├── ClipboardMessage.kt     # 剪贴板：文本内容（限长 1MB）
│       ├── FileTransferMessage.kt  # 文件传输：BEGIN/CHUNK/END/RESUME/ERROR
│       ├── FolderSyncMessage.kt    # 文件夹同步：文件列表/块哈希/数据块/完成确认
│       ├── ShareForwardMessage.kt  # 分享转发：MIME 类型 + 分块数据
│       └── TransportSwitchMessage.kt # 传输切换：REQUEST/READY/VERIFY/CONFIRMED/COMPLETE
│
├── codec/                           # 视频编解码 | Video Codec
│   ├── FrameCodec.kt              # 编解码主流程（encode/decode 入口）
│   ├── color/                      # 颜色处理
│   │   ├── ColorQuantizer.kt      # 量化器接口
│   │   ├── MedianCutQuantizer.kt  # 中位切割算法（ARGB → 256 色调色板）
│   │   ├── ColorMapper.kt         # 调色板映射（15-bit LUT 加速 + 精确匹配早退）
│   │   └── Palette.kt             # 256 色调色板数据结构
│   ├── delta/                      # 帧差编码
│   │   ├── DeltaEncoder.kt        # XOR 增量编码 + 16×16 块级脏区检测
│   │   ├── DeltaDecoder.kt        # XOR 增量解码（还原完整帧）
│   │   └── DirtyRegion.kt         # 脏区位图数据结构
│   └── compression/                # 压缩
│       ├── Compressor.kt          # 压缩器接口
│       └── LZ4Compressor.kt       # LZ4 快速压缩/解压实现
│
├── crypto/                          # 端到端加密 | End-to-End Encryption
│   ├── CryptoConstants.kt         # 加密参数常量
│   │   # AES_KEY_SIZE=256, GCM_NONCE_SIZE=12, GCM_TAG_SIZE=128
│   │   # ECDH_CURVE="secp256r1", SALT_SIZE=32
│   ├── KeyExchange.kt             # ECDH P-256 密钥交换（Diffie-Hellman）
│   ├── KeyDerivation.kt           # HKDF-SHA256 密钥派生
│   ├── EncryptedChannel.kt        # AES-256-GCM 加密通道
│   │   # 功能：消息级加解密、nonce 管理、Cipher 复用
│   │   # Nonce 结构：[base:4B][counter:8B]（发起方/被发起方不同基底）
│   │   # 安全特性：nonce 单调递增、Cipher 实例复用减少 GC
│   └── SecureRandomWrapper.kt     # 安全随机数生成器封装
│
├── transport/                       # 传输层抽象 | Transport Abstraction
│   ├── Connection.kt              # 连接接口（getInputStream/getOutputStream/close）
│   └── TransportType.kt           # 传输类型枚举（BLUETOOTH_RFCOMM, USB_ADB）
│
├── session/                         # 会话管理 | Session Management
│   └── SessionManager.kt          # 会话 ID、加密状态保存、传输切换状态迁移
│
├── sync/                            # 文件夹同步数据结构 | Sync Data Structures
│   ├── SyncConfig.kt              # 同步配置（路径、方向、版本控制、电源策略）
│   ├── FileInfo.kt                # 文件元信息（路径、大小、修改时间、块哈希列表）
│   ├── BlockInfo.kt               # 数据块信息（偏移、大小、SHA-256 哈希）
│   ├── SyncState.kt               # 同步状态快照
│   └── IgnorePattern.kt           # .syncignore 规则解析（Glob 模式匹配）
│
└── util/                            # 工具类 | Utilities
    ├── Logger.kt                   # 统一日志（自动检测 Android/JVM 环境）
    └── ByteBufferPool.kt          # 字节缓冲区对象池（减少 GC 压力）
```

### server 模块 | Server Module (`com.scrcpybt.server`)

被控端核心逻辑，作为 app 模块的库依赖打包进 APK。

```
server/src/main/kotlin/com/scrcpybt/server/
├── Server.kt                       # 服务端核心逻辑
│   # - 连接建立（握手、密钥交换、认证）
│   # - 主消息循环（接收输入/控制/文件/同步等消息并分发处理）
│   # - 帧发送线程（FrameProducer → 编码 → 加密 → 发送）
│   # - 传输切换（蓝牙 ↔ USB）
│   # - 优雅关闭（shutdown + awaitTermination + fallback shutdownNow）
│
├── ServerMain.kt                    # JVM 入口点（app_process 启动入口）
│
├── capture/                         # 屏幕捕获层 | Screen Capture Layer
│   ├── ScreenCapture.kt           # 屏幕捕获抽象接口
│   ├── SurfaceControlCapture.kt   # SurfaceControl API 屏幕录制（Hidden API）
│   ├── FrameProducer.kt           # 帧生产者（捕获回调 → 异步编码 → 消息队列）
│   │   # 编码解耦：pixels.copyOf() + encoderExecutor.submit()
│   └── DisplayInfo.kt             # 屏幕信息（宽高、密度、旋转方向）
│
├── encoder/                         # 帧编码层 | Frame Encoding Layer
│   ├── FrameEncoder.kt            # 编码管线封装（FrameCodec 适配器）
│   ├── FrameCodec.kt              # 256 色编码核心（已在 common 模块）
│   ├── ColorMapper.kt             # 颜色映射器封装
│   └── MedianCutQuantizer.kt      # 中位切割量化器封装
│
├── input/                           # 输入注入层 | Input Injection Layer
│   ├── InputInjector.kt           # 触摸/按键事件注入
│   │   # - 通过 InputManager Hidden API 注入事件
│   │   # - 令牌桶限速（200 事件/秒，峰值 50）
│   │   # - 坐标映射（控制端屏幕坐标 → 被控端物理坐标）
│   └── EventConverter.kt          # 事件格式转换（InputMessage → MotionEvent/KeyEvent）
│
├── control/                         # 设备控制层 | Device Control Layer
│   ├── ControlHandler.kt          # 控制命令处理器（返回、Home、最近任务、截图等）
│   ├── PowerControl.kt            # 电源控制（息屏、亮屏）
│   └── ScreenUnlocker.kt          # 屏幕解锁器（PIN/密码/图案，通过 Shell 命令）
│
├── display/                         # 显示管理层 | Display Management Layer
│   ├── VirtualDisplayManager.kt   # 虚拟显示器管理（创建/销毁/切换）
│   ├── VirtualDisplayLauncher.kt  # 虚拟显示器上的应用启动器
│   ├── WakeLockManager.kt         # 唤醒锁管理（25 分钟自动续期，避免 30 分钟超时）
│   └── SecureFlagBypass.kt        # FLAG_SECURE 绕过（通过 SurfaceControl Hidden API）
│
├── transport/                       # 服务端传输层 | Server Transport Layer
│   ├── ServerTransport.kt         # 传输抽象接口
│   ├── BluetoothServerTransport.kt # 蓝牙 RFCOMM 服务端传输
│   └── UnixSocketServerTransport.kt # Unix Domain Socket 传输（ADB 模式）
│
├── file/                            # 文件操作层 | File Operation Layer
│   ├── FileTransferHandler.kt     # 文件传输处理器
│   │   # - 路径安全校验（canonicalPath 白名单）
│   │   # - 分块读写 + 进度回报
│   └── FolderSyncHandler.kt       # 文件夹同步处理器
│       # - 文件列表扫描 + 块级哈希计算
│       # - 差异块传输 + 增量写入
│       # - 路径遍历防护
│
├── share/                           # 分享转发 | Share Forwarding
│   └── ShareForwardHandler.kt     # ACTION_SEND Intent 转发
│       # - 命令注入防护（ComponentName 白名单校验）
│       # - 分块数据重组
│
├── clipboard/                       # 剪贴板 | Clipboard
│   └── ClipboardHandler.kt        # 系统剪贴板读写（通过 Hidden API）
│
├── auth/                            # 服务端认证 | Server Authentication
│   └── AuthHandler.kt             # TOFU 设备认证 + 逐功能授权
│
└── util/                            # 工具类 | Utilities
    └── HiddenApiBypass.kt         # Android Hidden API 反射访问封装
```

---

## 三方连接模型 | Three-Party Connection Model

### 直连模式数据流 | Direct Mode Data Flow

```
被控端 C (Server):
  ┌─────────────┐    ┌──────────────┐    ┌──────────┐    ┌───────────────┐
  │ScreenCapture│───►│ FrameProducer│───►│FrameCodec│───►│EncryptedChannel│──┐
  └─────────────┘    └──────────────┘    │ .encode() │    │    .send()     │  │
                                         └──────────┘    └───────────────┘  │
  ┌─────────────┐    ┌──────────────┐                                       │
  │InputInjector│◄───│EncryptedChannel│◄─── Bluetooth RFCOMM / USB ADB ◄───┘
  └─────────────┘    │  .receive()   │                        │
                     └──────────────┘                        │
                                                              │
控制端 A (App):                                                │
  ┌───────────────┐    ┌──────────┐    ┌───────────────┐     │
  │EncryptedChannel│───►│FrameCodec│───►│DisplayRenderer│     │
  │   .receive()   │    │ .decode() │    │  (SurfaceView)│     │
  └───────────────┘    └──────────┘    └───────────────┘     │
                                                              │
  ┌───────────────┐    ┌────────────┐                        │
  │EncryptedChannel│◄───│TouchHandler│◄─── 用户触摸/按键 ────┘
  │    .send()     │    └────────────┘
  └───────────────┘
```

### 中继模式数据流 | Relay Mode Data Flow

```
控制端 A                 中继端 B                  被控端 C
┌──────┐  Bluetooth  ┌──────────┐  Bluetooth  ┌──────┐
│      │◄───────────►│RelayService│◄───────────►│      │
│ App  │   密文数据   │  A↔C透传  │   密文数据   │Server│
│      │             │ (不解密)   │             │      │
└──────┘             └──────────┘             └──────┘

中继端 B 的 RelayService 实现：
  - forwardAtoC(): 从 A 读取 → 写入 C（每次 flush，延迟敏感）
  - forwardCtoA(): 从 C 读取 → BufferedOutputStream 写入 A
                   （available() == 0 时才 flush，带宽优化）
```

### 传输切换流程 | Transport Switch Protocol

```
Controller A                          Server C
    │                                     │
    │ ── SWITCH_REQUEST (sessionId) ────► │
    │                                     │ 准备新传输通道
    │ ◄── SWITCH_READY ───────────────── │
    │                                     │
    │ ═══ 建立新连接（蓝牙/USB） ═══════ │
    │                                     │
    │ ── SWITCH_VERIFY (sessionId) ─────► │ 验证会话 ID
    │ ◄── SWITCH_CONFIRMED ────────────── │
    │                                     │
    │ ── SWITCH_COMPLETE ───────────────► │
    │                                     │
    │ ═══ 切换到新连接，旧连接关闭 ═════ │
    │     加密状态迁移（密钥+nonce 连续） │
```

---

## 通信协议 | Communication Protocol

### 消息帧格式 | Message Frame Format

```
┌──────────┬──────┬───────┬────────┬──────────┬─────────┐
│  Magic   │ Type │ Flags │ Length │ Sequence │ Payload │
│  4 bytes │ 1 B  │ 1 B   │ 4 B    │ 4 B      │ N bytes │
│ "SBT\01" │      │       │        │          │         │
└──────────┴──────┴───────┴────────┴──────────┴─────────┘
     ▲                        ▲         ▲
     │                        │         │
  协议标识                  载荷长度    序列号
  Protocol Magic          Payload Len  Sequence#
```

**总消息头大小**：14 字节

### 消息类型 | Message Types

| 类型 ID | 名称 | 方向 | 说明 |
|---------|------|------|------|
| 0x01 | HANDSHAKE | 双向 | 设备信息交换（名称、版本、屏幕尺寸、能力位） |
| 0x02 | KEY_EXCHANGE | 双向 | ECDH 公钥 + 随机盐交换 |
| 0x03 | AUTH | 双向 | TOFU 设备认证 + 逐功能授权请求/响应 |
| 0x10 | FRAME | C→A | 帧数据（关键帧/增量帧/调色板更新） |
| 0x11 | INPUT | A→C | 触摸/按键输入事件 |
| 0x12 | CONTROL | A→C | 设备控制命令（返回、Home、息屏等） |
| 0x20 | HEARTBEAT | 双向 | 心跳保活 + RTT 计算 |
| 0x30 | CLIPBOARD | 双向 | 剪贴板文本传输 |
| 0x31 | FILE_TRANSFER | 双向 | 文件分块传输（BEGIN/CHUNK/END/RESUME/ERROR） |
| 0x32 | FOLDER_SYNC | 双向 | 文件夹增量同步（文件列表/块哈希/数据块） |
| 0x33 | SHARE_FORWARD | A→C | 分享内容转发（MIME + 分块数据） |
| 0x40 | TRANSPORT_SWITCH | 双向 | 传输切换协商（5 步握手） |

### 连接建立流程 | Connection Establishment

```
1. TCP/RFCOMM 连接建立
2. 双方交换 HANDSHAKE 消息（明文）
3. 双方交换 KEY_EXCHANGE 消息（ECDH 公钥 + 随机盐）
4. 双方独立计算共享密钥：ECDH(myPriv, peerPub)
5. HKDF 派生 AES-256 密钥 + Nonce 基底
6. 建立 EncryptedChannel（后续所有消息均加密）
7. AUTH 消息交换（TOFU 设备指纹验证 + 功能授权）
8. 开始正常通信（FRAME, INPUT, CONTROL 等）
```

---

## 安全架构 | Security Architecture

### 加密方案 | Encryption Scheme

```
密钥交换 (Key Exchange):
  A: (privA, pubA) = ECDH.generateKeyPair(secp256r1)
  C: (privC, pubC) = ECDH.generateKeyPair(secp256r1)
  交换公钥后:
  sharedSecret = ECDH(privA, pubC) = ECDH(privC, pubA)

密钥派生 (Key Derivation):
  combinedSalt = clientSalt(32B) || serverSalt(32B)
  aesKey = HKDF-SHA256(sharedSecret, combinedSalt, "scrcpybt-aes-key", 32)
  nonceMaterial = HKDF-SHA256(sharedSecret, combinedSalt, "scrcpybt-nonce", 8)
  sendNonceBase = nonceMaterial[0:4]  (发起方)
  recvNonceBase = nonceMaterial[4:8]  (被发起方)

消息加密 (Message Encryption):
  nonce = sendNonceBase(4B) || sendCounter(8B, big-endian)
  ciphertext = AES-256-GCM.encrypt(aesKey, nonce, plaintext)
  sendCounter++  // 单调递增，杜绝 nonce 重用
```

### Nonce 安全设计 | Nonce Safety Design

| 属性 | 说明 |
|------|------|
| Nonce 大小 | 12 字节 = 4B base + 8B counter |
| 基底分离 | 发起方和被发起方使用不同的 nonce 基底 |
| 单调递增 | 计数器使用 AtomicLong，永远只增不减 |
| 无碰撞 | 不同方向的基底不同，同方向的计数器不同 → 全局唯一 |
| 传输切换 | 切换传输时保持 nonce 计数器连续递增（不归零） |

### TOFU 认证 | Trust On First Use

```
首次连接:
  1. 被控端生成设备指纹 = SHA-256(ECDH 公钥)
  2. 控制端提示用户确认设备指纹
  3. 双方将对方指纹存入信任库（EncryptedSharedPreferences）

后续连接:
  1. 交换设备指纹
  2. 与信任库中的指纹比对
  3. 匹配则自动信任，不匹配则警告用户（可能的中间人攻击）

逐功能授权:
  - 每个功能（屏幕镜像、剪贴板、文件传输等）独立授权
  - 被控端可以拒绝特定功能的访问请求
  - 授权结果绑定设备指纹，持久化存储
```

### 路径安全 | Path Safety

所有文件操作（文件传输、文件夹同步）均使用 `File.canonicalFile` 进行路径安全校验：

```kotlin
// 防止路径遍历攻击 | Prevent path traversal attacks
val canonicalFile = File(basePath, userPath).canonicalFile
if (!canonicalFile.path.startsWith(basePath.canonicalPath)) {
    throw SecurityException("Path traversal detected: $userPath")
}
```

---

## 视频编码管线 | Video Encoding Pipeline

### 编码流程 | Encoding Pipeline

```
ScreenCapture (ARGB_8888 像素缓冲区)
    │
    ▼ pixels.copyOf() — 解耦捕获和编码
FrameProducer (encoderExecutor 异步提交)
    │
    ▼
FrameCodec.encode()
    │
    ├─── 调色板检查 ───────────────────────────────────┐
    │    每 90 帧或颜色失真过大时重建                    │
    │                                                   ▼
    │                              MedianCutQuantizer.quantize()
    │                              (递归切割 RGB 颜色空间)
    │                                                   │
    │    ◄───────────── 新 Palette ────────────────────┘
    │
    ├─── 颜色映射 ──────────────────────────────────────┐
    │    ARGB → 256 色索引                               │
    │                                                   ▼
    │                              ColorMapper.mapPixels()
    │                              (15-bit LUT + 精确匹配早退)
    │                                                   │
    │    ◄───────────── indexedFrame[byte] ─────────────┘
    │
    ├─── 帧差检测 ──────────────────────────────────────┐
    │    与上一帧比较                                     │
    │                                                   ▼
    │                              DeltaEncoder.encode()
    │                              (16×16 块级脏区比较)
    │                              if 脏区 >60%: 关键帧
    │                                                   │
    │    ◄───────────── XOR 差异数据 + DirtyRegion ────┘
    │
    ├─── 压缩 ──────────────────────────────────────────┐
    │                                                   ▼
    │                              LZ4Compressor.compress()
    │                              (高速无损压缩)
    │                                                   │
    │    ◄───────────── 压缩后的帧消息 ────────────────┘
    │
    ▼
FrameMessage → EncryptedChannel.send() → 网络传输
```

### 帧类型 | Frame Types

| 类型 | 触发条件 | 内容 |
|------|---------|------|
| 关键帧 | 每 150 帧 / 脏区>60% / 首帧 | 完整 256 色索引帧 + 调色板 |
| 增量帧 | 画面有变化 | XOR 差异数据 + 脏区位图 |
| 调色板帧 | 每 90 帧 | 仅调色板数据（256×3 字节 RGB） |
| 空帧 | 画面无变化 | 不发送（零开销） |

### 解码流程 | Decoding Pipeline

```
FrameMessage ← EncryptedChannel.receive()
    │
    ▼
FrameCodec.decode()
    │
    ├─── 关键帧: 直接解码 256 色索引 → ARGB
    ├─── 增量帧: XOR 还原 → 合并到上一帧 → ARGB
    └─── 调色板帧: 更新调色板
    │
    ▼
FrameDecoder (msg.width.toInt(), msg.height.toInt())
    │
    ▼
Bitmap.setPixels() → SurfaceView 渲染
(复用 Bitmap 实例，避免每帧 createBitmap)
```

---

## 文件夹同步引擎 | Folder Sync Engine

### 增量同步算法 | Incremental Sync Algorithm

```
Phase 1: 文件列表交换 | File List Exchange
  ┌──────────┐                    ┌──────────┐
  │ Controller│  FILE_LIST_REQUEST │  Server  │
  │  (SyncEngine)  ──────────────►│(FolderSync│
  │          │                    │ Handler) │
  │          │  FILE_LIST_RESPONSE│          │
  │          │◄──────────────────│          │
  └──────────┘                    └──────────┘

Phase 2: 差异计算 | Diff Calculation (SyncDiff)
  对比本地/远端文件列表:
  - 新增文件: 远端有、本地无 → 需要下载
  - 修改文件: 时间戳/大小不同 → 需要块级比较
  - 删除文件: 本地有、远端无 → 根据配置决定是否删除

Phase 3: 块级哈希比较 | Block-Level Hash Comparison
  对于修改的文件:
  ┌─────────────────────────────────────────┐
  │  文件 A (128KB 块)                       │
  │  [Block 0][Block 1][Block 2]...[Block N]│
  │   SHA-256   SHA-256   SHA-256    SHA-256 │
  └─────────────────────────────────────────┘
  比较每个块的 SHA-256 哈希 → 仅传输不同的块

Phase 4: 差异传输 | Delta Transfer
  BLOCK_REQUEST → BLOCK_DATA (仅变化的块)
  └── BufferedOutputStream(65536) 高效写入
  └── RandomAccessFile.seek() 定位增量写入

Phase 5: 确认 | Confirmation
  SYNC_COMPLETE → 更新本地数据库
```

### 同步策略 | Sync Policies

| 策略 | 说明 |
|------|------|
| Send Only | 本地变化推送到远端，不拉取远端变化 |
| Receive Only | 拉取远端变化，不推送本地变化 |
| Send & Receive | 双向同步（冲突时以时间戳较新者为准） |
| .syncignore | Glob 模式排除规则（类似 .gitignore） |
| 文件版本控制 | 删除/覆盖的文件可保留历史版本 |
| 电源条件 | 可配置仅充电时同步、最低电量门槛等 |

---

## 传输层抽象 | Transport Abstraction

### 接口设计 | Interface Design

```kotlin
interface Connection {
    fun getInputStream(): InputStream
    fun getOutputStream(): OutputStream
    fun close()
    fun isConnected(): Boolean
}
```

### 实现 | Implementations

| 实现类 | 传输方式 | 特点 |
|--------|---------|------|
| `BluetoothConnection` | Bluetooth RFCOMM | 无线，约 3Mbps 带宽 |
| `AdbConnection` | USB ADB Forward | 有线，高带宽低延迟 |
| `UnixSocketServerTransport` | Unix Domain Socket | 本地进程间通信 |

---

## 线程模型 | Threading Model

### 控制端 (ControllerService) | Controller

| 线程 | 职责 |
|------|------|
| 主线程 | UI 渲染、用户交互 |
| Service 线程 | 连接管理、消息调度 |
| 接收线程 | `EncryptedChannel.receive()` 阻塞读取 |
| 解码线程 | `FrameCodec.decode()` + Bitmap 渲染 |
| 心跳线程 | 定时发送心跳消息 |

### 被控端 (Server) | Server

| 线程 | 职责 |
|------|------|
| 主线程 | 消息接收和分发 |
| 捕获线程 | ScreenCapture 回调 |
| 编码线程 | `encoderExecutor` — 异步编码 + 发送 |
| 输入线程 | InputInjector 事件注入 |
| WakeLock 线程 | 25 分钟定时续期 |

### 中继端 (RelayService) | Relay

| 线程 | 职责 |
|------|------|
| forwardAtoC 线程 | A→C 数据转发（每次 flush，延迟优先） |
| forwardCtoA 线程 | C→A 数据转发（BufferedOutputStream + 条件 flush，带宽优先） |

---

## 性能优化 | Performance Optimizations

| 优化项 | 方案 | 效果 |
|--------|------|------|
| Bitmap 复用 | `setPixels()` 代替每帧 `createBitmap()` | 减少 GC 压力，降低内存分配 |
| Cipher 复用 | AES-GCM Cipher 实例和 nonce buffer 复用 | 减少对象创建开销 |
| ColorMapper 加速 | 15-bit RGB LUT (32KB) + 精确匹配早退 | O(1) 颜色查找 |
| 编码解耦 | FrameProducer 捕获与编码在不同线程 | 捕获帧率不受编码延迟影响 |
| 中继 flush 优化 | C→A 方向 BufferedOutputStream + `available()==0` 条件 flush | 减少系统调用，提高吞吐量 |
| WakeLock 续期 | 25 分钟自动续期（系统 30 分钟超时） | 避免意外释放导致中断 |
| ByteBufferPool | 对象池复用字节缓冲区 | 减少频繁的 ByteArray 分配 |
| 增量帧 | 16×16 块级脏区检测，未变化区域零开销 | 静态画面几乎不消耗带宽 |
| LZ4 压缩 | 高速实时压缩（压缩比约 2:1-4:1） | 显著减少传输数据量 |
| 文件同步 I/O | BufferedOutputStream(65536) + RandomAccessFile seek | 减少磁盘 I/O 系统调用 |
| 优雅关闭 | shutdown() + awaitTermination(5s) + shutdownNow() | 避免任务丢失和资源泄漏 |

---

## 安全审计要点 | Security Audit Notes

### 已实施的安全措施 | Implemented Security Measures

| 类别 | 措施 | 文件 |
|------|------|------|
| 加密通信 | AES-256-GCM + ECDH P-256 | `EncryptedChannel.kt` |
| Nonce 安全 | 单调递增计数器 + 方向分离基底 | `EncryptedChannel.kt` |
| 设备认证 | TOFU + 逐功能授权 | `AuthHandler.kt` |
| 路径遍历防护 | canonicalFile 校验 | `FileTransferHandler.kt`, `FolderSyncHandler.kt` |
| 输入限速 | 令牌桶 200/s | `InputInjector.kt` |
| 消息长度限制 | MAX_PAYLOAD=16MB, 字段独立限长 | `ProtocolConstants.kt` |
| 命令注入防护 | ComponentName 白名单 | `ShareForwardHandler.kt` |
| 凭据安全存储 | EncryptedSharedPreferences | `DeviceTrustStore.kt` |
| 权限降级 | 拒绝权限不崩溃 | `PermissionHelper.kt` |

---

## 构建与 CI | Build & CI

### Gradle 模块依赖 | Module Dependencies

```
app ──► common
app ──► server ──► common
```

### GitHub Actions CI

**工作流文件**：`.github/workflows/build.yml`

| 步骤 | 工具 |
|------|------|
| Java | Temurin JDK 17 |
| Android SDK | `android-actions/setup-android@v3` |
| Gradle | `gradle/actions/setup-gradle@v4` (8.5) |
| 构建 | `gradle assembleDebug` |
| 产出 | `app-debug.apk` (上传为 Artifact) |

**触发条件**：`*.kt`, `*.kts`, `*.gradle*`, `gradle/**` 文件变更时触发

**并发控制**：同一分支的运行自动取消之前的 in-progress 运行

---

*最后更新 | Last Updated: 2026-03-20*
