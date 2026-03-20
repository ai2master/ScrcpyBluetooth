# ScrcpyBluetooth - 产品介绍 | Product Overview

## 目录 | Table of Contents

- [一句话定位 | Tagline](#一句话定位--tagline)
- [解决什么问题 | Problem Statement](#解决什么问题--problem-statement)
- [六大亮点 | Six Key Highlights](#六大亮点--six-key-highlights)
- [使用场景 | Use Cases](#使用场景--use-cases)
- [竞品对比 | Competitive Comparison](#竞品对比--competitive-comparison)
- [技术规格 | Technical Specifications](#技术规格--technical-specifications)
- [路线图 | Roadmap](#路线图--roadmap)
- [参与贡献 | Contributing](#参与贡献--contributing)

---

## 一句话定位 | Tagline

**无需网络、端到端加密的 Android 对 Android 屏幕镜像与远程控制工具。**

**Offline-first, end-to-end encrypted Android-to-Android screen mirroring and remote control.**

---

## 解决什么问题 | Problem Statement

### 痛点 | Pain Points

| # | 痛点 | 现有方案的不足 |
|---|------|--------------|
| 1 | **离线场景下无法远程协助** | 现有远程控制工具（TeamViewer、AnyDesk）全部依赖互联网连接 |
| 2 | **Android→Android 方案缺失** | scrcpy 只支持 PC→Android；Vysor 同样需要 PC 中转 |
| 3 | **网络安全顾虑** | 公共网络/不受信环境下，数据经第三方服务器中转，存在窃听风险 |
| 4 | **被控端准备复杂** | 许多方案需要 ROOT 权限或安装额外 PC 工具 |
| 5 | **蓝牙距离受限** | 蓝牙有效范围约 10 米，更远距离场景无法覆盖 |
| 6 | **跨设备协作低效** | 两台手机间传文件、同步剪贴板需要多个 APP 配合 |

### ScrcpyBluetooth 的答案 | Our Solution

ScrcpyBluetooth 用**一个 APK** 同时解决以上所有问题：

- 蓝牙/USB 直连 → 解决离线场景
- Android-to-Android → 不需要 PC
- 端到端加密 → 即使经过中继也无法窃听
- ADB Shell 权限 → 不需要 ROOT
- 中继模式 → 第三台设备延伸蓝牙距离
- 内置剪贴板/文件传输/文件夹同步 → 一站式协作

---

## 六大亮点 | Six Key Highlights

### 1. 纯离线，零网络依赖 | Offline-First, Zero Network Dependency

**蓝牙配对即可开始，不需要 Wi-Fi、移动数据或任何互联网连接。**

工作原理：
- **蓝牙模式**：通过 Bluetooth RFCOMM 协议建立点对点数据通道
- **USB 模式**：通过 ADB Forward 建立 Unix Socket 通道
- **自动切换**：连接中可随时在蓝牙/USB 间切换，会话不中断

适用环境：
| 场景 | 网络状况 | ScrcpyBluetooth |
|------|---------|----------------|
| 飞行模式 | 无网络 | 正常工作（蓝牙不受飞行模式影响） |
| 地下室/矿井 | 无信号 | 正常工作 |
| 偏远地区 | 无基站 | 正常工作 |
| 保密场所 | 禁用网络 | 正常工作 |
| 海外漫游 | 高费用 | 正常工作，零流量消耗 |

### 2. 端到端加密，军事级安全 | End-to-End Encryption, Military-Grade Security

**采用与 Signal 同级别的加密方案，中继设备无法窃听。**

加密架构：

```
密钥交换 | Key Exchange:
  ECDH P-256 (secp256r1) → 256-bit 共享密钥

密钥派生 | Key Derivation:
  HKDF-SHA256(shared_secret, salt="ScrcpyBT-v1", info="session")
  → AES-256 密钥 + 基础 nonce

数据加密 | Data Encryption:
  AES-256-GCM (认证加密，防篡改)
  12 字节 nonce = 4B 基础值 + 8B 单调递增计数器

设备认证 | Device Authentication:
  TOFU (Trust On First Use) + 设备指纹持久化
```

安全保障：
| 保障项 | 说明 |
|--------|------|
| **不可窃听** | AES-256-GCM 加密所有传输数据 |
| **不可篡改** | GCM 认证标签验证数据完整性 |
| **不可重放** | 单调递增 nonce 防止重放攻击 |
| **前向保密** | 每次会话生成新的 ECDH 临时密钥对 |
| **中继安全** | 中继端只转发密文，无法获取密钥 |
| **首次信任** | TOFU 模型持久化设备指纹，后续连接自动验证 |

### 3. 统一 APK，三种角色 | One APK, Three Roles

**控制端、被控端、中继端——同一个 APK，安装即用。**

```
┌─────────────────────────────────┐
│         ScrcpyBluetooth APK     │
│  ┌───────┐ ┌───────┐ ┌───────┐ │
│  │ 控制端 │ │ 中继端 │ │ 被控端 │ │
│  │  (A)  │ │  (B)  │ │  (C)  │ │
│  └───────┘ └───────┘ └───────┘ │
│     APP        APP      Shell   │
│   普通运行    普通运行   ADB权限  │
└─────────────────────────────────┘
```

| 角色 | 功能 | 运行方式 | 需要 ROOT |
|------|------|---------|----------|
| **控制端 A** | 查看远程屏幕、发送触摸/按键 | 标准 APP 运行 | 否 |
| **中继端 B** | 透明转发加密数据 | 标准 APP 运行 | 否 |
| **被控端 C** | 捕获屏幕、注入输入、处理文件 | ADB Shell 权限 | 否 |

被控端通过 `app_process` 以 Shell 权限运行服务端进程，无需 ROOT 即可实现：
- 屏幕截图 → `SurfaceControl` API
- 输入注入 → `InputManager.injectInputEvent()`
- 剪贴板操作 → `ClipboardManager`（系统级）
- 文件访问 → Shell 用户权限范围

### 4. 丰富附加功能 | Feature-Rich Beyond Mirroring

**不仅仅是屏幕镜像，更是完整的跨设备协作平台。**

#### 4.1 剪贴板同步 | Clipboard Transfer

双向实时剪贴板传递，支持在控制端和被控端之间同步复制的文本。

| 操作 | 说明 |
|------|------|
| 发送到远端 | 将本地剪贴板内容推送到被控端 |
| 从远端获取 | 拉取被控端的剪贴板内容 |
| 文本选中快捷操作 | 任意 APP 中选中文本，一键发送到远端 |

安全限制：内容长度上限 1MB，所有传输经端到端加密。

#### 4.2 文件传输 | File Transfer

支持大文件分块传输和断点续传。

| 特性 | 说明 |
|------|------|
| **分块传输** | 大文件按可配置块大小分割传输 |
| **断点续传** | 每 N 个块保存检查点，中断后自动恢复 |
| **路径安全** | canonical path 校验，防止路径遍历攻击 |
| **传输队列** | 支持多文件排队，逐一传输 |
| **进度显示** | 实时百分比进度和传输速率 |

#### 4.3 文件夹增量同步 | Folder Sync

基于 SHA-256 块级哈希比较的智能增量同步，只传输实际变化的数据块。

```
扫描文件列表 → 对比差异 → 块级哈希 → 传输变化块 → 更新数据库
```

| 同步模式 | 说明 |
|----------|------|
| **Send Only** | 本地 → 远端（单向推送） |
| **Receive Only** | 远端 → 本地（单向拉取） |
| **Send & Receive** | 双向同步 |

高级特性：
- **文件版本控制**：5 种模式（None / Trashcan / Simple / Staggered / External）
- **电源条件**：仅充电时同步、最低电量阈值、尊重省电模式、时间窗口
- **文件系统监控**：FileObserver 实时检测文件变化
- **.syncignore**：类似 .gitignore 的排除规则

#### 4.4 分享转发 | Share Forwarding

在控制端的任意应用中使用系统"分享"功能，一键将文本或文件转发到被控端的指定应用。

工作流程：
1. 被控端配置目标应用（"Share Config"）
2. 控制端在任意应用中选择"分享" → "Share to Remote"
3. 内容自动加密传输并在被控端目标应用中打开

#### 4.5 中继模式 | Relay Mode

第三台设备做桥梁，延伸蓝牙有效距离。

```
┌──────────┐   蓝牙 10m   ┌──────────┐   蓝牙 10m   ┌──────────┐
│  控制端 A │ ◄──────────► │  中继端 B │ ◄──────────► │  被控端 C │
└──────────┘              └──────────┘              └──────────┘
                    有效距离扩展到 ~20m
```

中继安全保障：
- 中继端只做密文转发，不参与密钥交换
- 端到端加密密钥仅在 A-C 之间协商
- 中继端无法解密、查看或修改任何传输内容

#### 4.6 虚拟显示器 | Virtual Display

类似 Windows 远程桌面 (RDP) 的独立虚拟屏幕。远程用户在虚拟屏幕上操作，物理屏幕保持关闭或锁定——附近的人无法看到远程会话内容。

| 要求 | 说明 |
|------|------|
| Android 版本 | Android 10.0 (API 29) 及以上 |
| 权限 | 需要 ROOT |
| 可选功能 | 启动指定应用、绕过 FLAG_SECURE |

### 5. 资源高效，后台稳定 | Resource Efficient, Background Stable

**蓝牙有限带宽下的极致优化，确保流畅体验。**

#### 视频编码优化 | Video Encoding Optimization

```
原始屏幕 (ARGB_8888, ~8MB/帧)
    ↓ Median Cut 颜色量化
256 色索引 (1B/像素, ~2MB/帧)
    ↓ XOR 增量编码
增量帧 (仅变化区域, 平均 ~50KB)
    ↓ LZ4 实时压缩
压缩数据 (~10-30KB/帧)
```

| 优化手段 | 效果 |
|----------|------|
| 256 色索引编码 | 数据量减少 75%（4B → 1B/像素） |
| XOR 增量帧 | 画面不变时零开销，仅传变化区域 |
| 16×16 块级脏区检测 | 精确识别变化区域，避免全帧传输 |
| LZ4 实时压缩 | 高速压缩，额外减少 50-80% 数据量 |
| 自适应关键帧 | 每 150 帧强制刷新，变化 >60% 自动升级 |
| 调色板动态重建 | 每 90 帧重建，适应画面内容变化 |

#### 后台保活机制 | Background Persistence

| 机制 | 说明 |
|------|------|
| 前台服务 + 通知 | 防止系统回收进程 |
| PARTIAL_WAKE_LOCK | 保持 CPU 运行（30 分钟超时保护） |
| onTaskRemoved() | 从最近任务滑走后自动重启服务 |
| 电池优化豁免引导 | 首次使用时引导用户关闭电池优化 |

#### 权限优雅降级 | Graceful Permission Degradation

| 策略 | 说明 |
|------|------|
| 拒绝权限不崩溃 | SecurityException 全部被捕获处理 |
| 功能门控 | 缺少权限时禁用相关功能，不影响其他功能 |
| 权限状态面板 | 主界面实时显示权限状态（绿/黄/红） |
| 操作前检查 | 每次操作前验证权限，缺少时弹窗引导 |

### 6. 完全开源，社区驱动 | Fully Open Source, Community Driven

| 项目 | 说明 |
|------|------|
| 开源协议 | MIT License |
| 源代码 | [GitHub: ai2master/ScrcpyBluetooth](https://github.com/ai2master/ScrcpyBluetooth) |
| 代码注释 | 中英双语详细注释 |
| 技术文档 | 完整的架构说明、使用指南 |
| CI/CD | GitHub Actions 自动构建 |
| 贡献指南 | 欢迎 PR、Issue、功能建议 |

---

## 使用场景 | Use Cases

### 场景 1：远程协助家人 | Remote Family Assistance

> 爸妈不会操作手机上的新功能，打电话说不清楚。

**解决方案**：蓝牙配对后，直接在自己手机上操作父母的手机，手把手教学。

- 无需安装额外软件，同一个 APK
- 无需网络，蓝牙范围内即可
- 操作完毕断开蓝牙即可

### 场景 2：会议/教学演示 | Meeting & Teaching Demo

> 需要在课堂上演示手机 APP 的操作流程。

**解决方案**：用一台手机控制演示手机，大屏幕连接控制端即可同步展示。

- 避免直接操作演示机导致的视角遮挡
- 中继模式可延伸控制距离

### 场景 3：离线环境设备管理 | Offline Device Management

> 工厂车间/野外作业/地下空间，没有网络覆盖。

**解决方案**：蓝牙直连或 USB 连接，不依赖任何网络基础设施。

- 飞行模式下正常工作
- USB 模式提供更高带宽和更低延迟

### 场景 4：安全敏感操作 | Security-Sensitive Operations

> 在公共场所需要远程操作含敏感信息的设备。

**解决方案**：端到端加密确保数据安全，虚拟显示器模式让物理屏幕保持锁定。

- 中继设备无法解密任何内容
- 虚拟显示器模式：附近的人看不到操作内容
- 会话结束自动清理安全状态

### 场景 5：跨设备文件协作 | Cross-Device File Collaboration

> 两台手机之间需要频繁同步文件和剪贴板。

**解决方案**：文件夹增量同步 + 剪贴板双向传递，一站式解决。

- SHA-256 块级哈希，只传变化部分
- 断点续传，大文件传输中断不怕
- 分享转发，任意 APP 一键发送

### 场景 6：多机协同操控 | Multi-Device Coordination

> 需要同时管理多台 Android 设备。

**解决方案**：每台设备安装同一个 APK，灵活切换控制/被控角色。

- 统一 APK 无需区分安装包
- 支持蓝牙和 USB 混合连接
- 传输切换不中断会话

---

## 竞品对比 | Competitive Comparison

### 功能对比 | Feature Comparison

| 特性 | ScrcpyBluetooth | scrcpy (原版) | Vysor | TeamViewer | AnyDesk |
|------|:-:|:-:|:-:|:-:|:-:|
| Android → Android | **是** | 否 | 否 | 是 | 是 |
| 无需 PC | **是** | 否 | 否 | 是 | 是 |
| 无需网络 | **蓝牙/USB** | USB | USB | 否 | 否 |
| 端到端加密 | **AES-256-GCM** | 无 | 无 | 有 | 有 |
| 无需 ROOT | **是** | 是 | 是 | 是 | 是 |
| 开源 | **MIT** | Apache-2.0 | 否 | 否 | 否 |
| 文件传输 | **断点续传** | adb push/pull | 否 | 是 | 是 |
| 文件夹同步 | **增量块级** | 否 | 否 | 否 | 否 |
| 剪贴板同步 | **双向** | 单向 | 是 | 是 | 是 |
| 中继模式 | **是** | 否 | 否 | 服务器中继 | 服务器中继 |
| 虚拟显示器 | **是** | 否 | 否 | 否 | 否 |
| 分享转发 | **是** | 否 | 否 | 否 | 否 |
| APK 体积 | **~9MB** | N/A | ~15MB | ~60MB | ~20MB |

### 连接方式对比 | Connection Mode Comparison

| 特性 | ScrcpyBluetooth | scrcpy | TeamViewer |
|------|:-:|:-:|:-:|
| 蓝牙直连 | **是** | 否 | 否 |
| USB ADB | **是** | 是 | 否 |
| 蓝牙中继 | **是** | 否 | 否 |
| Wi-Fi | 否 | 是 | 否 |
| 互联网 | 否 | 否 | 是 |
| 传输热切换 | **是** | 否 | 否 |

### 安全性对比 | Security Comparison

| 特性 | ScrcpyBluetooth | scrcpy | TeamViewer |
|------|:-:|:-:|:-:|
| 传输加密 | **AES-256-GCM** | 无 | TLS |
| 密钥交换 | **ECDH P-256** | 无 | RSA |
| 数据路径 | **点对点** | 点对点 | 经服务器中转 |
| 中继安全 | **端到端加密** | N/A | 取决于实现 |
| 设备认证 | **TOFU+指纹** | 无 | 账户认证 |
| 开源可审计 | **是** | 是 | 否 |

---

## 技术规格 | Technical Specifications

### 通信协议 | Communication Protocol

| 参数 | 值 |
|------|------|
| 协议版本 | v1 |
| 消息头 | 14 字节（类型 1B + 标志 1B + 会话 ID 4B + 载荷长度 4B + 校验 4B） |
| 消息类型 | 12 种（握手、帧、输入、剪贴板、文件等） |
| 载荷上限 | 16 MB / 消息 |
| 蓝牙传输 | RFCOMM SPP 协议 |
| USB 传输 | ADB Forward + Unix Abstract Socket |

### 视频编码 | Video Encoding

| 参数 | 值 |
|------|------|
| 色彩模式 | 256 色索引（Median Cut 量化） |
| 帧类型 | 关键帧 + XOR 增量帧 |
| 压缩算法 | LZ4 |
| 关键帧间隔 | 每 150 帧 |
| 调色板重建 | 每 90 帧 |
| 脏区检测 | 16×16 像素块 |
| 关键帧升级 | 变化块 > 60% 时自动升级 |

### 加密规格 | Encryption Specifications

| 参数 | 值 |
|------|------|
| 密钥交换 | ECDH P-256 (secp256r1) |
| 对称加密 | AES-256-GCM |
| 密钥派生 | HKDF-SHA256 |
| Nonce 长度 | 12 字节（4B base + 8B counter） |
| 认证模型 | TOFU (Trust On First Use) |
| 指纹存储 | EncryptedSharedPreferences |

### 系统要求 | System Requirements

| 参数 | 值 |
|------|------|
| 最低 Android 版本 | Android 9.0 (API 28) |
| 虚拟显示器 | Android 10.0 (API 29) + ROOT |
| 蓝牙版本 | Bluetooth 4.0+ |
| APK 体积 | ~9 MB |
| 运行内存 | 建议 50MB+ 可用 |

---

## 路线图 | Roadmap

### 近期计划 | Short-term

| 功能 | 状态 | 说明 |
|------|------|------|
| Wi-Fi Direct 传输 | 计划中 | 更高带宽的离线传输选项 |
| 自动重连 | 计划中 | 连接断开后自动恢复 |
| H.264/H.265 编码 | 计划中 | 硬件加速编码，更高画质 |
| 多语言 UI | 计划中 | 完整的国际化支持 |

### 中期计划 | Mid-term

| 功能 | 状态 | 说明 |
|------|------|------|
| 音频转发 | 调研中 | 同步传输被控端音频 |
| 多点控制 | 调研中 | 一台控制端同时控制多台被控端 |
| 录屏功能 | 调研中 | 录制远程操控过程 |

### 长期愿景 | Long-term Vision

| 功能 | 说明 |
|------|------|
| 跨平台控制端 | iOS / Desktop 控制端 |
| 插件系统 | 自定义功能扩展 |
| P2P 网络 | 基于区块链的去中心化设备网络 |

---

## 参与贡献 | Contributing

### 如何参与 | How to Contribute

1. **提交 Issue**：报告 Bug 或提出功能建议
2. **提交 PR**：修复问题或添加新功能
3. **改进文档**：完善使用说明和技术文档
4. **分享推广**：Star 项目、分享给有需要的人

### 开发环境 | Development Environment

```bash
# 克隆仓库 | Clone
git clone https://github.com/ai2master/ScrcpyBluetooth.git
cd ScrcpyBluetooth

# 构建 | Build
./gradlew assembleDebug

# 安装到设备 | Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 代码规范 | Code Standards

| 规范 | 说明 |
|------|------|
| 语言 | Kotlin |
| 注释 | 中英双语 `// 中文 | English` |
| 提交 | Conventional Commits 格式 |
| CI | GitHub Actions 自动构建验证 |

### 项目结构 | Project Structure

```
ScrcpyBluetooth/
├── app/          # Android 应用模块（控制端 + 中继端 UI）
├── server/       # 被控端服务模块（ADB Shell 运行）
├── common/       # 共享协议、加密、消息定义
└── docs/         # 项目文档
    ├── product.md       # 产品介绍（本文件）
    ├── usage.md         # 使用说明
    └── architecture.md  # 技术架构
```

---

## 开源协议 | License

MIT License — 自由使用、修改、分发，商用亦可。

---

*最后更新 | Last Updated: 2026-03-20*
