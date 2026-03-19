# ScrcpyBluetooth 使用说明

## 简介

ScrcpyBluetooth 是一款 Android 对 Android 的屏幕镜像与远程控制应用，通过蓝牙或 USB 连接，支持端到端加密。

## 系统要求

- **控制端**：Android 9.0 (API 28) 及以上
- **被控端**：Android 9.0 (API 28) 及以上
- 蓝牙 4.0+ 或 USB 数据线
- 两台设备需先完成蓝牙配对（蓝牙模式）

## 安装

### 从源码构建

```bash
git clone https://github.com/ai2master/ScrcpyBluetooth.git
cd ScrcpyBluetooth
./gradlew assembleDebug
```

构建产物：
- 控制端 APK：`app/build/outputs/apk/debug/app-debug.apk`
- 被控端 Server JAR：`server/build/libs/server.jar`

### 安装到设备

```bash
# 安装控制端 APP
adb -s <控制端设备> install app-debug.apk

# 推送被控端 Server 到被控设备
adb -s <被控端设备> push server.jar /data/local/tmp/
```

## 使用方法

### 1. 蓝牙直连模式（A ↔ C）

1. 两台设备先完成蓝牙配对
2. 被控端（C）运行 Server：
   ```bash
   adb shell app_process /data/local/tmp com.scrcpybt.server.ServerMain
   ```
3. 控制端（A）打开 APP → 选择已配对的蓝牙设备 → 连接
4. 连接成功后即可看到被控端屏幕，触摸控制端屏幕即可远程操作

### 2. 中继模式（A ↔ B ↔ C）

适用于蓝牙距离不够的场景，由中继设备（B）桥接：

1. A、B、C 三台设备两两蓝牙配对
2. B 设备打开 APP → 启动中继服务
3. C 设备运行 Server
4. A 设备打开 APP → 连接到 B 的中继服务

### 3. 附加功能

| 功能 | 操作 |
|------|------|
| 剪贴板同步 | 控制端复制的文本自动同步到被控端，反之亦然 |
| 文件传输 | APP 设置页 → 文件传输 → 选择文件发送 |
| 文件夹同步 | APP 设置页 → 文件夹同步 → 选择目录进行增量同步 |
| 分享转发 | 在任意 APP 中使用"分享"→ 选择 ScrcpyBluetooth → 自动转发到被控端 |

## 权限说明

| 权限 | 用途 | 是否必须 |
|------|------|---------|
| BLUETOOTH_CONNECT | 蓝牙连接 | 蓝牙模式必须 |
| BLUETOOTH_SCAN | 扫描蓝牙设备 | 蓝牙模式必须 |
| BLUETOOTH_ADVERTISE | 中继端可被发现 | 中继模式必须 |
| READ_EXTERNAL_STORAGE | 文件传输读取 | 文件功能可选 |
| POST_NOTIFICATIONS | 前台服务通知 | 推荐 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 后台保活 | 推荐 |

应用采用优雅降级策略：拒绝的权限不会导致崩溃，仅相关功能不可用。

## 常见问题

**Q: 连接不上怎么办？**
A: 确保两台设备已完成蓝牙配对，且被控端 Server 已启动。

**Q: 画面延迟高怎么办？**
A: 蓝牙带宽有限，应用已使用 256 色编码 + LZ4 压缩 + 增量帧优化。确保两台设备距离在蓝牙有效范围内。

**Q: 应用被系统杀死怎么办？**
A: 进入系统设置 → 电池优化 → 将 ScrcpyBluetooth 设为"不受限"。应用内也会引导你进行此设置。
