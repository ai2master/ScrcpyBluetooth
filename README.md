# ScrcpyBluetooth

Android 对 Android 屏幕镜像与远程控制应用，通过蓝牙 RFCOMM 或 USB ADB 进行通信。

## 项目概述

ScrcpyBluetooth 实现了在两台 Android 设备之间进行屏幕镜像和远程控制。与 scrcpy 需要电脑作为控制端不同，本项目允许直接用一台 Android 手机控制另一台 Android 手机。

**核心设计特点：**

- **256 色调色板编码**：放弃传统 H.264 视频编码，采用 256 色调色板 + XOR 帧差 + LZ4 压缩方案，保持原始分辨率的同时极大降低带宽需求
- **纯蓝牙/USB 通信**：不使用任何 IP 层网络连接，仅通过蓝牙 RFCOMM 或 USB ADB 转发进行通信
- **三角色架构**：单 APK 包含控制端 (A)、中继端 (B)、被控端 (C) 三种角色
- **虚拟显示模式**：类似 Windows 远程桌面的隐私保护，远程操作在独立虚拟屏幕上进行，物理屏幕保持锁定
- **端到端加密**：ECDH P-256 密钥交换 + HKDF-SHA256 密钥派生 + AES-256-GCM 加密

## 系统要求

| 角色 | 要求 |
|------|------|
| 控制端 (A) | Android 9+ (API 28)，无需 Root |
| 中继端 (B) | Android 9+ (API 28)，无需 Root |
| 被控端 (C) | Android 9+ (API 28)，**需要 Root** |

虚拟显示模式需要 Android 10+ (API 29)。

## 功能一览

### 屏幕镜像与远程控制
- 256 色原始分辨率屏幕传输
- 触摸事件双向传递（支持多点触控）
- 物理按键远程控制（返回、主页、最近任务、音量等）
- 虚拟显示模式（Android 10+，物理屏幕保持锁定）
- 镜像模式回退（Android 9，需强制解锁）
- 运行时虚拟显示/镜像模式切换

### 数据传输
- 剪贴板双向传递
- 文件/文件夹传输（支持断点续传）
- 分享转发（控制端 ACTION_SEND 转发到被控端指定应用）
- 文件夹同步（类 Syncthing 的块级增量同步）

### 安全与会话
- ECDH + AES-256-GCM 端到端加密
- 蓝牙 SSP 链路层加密
- 按功能粒度的 TOFU 设备认证
- 会话管理与恢复
- 蓝牙/USB 无缝传输切换

### 文件夹同步（高级）
- 块级增量同步（128KB 分块 + SHA-256 哈希比对）
- 5 种文件版本控制策略（回收站、简单、交错、外部命令）
- .syncignore 忽略规则
- 电源条件控制（充电状态、电量阈值、省电模式、时间窗口）
- 文件系统监视器（FileObserver）
- 冲突自动检测与解决

## 项目结构

```
ScrcpyBluetooth/
├── settings.gradle.kts          # Gradle 模块配置
├── build.gradle.kts             # 根构建脚本 (AGP 8.2.2, Kotlin 1.9.22)
├── gradle.properties            # Gradle 属性
│
├── common/                      # 共享模块 (纯 Kotlin/JVM)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/scrcpybt/common/
│       ├── protocol/            # 二进制通信协议
│       │   ├── MessageType.kt       # 消息类型枚举 (12 种)
│       │   ├── ProtocolConstants.kt # 协议常量 (魔数、版本、UUID)
│       │   ├── MessageHeader.kt     # 14 字节消息头
│       │   ├── message/             # 各类消息定义
│       │   │   ├── Message.kt           # 消息抽象基类
│       │   │   ├── MessageCodec.kt      # 序列化/反序列化
│       │   │   ├── HandshakeMessage.kt  # 握手
│       │   │   ├── KeyExchangeMessage.kt# ECDH 公钥交换
│       │   │   ├── FrameMessage.kt      # 帧数据
│       │   │   ├── InputMessage.kt      # 触摸/按键输入
│       │   │   ├── ControlMessage.kt    # 设备控制命令
│       │   │   ├── HeartbeatMessage.kt  # 心跳
│       │   │   ├── ClipboardMessage.kt  # 剪贴板
│       │   │   ├── FileTransferMessage.kt# 文件传输
│       │   │   ├── FolderSyncMessage.kt # 文件夹同步
│       │   │   ├── ShareForwardMessage.kt# 分享转发
│       │   │   ├── AuthMessage.kt       # 认证
│       │   │   └── TransportSwitchMessage.kt# 传输切换
│       │   └── stream/              # 流式读写
│       │       ├── MessageReader.kt     # 带缓冲的消息读取
│       │       └── MessageWriter.kt     # 带缓冲的消息写入
│       ├── crypto/              # 加密层
│       │   ├── CryptoConstants.kt   # 加密常量
│       │   ├── KeyExchange.kt       # ECDH P-256 密钥交换
│       │   ├── KeyDerivation.kt     # HKDF-SHA256 密钥派生
│       │   ├── EncryptedChannel.kt  # AES-256-GCM 加密通道
│       │   └── SecureRandomWrapper.kt# 安全随机数
│       ├── codec/               # 256 色编解码
│       │   ├── FrameCodec.kt        # 编解码编排 (高层接口)
│       │   ├── color/               # 颜色量化
│       │   │   ├── ColorQuantizer.kt    # 量化器接口
│       │   │   ├── MedianCutQuantizer.kt# 中位切割算法
│       │   │   ├── Palette.kt           # 256 色调色板
│       │   │   └── ColorMapper.kt       # RGB↔索引映射 (15-bit LUT)
│       │   ├── delta/               # 帧差编码
│       │   │   ├── DeltaEncoder.kt      # XOR 帧差 + 16×16 块脏区检测
│       │   │   ├── DeltaDecoder.kt      # 帧差解码
│       │   │   └── DirtyRegion.kt       # 脏区矩形列表
│       │   └── compression/         # 压缩
│       │       ├── Compressor.kt        # 压缩器接口
│       │       └── LZ4Compressor.kt     # LZ4 快速压缩
│       ├── transport/           # 传输抽象
│       │   ├── Connection.kt        # 连接接口
│       │   └── TransportType.kt     # 传输类型枚举
│       ├── session/             # 会话管理
│       │   └── SessionManager.kt    # 加密会话状态管理
│       ├── sync/                # 同步协议
│       │   ├── SyncConfig.kt        # 同步配置
│       │   ├── SyncState.kt         # 同步状态
│       │   ├── FileInfo.kt          # 文件元数据
│       │   ├── BlockInfo.kt         # 块级信息
│       │   └── IgnorePattern.kt     # .syncignore 规则
│       └── util/                # 工具
│           ├── ByteBufferPool.kt    # 缓冲池
│           └── Logger.kt           # 日志
│
├── server/                      # 服务端模块 (通过 app_process 运行)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/scrcpybt/server/
│       ├── ServerMain.kt           # 入口 main(), 命令行参数解析
│       ├── Server.kt               # 主循环 (连接→握手→密钥交换→捕获+编码+发送)
│       ├── capture/             # 屏幕捕获
│       │   ├── ScreenCapture.kt     # 捕获接口
│       │   ├── SurfaceControlCapture.kt # 双模式捕获 (镜像/虚拟显示)
│       │   ├── DisplayInfo.kt       # 屏幕信息查询 (反射 API)
│       │   └── FrameProducer.kt     # 帧生产者 (限速、队列)
│       ├── encoder/             # 编码
│       │   └── FrameEncoder.kt      # 服务端帧编码器
│       ├── input/               # 输入注入
│       │   ├── InputInjector.kt     # InputManager 反射注入 (支持 displayId 路由)
│       │   └── EventConverter.kt    # InputMessage → MotionEvent/KeyEvent
│       ├── display/             # 虚拟显示管理
│       │   ├── VirtualDisplayManager.kt  # 虚拟显示创建 (Android 10-14+)
│       │   ├── VirtualDisplayLauncher.kt # 虚拟显示应用启动器
│       │   ├── WakeLockManager.kt       # 防深度睡眠
│       │   └── SecureFlagBypass.kt      # FLAG_SECURE 绕过
│       ├── control/             # 设备控制
│       │   ├── ControlHandler.kt    # 控制命令处理
│       │   ├── PowerControl.kt      # 屏幕电源管理
│       │   └── ScreenUnlocker.kt    # 屏幕解锁管理
│       ├── transport/           # 服务端传输
│       │   ├── ServerTransport.kt           # 传输接口
│       │   ├── BluetoothServerTransport.kt  # BT RFCOMM 服务端
│       │   └── UnixSocketServerTransport.kt # Unix 抽象 socket
│       ├── clipboard/           # 剪贴板
│       │   └── ClipboardHandler.kt  # 剪贴板读写
│       ├── file/                # 文件传输
│       │   ├── FileTransferHandler.kt   # 文件收发
│       │   └── FolderSyncHandler.kt     # 同步处理
│       ├── share/               # 分享转发
│       │   └── ShareForwardHandler.kt   # 分享 Intent 转发
│       ├── auth/                # 认证
│       │   └── AuthHandler.kt       # 认证处理
│       └── util/                # 工具
│           └── HiddenApiBypass.kt   # 隐藏 API 访问
│
└── app/                         # Android 应用模块
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/scrcpybt/app/
        │   ├── ScrcpyApplication.kt     # Application 入口
        │   ├── ui/                  # 界面
        │   │   ├── MainActivity.kt          # 角色/传输选择
        │   │   ├── controller/              # 控制端
        │   │   │   ├── ControllerActivity.kt    # 镜像画面 + 触摸控制
        │   │   │   ├── DisplayRenderer.kt       # SurfaceView 渲染
        │   │   │   └── TouchHandler.kt          # 触摸 → InputMessage
        │   │   ├── relay/                   # 中继端
        │   │   │   └── RelayActivity.kt         # 转发状态显示
        │   │   ├── controlled/              # 被控端
        │   │   │   ├── ControlledActivity.kt    # 服务端启动配置
        │   │   │   └── ServerLauncher.kt        # su + app_process 命令构建
        │   │   ├── clipboard/               # 剪贴板
        │   │   │   └── ClipboardActivity.kt
        │   │   ├── filetransfer/            # 文件传输
        │   │   │   └── FileTransferActivity.kt
        │   │   ├── foldersync/              # 文件夹同步
        │   │   │   └── FolderSyncActivity.kt
        │   │   ├── history/                 # 操作历史
        │   │   │   ├── HistoryActivity.kt
        │   │   │   └── HistoryAdapter.kt
        │   │   └── share/                   # 分享转发
        │   │       ├── ShareReceiverActivity.kt # ACTION_SEND 接收
        │   │       └── ShareConfigActivity.kt   # 被控端分享配置
        │   ├── transport/           # 传输实现
        │   │   ├── TransportMonitor.kt          # 传输状态监控
        │   │   ├── bluetooth/
        │   │   │   ├── BluetoothManager.kt      # 蓝牙发现/配对
        │   │   │   ├── BluetoothRfcommClient.kt # RFCOMM 客户端
        │   │   │   ├── BluetoothRfcommServer.kt # RFCOMM 服务端
        │   │   │   └── BluetoothConnection.kt   # Connection 实现
        │   │   └── usb/
        │   │       ├── AdbForwardManager.kt     # adb forward 管理
        │   │       ├── AdbConnection.kt         # LocalSocket 连接
        │   │       └── UsbDetector.kt           # USB 连接检测
        │   ├── service/             # 前台服务
        │   │   ├── ControllerService.kt     # 控制端服务
        │   │   └── RelayService.kt          # 中继端服务
        │   ├── codec/               # 客户端解码
        │   │   ├── FrameDecoder.kt          # 帧解码 → Bitmap
        │   │   └── DecoderThread.kt         # 解码线程
        │   ├── handler/             # 功能处理器
        │   │   ├── ClipboardTransferHandler.kt  # 剪贴板处理
        │   │   └── FileTransferController.kt    # 文件传输控制
        │   ├── auth/                # 认证
        │   │   ├── DeviceTrustStore.kt      # 设备信任存储
        │   │   └── FeatureAuthClient.kt     # 功能级认证客户端
        │   ├── history/             # 历史记录
        │   │   ├── HistoryDatabase.kt       # SQLite 数据库
        │   │   └── HistoryEntry.kt          # 记录条目
        │   ├── transfer/            # 传输状态
        │   │   ├── TransferSettings.kt      # 传输配置
        │   │   ├── TransferState.kt         # 状态模型
        │   │   └── TransferStateDatabase.kt # 断点续传数据库
        │   ├── sync/                # 文件夹同步
        │   │   ├── SyncEngine.kt            # 同步引擎核心
        │   │   ├── SyncService.kt           # 同步前台服务
        │   │   ├── SyncDatabase.kt          # 同步状态数据库
        │   │   ├── SyncDiff.kt              # 差异比较算法
        │   │   ├── BlockHasher.kt           # 块级 SHA-256 哈希
        │   │   ├── FileVersioner.kt         # 文件版本管理
        │   │   ├── FolderWatcher.kt         # FileObserver 监控
        │   │   └── PowerConditionChecker.kt # 电源条件检查
        │   └── util/                # 工具
        │       ├── RootChecker.kt           # Root 检测
        │       ├── PermissionHelper.kt      # 运行时权限
        │       └── NotificationHelper.kt    # 通知管理
        └── res/
            ├── layout/              # 布局文件 (12 个)
            ├── menu/                # 菜单 (2 个)
            ├── values/strings.xml   # 英文字符串
            ├── values-zh/strings.xml# 中文字符串
            └── xml/file_paths.xml   # FileProvider 路径
```

## 技术架构

### 256 色编码管线

```
捕获 ARGB_8888 帧
    ↓
中位切割 (Median Cut) 生成 256 色调色板 (每 30 帧更新)
    ↓
RGB → 调色板索引映射 (15-bit 查找表加速, 32768 个条目)
    ↓
16×16 块级脏区检测 (对比前帧)
    ↓
XOR 帧差编码 (仅编码变化区域)
    ↓
LZ4 快速压缩
    ↓
组装 FrameMessage 发送
```

关键帧每 150 帧强制发送一次；脏区覆盖超过 60% 时自动转为关键帧。

### 二进制协议

所有消息使用 14 字节头 + 变长载荷：

```
[magic:4][type:1][flags:1][length:4][sequence:4]

magic   = 0x53435242 ("SCRB")
type    = 消息类型 (0x01-0x0C)
flags   = 0x01=加密, 0x02=压缩, 0x04=需要ACK
length  = 载荷长度
sequence= 序列号
```

12 种消息类型：HANDSHAKE, KEY_EXCHANGE, FRAME, INPUT, CONTROL, HEARTBEAT, CLIPBOARD, FILE_TRANSFER, FOLDER_SYNC, SHARE_FORWARD, AUTH, TRANSPORT_SWITCH

### 加密流程

```
1. 明文握手 → 交换设备信息、分辨率、版本
2. ECDH P-256 交换公钥 → 计算共享密钥
3. HKDF-SHA256 派生 AES-256 密钥和 nonce 基底
4. 后续所有消息用 AES-256-GCM 加密
   - 12 字节 nonce = 4 字节基底 + 8 字节递增计数器
   - 发送和接收使用独立的 nonce 序列，防止重放
```

### 虚拟显示模式 (Android 10+)

类似 Windows 远程桌面的行为：

```
物理屏幕: 关闭或显示锁屏 → 设备旁的人无法看到远程操作
虚拟显示: 独立的第二屏幕 → 远程用户在此操作，有完整 UI
```

实现细节：
- 通过 `DisplayManager` 隐藏 API（反射）创建虚拟显示
- Android 14+ 使用 `VirtualDisplayConfig.Builder`
- Android 10-13 使用 legacy `createVirtualDisplay` 方法
- 物理显示令牌获取支持 4 级回退策略 (Android 10-14+)
- `WakeLockManager` 防止物理屏幕关闭后设备深度睡眠
- `VirtualDisplayLauncher` 自动在虚拟显示上启动应用（防黑屏）
- `SecureFlagBypass` 可选绕过 FLAG_SECURE（查看银行/支付应用）
- 触摸事件通过 `InputEvent.setDisplayId()` 路由到虚拟显示
- 会话结束自动恢复物理屏幕并清理虚拟显示

### 服务端部署方式

被控端的服务端进程通过 Root 权限以 `app_process` 方式启动：

```bash
su -c "CLASSPATH=/data/data/com.scrcpybt.app/cache/server.jar \
  app_process / com.scrcpybt.server.ServerMain \
  --transport=bluetooth \
  --virtual-display=true \
  --start-app=com.android.settings/.Settings \
  --bypass-secure-flag=false"
```

这赋予服务端 shell 级别权限，可以：
- 通过 `SurfaceControl` 隐藏 API 捕获屏幕
- 通过 `InputManager` 隐藏 API 注入触摸/按键事件
- 通过 `DisplayManager` 隐藏 API 创建虚拟显示
- 通过 `SurfaceControl.setDisplayPowerMode` 控制物理屏幕电源

### 中继转发

中继端 (B) 透明转发控制端 (A) 和被控端 (C) 之间的加密数据流：

```
控制端 A ←→ [蓝牙/USB] ←→ 中继端 B ←→ [蓝牙/USB] ←→ 被控端 C
```

中继端不解密、不解析任何载荷数据，仅原样转发字节流。

### 文件夹同步

参考 Syncthing 设计的块级增量同步：

- **分块哈希**：文件按 128KB 分块，每块计算 SHA-256 哈希
- **差异比较**：只传输哈希不匹配的块
- **版本控制**：5 种策略（无、回收站、简单、交错、外部命令）
- **冲突解决**：自动创建 `.sync-conflict-<时间戳>-<来源>` 副本
- **忽略规则**：.syncignore 支持 glob 模式匹配
- **电源感知**：可配置仅充电时同步、最低电量、时间窗口

## 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Kotlin | 1.9.22 | 语言 |
| AGP | 8.2.2 | Android 构建 |
| AndroidX AppCompat | 1.6.1 | 兼容性 |
| Material Components | 1.11.0 | UI 组件 |
| ConstraintLayout | 2.1.4 | 布局 |
| Lifecycle | 2.7.0 | ViewModel/LiveData |
| Core KTX | 1.12.0 | Kotlin 扩展 |
| Bouncy Castle | 1.77 | ECDH/加密 |
| LZ4 (jpountz) | - | 帧数据压缩 |

## 构建

> **注意**：本项目仅包含源代码，不包含 Gradle Wrapper 和构建环境配置。

```bash
# 确保已安装 Android SDK (compileSdk 34) 和 JDK 11+

# 构建 debug APK
./gradlew assembleDebug

# server.jar 需要从 server 模块构建后复制到 app/src/main/assets/
./gradlew :server:jar
cp server/build/libs/server.jar app/src/main/assets/
```

## 国际化

应用支持中英双语，通过 Android 标准 i18n 机制实现：

- `res/values/strings.xml` — 英文（默认）
- `res/values-zh/strings.xml` — 中文

共 300+ 个字符串资源，覆盖所有 UI 文本。

## 参考项目

- [scrcpy](https://github.com/Genymobile/scrcpy) — 服务端架构参考（app_process 部署、SurfaceControl 捕获、InputManager 注入）
- [Syncthing](https://syncthing.net/) — 文件夹同步协议设计参考（块级同步、版本控制、忽略规则）

## 许可证

本项目仅供学习和研究使用。
