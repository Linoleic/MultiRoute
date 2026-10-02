# MultiRoute

[English](README.md) | [简体中文](README_CN.md)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-10%20--%2017-green.svg)](https://developer.android.com)
[![LibXposed](https://img.shields.io/badge/LibXposed-API%20v102-orange.svg)](https://github.com/libxposed)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org)

MultiRoute 是一款面向 Android 平台的高性能多网络并发与分应用策略路由管理模块。通过融合 Linux 内核策略路由 (`ip rule` / `ip route`) 与现代 LibXposed 系统服务注入 (`ConnectivityService`)，打破 Android 系统默认单网通信的限制，实现主 Wi-Fi、副 Wi-Fi（双 WLAN）、移动蜂窝数据及有线以太网的多网并发与分应用策略分流。

---

## 核心架构与原理

Android 原生网络栈在设计上遵循“单主默认网络（Default Network）”的排他性逻辑：即使系统同时连接了主 Wi-Fi、副 Wi-Fi 和蜂窝移动网络，所有未主动通过网络 API 绑定的应用依然只会走单一默认网络。

MultiRoute 采用双层协同机制实现分应用多网并发：

```
+--------------------------------------------------------------------+
|                         MultiRoute 用户界面                         |
|      (Material 3 / 动态通道感知 / 批量应用分流 / 实时出口 IP 诊断)     |
+--------------------------------------------------------------------+
                                  |
            +---------------------+---------------------+
            v                                           v
+-------------------------------+       +-------------------------------+
|       系统层 (LSPosed)         |       |       内核层 (Linux SU)       |
|    作用域: system_server      |       |    ip rule / ip route 策略    |
+-------------------------------+       +-------------------------------+
| 注入 ConnectivityService:     |       | 为各网络接口建立独立策略表:      |
| - 拦截 getDefaultNetworkForUid|       | - pref 14500 lookup <table_id>|
| - 按规则映射目标 NetworkAgent   |       | - 匹配应用 UID 绑定到专属接口  |
| - 拦截副 WLAN 息屏断联服务      |       | - 维持底层 IP 数据包正确出路   |
+-------------------------------+       +-------------------------------+
                                  |
                                  v
+--------------------------------------------------------------------+
|       并发承载: 主 Wi-Fi | 副 Wi-Fi (双 WLAN) | 移动数据 | 以太网   |
+--------------------------------------------------------------------+
```

1. **内核策略路由层 (Kernel Policy Routing)**：
   - 动态识别并为各个活跃的物理网络接口维护独立路由表；
   - 通过 `ip rule add uidrange <uid>-<uid> lookup <table_id> pref 14500` 将指定应用的底层 Socket 流量重定向至对应网络接口。
2. **系统服务框架层 (LibXposed Modern Hook)**：
   - 遵循现代 LibXposed API v102 规范，作用域精准收敛至 `system_server`；
   - 适配 Android Mainline/APEX 模块化与传统框架结构，拦截系统网络决策中枢 `ConnectivityService` 的 `getDefaultNetworkForUid(int)` 方法，向应用返回匹配通道的 `Network` / `NetworkAgentInfo`，保证应用层 DNS 解析、Socket 自动绑定及网络连通性判定完全一致；
   - 适配系统底层双 WLAN 管理逻辑（如小米 HyperOS / MIUI 的 `SlaveWifiService`），拦截息屏状态下断开副 Wi-Fi 的行为，实现副 Wi-Fi 息屏常驻。

---

## 主要特性

- **动态接口感知**：自动枚举系统底层所有已激活的网络接口，动态呈现接口名称、内网 IP、网关、MAC 及 Wi-Fi SSID，不硬编码任何设备特异性网卡名。
- **分应用策略分流**：支持将任意应用指派至指定通道（例如：网盘/下载工具走副 Wi-Fi，社交通讯走主 Wi-Fi，低延时业务走移动蜂窝网络）。
- **多选批量配置**：长按进入多选模式，支持跨通道批量迁移应用规则。
- **公网出口实时诊断**：内置多预设与自定义 URL 测速/公网 IP 查询工具，直观验证应用出口与网络延迟。
- **副 Wi-Fi 息屏防断联**：针对支持双 WLAN 的设备，支持开启息屏常驻 Hook，避免息屏后副 Wi-Fi 自动断开。
- **零应用侵入**：作用域严格限定为 `system_server`，不侵入第三方应用进程空间；本地配置通过只读 ContentProvider 分发，无常驻后台开销。

---

## 运行环境与前置要求

- **操作系统**：Android 10 - 17+（已在 Android 17 / HyperOS 2 深度测试验证，同时兼容 APEX 模块化与传统架构）
- **Root 权限**：KernelSU / APatch / Magisk（需授权 MultiRoute Root 权限以操作策略路由表）
- **Xposed 框架**：LSPosed（v1.9.3+ 或基于现代 LibXposed 的实现）
  - **模块作用域**：仅需勾选 系统框架 / 核心服务 (`system`)。
  - 模块已内置静态作用域元数据 (`META-INF/xposed/scope.list`)，现代 LSPosed 载入时会自动识别。
  - *注：初次激活模块后，建议软重启（重启系统界面或手机）以使 `system_server` 注入生效。*

---

## 源码构建指南

本项目使用 Gradle Kotlin DSL 构建，支持一键免私钥编译：

### 1. 环境准备
- JDK 17
- Android SDK Platform 36
- Android Build Tools 34.0.0+

### 2. 签名配置（可选）
项目配置了自动签名检测与优雅降级机制：
- 若需正式签名发布，复制根目录下的模板文件：
  ```bash
  cp keystore.properties.example keystore.properties
  ```
  在 `keystore.properties` 中填入私钥路径与凭证。
- **免密钥回退**：若未配置 `keystore.properties`，Gradle 会自动输出警告并降级为 `debug` 签名配置，保证 CI 构建与外部开发者编译直接成功。

### 3. 编译命令
```bash
# 编译 Debug 版本
./gradlew assembleDebug

# 编译 Release 发布包
./gradlew assembleRelease
```
编译产物位于 `app/build/outputs/apk/release/app-release.apk`。

---

## 常见问题与排查 (Troubleshooting)

1. **LSPosed 提示“模块未激活”？**
   - 检查 LSPosed 管理器中是否已启用 MultiRoute 并勾选了 `system`（系统框架）作用域；
   - 确保启用后已重启设备或通过 Root 执行了 `killall system_server`。
2. **副 Wi-Fi 接口未显示？**
   - 确认设备硬件支持双 WLAN，并在系统设置中已开启“双 WLAN 加速”并连接至第二热点；
   - MultiRoute 在检测到物理接口上线后会自动加载并更新通道选项。
3. **公网出口测试超时？**
   - 前往“设置”页将“测试服务器”切换为当前网络可达的预设节点（如 ipip.net、cip.cc、icanhazip.com 或自定义测试接口）。

---

## 开源协议

本项目采用 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 协议开源。
