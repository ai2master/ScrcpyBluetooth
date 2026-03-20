# ScrcpyBluetooth 使用说明 | User Guide

## 目录 | Table of Contents

- [简介 | Introduction](#简介--introduction)
- [系统要求 | System Requirements](#系统要求--system-requirements)
- [安装方式 | Installation](#安装方式--installation)
- [快速入门 | Quick Start](#快速入门--quick-start)
- [角色说明 | Role Overview](#角色说明--role-overview)
- [连接模式 | Connection Modes](#连接模式--connection-modes)
- [核心功能 | Core Features](#核心功能--core-features)
- [高级功能 | Advanced Features](#高级功能--advanced-features)
- [权限说明 | Permissions](#权限说明--permissions)
- [设置与配置 | Settings & Configuration](#设置与配置--settings--configuration)
- [故障排除 | Troubleshooting](#故障排除--troubleshooting)
- [快捷操作 | Shortcuts & Quick Actions](#快捷操作--shortcuts--quick-actions)

---

## 简介 | Introduction

ScrcpyBluetooth 是一款 **Android 对 Android** 的屏幕镜像与远程控制应用。它让你通过蓝牙或 USB 数据线，在一台 Android 设备上实时查看并控制另一台 Android 设备的屏幕——完全不需要 Wi-Fi、移动数据或任何互联网连接。

ScrcpyBluetooth is an **Android-to-Android** screen mirroring and remote control application. It enables you to view and control one Android device's screen from another in real-time via Bluetooth or USB — no Wi-Fi, mobile data, or internet required.

### 核心特点 | Key Features

| 特点 | 说明 |
|------|------|
| **纯离线** | 蓝牙/USB 直连，无需网络 |
| **端到端加密** | AES-256-GCM + ECDH 密钥交换，中继设备无法窃听 |
| **统一 APK** | 控制端、被控端、中继端三种角色共用同一个 APK |
| **无需 ROOT** | 被控端通过 ADB Shell 权限运行，不需要 ROOT |
| **丰富附加功能** | 剪贴板同步、文件传输、文件夹增量同步、分享转发 |

---

## 系统要求 | System Requirements

### 硬件要求 | Hardware

| 项目 | 要求 |
|------|------|
| 设备数量 | 最少 2 台 Android 设备（直连模式）或 3 台（中继模式） |
| 蓝牙 | Bluetooth 4.0 或更高版本（蓝牙模式） |
| USB | USB 数据线 + ADB 调试已启用（USB 模式） |
| 存储空间 | APK 约 9MB，运行时建议预留 50MB 以上 |

### 软件要求 | Software

| 项目 | 要求 |
|------|------|
| 操作系统 | Android 9.0 (API 28) 及以上 |
| 虚拟显示器模式 | Android 10.0 (API 29) 及以上 + ROOT（可选） |
| ADB 调试 | 需在被控端启用（设置 → 开发者选项 → USB 调试） |

---

## 安装方式 | Installation

### 方式一：从 GitHub Releases 下载 | Download from GitHub Releases

1. 访问 [Releases 页面](https://github.com/ai2master/ScrcpyBluetooth/releases)
2. 下载最新的 `app-debug.apk`
3. 在**两台设备**上都安装此 APK（同一个 APK 包含所有角色）

### 方式二：从源码构建 | Build from Source

```bash
# 克隆仓库 | Clone repository
git clone https://github.com/ai2master/ScrcpyBluetooth.git
cd ScrcpyBluetooth

# 构建 Debug APK | Build Debug APK
./gradlew assembleDebug
```

构建产物位于：`app/build/outputs/apk/debug/app-debug.apk`

### 安装到设备 | Install to Devices

```bash
# 安装到两台设备 | Install to both devices
adb -s <设备A序列号> install app-debug.apk
adb -s <设备C序列号> install app-debug.apk

# 中继模式还需安装到第三台设备 | For relay mode, install on the 3rd device
adb -s <设备B序列号> install app-debug.apk
```

---

## 快速入门 | Quick Start

### 30 秒快速体验 | 30-Second Quick Start

1. **两台设备**都安装同一个 APK
2. **蓝牙配对**：在系统蓝牙设置中完成两台设备的配对
3. **设备 C（被控端）**：打开 APP → 选择 "Controlled Device (C)" → 选择 "Bluetooth" → 点击 "Start Server"
4. **设备 A（控制端）**：打开 APP → 选择 "Controller (A)" → 选择 "Bluetooth" → 点击 "Start"
5. 连接建立后，设备 A 上会显示设备 C 的屏幕画面，触摸即可远程操控

---

## 角色说明 | Role Overview

ScrcpyBluetooth 采用 **A-B-C 三方架构**，每台设备可以扮演以下三种角色之一：

### 控制端 A | Controller (A)

- **功能**：查看被控端屏幕画面，发送触摸/按键事件进行远程操控
- **界面**：显示远程屏幕的实时镜像画面，底部有导航按钮（返回、Home、最近任务、息屏、解锁）
- **附加功能**：通过菜单访问剪贴板传输、文件传输、文件夹同步等功能
- **运行方式**：标准 Android APP，直接打开使用

### 中继端 B | Relay (B)

- **功能**：在控制端和被控端之间透明转发数据，自身不解密或查看任何内容
- **使用场景**：当 A 和 C 之间蓝牙距离不够时，在中间放一台设备做桥梁
- **安全性**：中继端只转发密文，端到端加密保证中继设备无法窃听
- **运行方式**：标准 Android APP，启动后自动处理数据转发

### 被控端 C | Controlled Device (C)

- **功能**：捕获自身屏幕画面并发送给控制端，接收远程输入事件并注入执行
- **能力**：屏幕镜像、输入注入、剪贴板操作、文件操作等
- **运行方式**：APP 内启动 Server 进程，通过 ADB Shell 权限运行

---

## 连接模式 | Connection Modes

### 模式一：蓝牙直连 (A ↔ C) | Bluetooth Direct

```
┌──────────┐    Bluetooth RFCOMM    ┌──────────┐
│  控制端 A │ ◄────────────────────► │  被控端 C │
└──────────┘                        └──────────┘
```

**适用场景**：两台设备在蓝牙有效范围内（约 10 米）

**操作步骤**：
1. 系统设置中完成蓝牙配对
2. 被控端打开 APP → 选择 "Controlled Device (C)" → Bluetooth → Start Server
3. 控制端打开 APP → 选择 "Controller (A)" → Bluetooth → Start
4. 选择已配对的蓝牙设备，等待连接建立

### 模式二：蓝牙中继 (A ↔ B ↔ C) | Bluetooth Relay

```
┌──────────┐    Bluetooth    ┌──────────┐    Bluetooth    ┌──────────┐
│  控制端 A │ ◄────────────► │  中继端 B │ ◄────────────► │  被控端 C │
└──────────┘                 └──────────┘                 └──────────┘
```

**适用场景**：A 和 C 之间距离超过蓝牙范围，中间放一台设备做桥梁

**操作步骤**：
1. A-B 和 B-C 分别完成蓝牙配对
2. 被控端 C：打开 APP → Controlled Device → Bluetooth → Start Server
3. 中继端 B：打开 APP → Relay (B) → Start
4. 控制端 A：打开 APP → Controller (A) → Bluetooth → Start → 连接到 B

### 模式三：USB ADB 直连 (A ↔ C) | USB ADB Direct

```
┌──────────┐    USB ADB Forward    ┌──────────┐
│  控制端 A │ ◄──────────────────► │  被控端 C │
└──────────┘                       └──────────┘
```

**适用场景**：追求更低延迟和更高带宽的有线连接

**操作步骤**：
1. 两台设备通过 USB 数据线连接（可能需要 OTG 转接头）
2. 确保 ADB 调试已启用
3. 被控端打开 APP → Controlled Device → USB ADB → Start Server
4. 控制端打开 APP → Controller (A) → USB ADB → Start

### 传输切换 | Transport Switching

连接建立后，可以在**不断开会话**的情况下切换传输方式（蓝牙 ↔ USB）。会话 ID 保持不变，加密通道自动迁移。

---

## 核心功能 | Core Features

### 1. 屏幕镜像 | Screen Mirroring

| 参数 | 值 |
|------|------|
| 色彩模式 | 256 色索引（低带宽优化） |
| 帧策略 | 增量帧（仅传输变化区域），每 150 帧一个关键帧 |
| 压缩算法 | LZ4 实时压缩 |
| 调色板 | Median Cut 算法，每 90 帧重建一次 |
| 脏区检测 | 16x16 像素块级别比较，变化 >60% 自动升级为关键帧 |

**使用方式**：连接建立后自动开始屏幕镜像，控制端实时显示被控端画面。

### 2. 远程操控 | Remote Control

支持在控制端屏幕上直接触摸操控被控端：

| 操作 | 映射 |
|------|------|
| 单指点击 | 触摸事件注入 |
| 滑动手势 | 连续触摸事件 |
| 底部导航栏 | 返回、Home、最近任务、息屏、解锁 |
| 硬件按键 | 电源键、音量键等 |

**输入限速**：令牌桶算法限制注入速率（200 事件/秒），防止过载。

### 3. 剪贴板同步 | Clipboard Transfer

**双向实时剪贴板传递**，在控制端和被控端之间同步复制的文本。

**使用方式**：
1. 连接建立后，通过控制端菜单进入 "Clipboard Transfer"
2. **刷新本地剪贴板**：查看控制端当前剪贴板内容
3. **发送到远端**：将控制端剪贴板发送到被控端
4. **从远端获取**：获取被控端剪贴板内容
5. **复制到本地**：将获取的远端内容复制到控制端剪贴板

**安全特性**：剪贴板内容长度限制 1MB，所有传输经过端到端加密。

### 4. 文件传输 | File Transfer

支持**大文件分块传输**和**断点续传**。

**使用方式**：
1. 进入 "File Transfer" 功能页
2. 点击 "Select File(s)" 选择要发送的文件
3. 文件自动加入传输队列，逐一发送
4. 实时显示传输进度
5. 传输中断后重连，自动恢复断点继续传输

**技术特性**：
| 特性 | 说明 |
|------|------|
| 分块传输 | 大文件按配置的块大小分割传输 |
| 断点续传 | 每传输 N 个块保存检查点，中断后从检查点恢复 |
| 路径安全 | canonical path 校验，防止路径遍历攻击 |
| 大小限制 | 单消息载荷上限 16MB |

### 5. 文件夹增量同步 | Folder Sync

基于 **SHA-256 块级哈希比较** 的智能增量同步，只传输实际变化的数据块。

**使用方式**：
1. 进入 "Folder Sync" 功能页
2. 设置本地文件夹路径和远端文件夹路径
3. 选择同步方向：
   - **Send Only**：本地 → 远端（单向推送）
   - **Receive Only**：远端 → 本地（单向拉取）
   - **Send & Receive**：双向同步
4. 配置高级选项（可选，见下方详细说明）
5. 点击 "Start Sync" 开始同步

**同步流程**：
```
扫描本地/远端文件列表
    ↓
对比文件差异（新增/修改/删除）
    ↓
对修改的文件进行块级哈希比较
    ↓
仅传输变化的数据块
    ↓
写入目标文件 + 更新数据库
```

### 6. 分享转发 | Share Forwarding

在控制端的**任意应用**中使用系统"分享"功能，一键将文本或文件转发到被控端的指定应用。

**使用方式**：
1. **被控端配置**：进入 "Share Config" → 开启转发 → 选择目标应用
2. **控制端使用**：在任意应用中选择"分享" → 选择 "Share to Remote"
3. 内容自动通过加密通道发送到被控端，并在目标应用中打开

---

## 高级功能 | Advanced Features

### 虚拟显示器模式 | Virtual Display Mode

类似 Windows 远程桌面 (RDP) 的独立虚拟屏幕功能。远程用户在虚拟屏幕上操作，物理屏幕保持关闭或锁定状态——附近的人无法看到远程会话的内容。

**要求**：Android 10+ 且需要 ROOT 权限

**使用方式**：在被控端的 "Controlled Device" 界面开启 "Enable Virtual Display"

**高级选项**：
| 选项 | 说明 |
|------|------|
| 启动指定应用 | 在虚拟显示器上自动启动指定应用（填写 ComponentName） |
| 绕过 FLAG_SECURE | 允许查看银行/支付类应用的界面（会话结束自动恢复） |

### 强制解锁 | Force Unlock

Android 10 以下设备的备选方案。远程解锁设备的锁屏，允许远程操控。

**注意**：解锁凭据仅保存在本地设备上，不会通过蓝牙或 USB 传输。

**支持的凭据类型**：
- 无锁（滑动解锁）
- PIN 码
- 密码
- 图案锁

### 文件夹同步高级配置 | Advanced Folder Sync Configuration

#### 文件版本控制 | File Versioning

| 模式 | 说明 |
|------|------|
| None | 不保留历史版本，直接覆盖 |
| Trashcan | 删除的文件移入回收站，可设置自动清理天数 |
| Simple | 保留最近 N 个版本 |
| Staggered | 按时间间隔递减保留版本（最近密集、远期稀疏） |
| External | 调用外部命令处理版本控制 |

#### 电源条件 | Power Conditions

| 选项 | 说明 |
|------|------|
| 仅充电时同步 | 开启后仅在设备充电时执行同步 |
| 最低电量 | 电量低于此值时暂停同步（默认 20%） |
| 尊重省电模式 | 系统开启省电模式时暂停同步 |
| 时间窗口 | 仅在指定小时范围内同步（如 2:00-6:00） |

#### 高级设置 | Advanced Settings

| 选项 | 说明 |
|------|------|
| 重扫描间隔 | 定期重新扫描文件变化的间隔（秒） |
| 文件系统监视器 | 实时监控文件变化（使用 FileObserver） |
| 监视器延迟 | 文件变化后等待多久再触发同步 |
| 忽略删除 | 开启后不同步删除操作 |
| 拉取顺序 | 多文件同步时的排序策略（字母序、大小优先、时间优先等） |
| 块大小 | 增量同步的数据块大小 |
| .syncignore | 类似 .gitignore，排除不需要同步的文件和目录 |

---

## 权限说明 | Permissions

应用采用**优雅降级策略**：拒绝任何权限都不会导致崩溃，仅相关功能不可用。

### 必需权限 | Required Permissions

| 权限 | 用途 | 影响范围 |
|------|------|---------|
| `BLUETOOTH_CONNECT` | 建立蓝牙连接 | 蓝牙模式的所有功能 |
| `BLUETOOTH_SCAN` | 扫描和发现蓝牙设备 | 蓝牙设备选择 |
| `FOREGROUND_SERVICE` | 前台服务通知 | 保持后台连接稳定 |

### 可选权限 | Optional Permissions

| 权限 | 用途 | 拒绝后影响 |
|------|------|-----------|
| `BLUETOOTH_ADVERTISE` | 中继端可被其他设备发现 | 中继模式不可用 |
| `READ_EXTERNAL_STORAGE` | 读取文件进行传输 | 文件传输/文件夹同步不可用 |
| `POST_NOTIFICATIONS` | 显示前台服务通知 | 通知栏不显示状态（连接不受影响） |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 请求免除电池优化 | 后台可能被系统杀死 |

### 权限状态面板 | Permission Status Panel

主界面底部会显示权限状态面板：
- **绿色**：所有权限已授予
- **黄色**：缺少可选权限，部分功能受限
- **红色**：缺少必需权限，核心功能无法使用

点击"查看详情"可以查看每个权限的具体状态和说明。

---

## 设置与配置 | Settings & Configuration

### 电池优化 | Battery Optimization

为了保持屏幕镜像连接稳定，强烈建议关闭 ScrcpyBluetooth 的电池优化：

**路径**：系统设置 → 电池 → 电池优化 → ScrcpyBluetooth → 不受限

应用首次启动时会弹窗引导你完成此设置。

### 文件传输设置 | File Transfer Settings

| 设置项 | 说明 | 默认值 |
|--------|------|--------|
| 块大小 | 每个传输块的大小 | 可配置 |
| 检查点间隔 | 每传输多少个块保存一个检查点 | 可配置 |
| 自动重连续传 | 重连后自动恢复中断的传输 | 开启 |

---

## 故障排除 | Troubleshooting

### 连接问题 | Connection Issues

**Q: 蓝牙模式连接不上？**

A: 请检查以下几点：
1. 两台设备是否已在系统蓝牙设置中完成**配对**
2. 两台设备的蓝牙是否都已**开启**
3. 被控端是否已**启动 Server**
4. 控制端选择设备时是否选对了**已配对的设备**
5. 尝试在系统蓝牙中**取消配对后重新配对**

**Q: USB 模式连接不上？**

A: 请检查：
1. 被控端是否已启用 **USB 调试**（设置 → 开发者选项 → USB 调试）
2. 连接 USB 线后是否在被控端弹窗中点击了**允许 USB 调试**
3. 数据线是否支持**数据传输**（部分线只能充电）

### 性能问题 | Performance Issues

**Q: 画面延迟很高？**

A: 蓝牙带宽有限（RFCOMM 最大约 3Mbps），应用已做以下优化：
- 256 色编码（相比 ARGB 减少 4 倍数据量）
- LZ4 压缩（高速实时压缩）
- 增量帧传输（画面不变时零开销）
- 16x16 块级脏区检测（只传输变化的区域）

如果仍然卡顿：
1. 确保两台设备**距离尽量近**（3 米以内最佳）
2. 避免其他蓝牙设备的**信号干扰**
3. 考虑使用 **USB ADB 模式**获得更高带宽

**Q: 画面颜色不准确？**

A: 256 色模式使用 Median Cut 颜色量化，是为低带宽做的取舍。每 90 帧会重建调色板以适应画面变化。

### 稳定性问题 | Stability Issues

**Q: 应用被系统杀死？**

A: 请完成以下设置：
1. 关闭电池优化（见上方设置说明）
2. 在系统"最近任务"中**锁定** ScrcpyBluetooth
3. 部分品牌手机需要在自启动管理中**允许** ScrcpyBluetooth

**Q: 连接断开后能自动重连吗？**

A: 当前版本不支持自动重连，需要手动重新建立连接。但文件传输支持**断点续传**——重连后未完成的传输会自动恢复。

---

## 快捷操作 | Shortcuts & Quick Actions

### 桌面长按快捷方式 | Launcher Shortcuts

在桌面长按 ScrcpyBluetooth 图标，可以快速访问：
- **剪贴板传输** | Clipboard Transfer
- **文件传输** | File Transfer
- **文件夹同步** | Folder Sync
- **分享穿透设置** | Share Forward Config

### 文本选中操作条 | Text Selection Action

在任意应用中选中文本，操作条会出现 "发送到远端剪贴板" 选项，一键将选中的文本发送到被控端。

### 系统分享菜单 | System Share Menu

在任意应用的"分享"菜单中选择 "Share to Remote"，即可将文本或文件转发到被控端。

### 快捷方式配置 | Shortcut Configuration

在 APP 主界面点击 "Shortcut Config" 可以自定义所有快捷方式的开关状态，包括桌面快捷方式、文本选中操作条和系统分享菜单。

---

## 安全须知 | Security Notes

1. **所有通信均经过端到端加密**：ECDH P-256 密钥交换 + AES-256-GCM 认证加密
2. **中继模式安全**：中继设备只转发密文，无法解密任何内容
3. **首次连接信任 (TOFU)**：首次连接时信任设备指纹，后续连接自动验证
4. **文件操作路径安全**：canonical path 校验防止路径遍历攻击
5. **解锁凭据本地存储**：强制解锁的凭据仅保存在本地，不会通过网络传输
6. **FLAG_SECURE 绕过**：仅在用户明确开启时生效，会话结束自动恢复

---

*最后更新 | Last Updated: 2026-03-20*
