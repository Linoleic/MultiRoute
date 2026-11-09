# MultiRoute

[English](README.md) | [简体中文](README_CN.md)<br>
[技术架构与原理](ARCHITECTURE_CN.md) · [编译与发布](BUILDING_CN.md) · [更新日志](CHANGELOG.md)

> 可从 [GitHub Releases](https://github.com/Linoleic/MultiRoute/releases) 下载安装包，或在 **LSPosed 管理器** 的[模块仓库](https://modules.lsposed.org/module/io.github.linoleic.multiroute)直接下载与更新。

[![build](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml/badge.svg)](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Linoleic/MultiRoute?label=release)](https://github.com/Linoleic/MultiRoute/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20%28%E5%B7%B2%E9%AA%8C%E8%AF%81%2016%20%2F%2017%29-green.svg)](https://developer.android.com)
[![LSPosed](https://img.shields.io/badge/LSPosed-module%20page-orange.svg)](https://modules.lsposed.org/module/io.github.linoleic.multiroute)
[![LibXposed API](https://img.shields.io/badge/LibXposed-min%20101%20%C2%B7%20target%20102-orange.svg)](https://github.com/libxposed)

MultiRoute 是一个 Android LSPosed 模块，提供分应用策略路由（含副卡通道控制与按 SIM 分流）、副 Wi-Fi 连接管理、个人热点上游选择、通道速率监控及无线调试辅助。不同功能对设备与 ROM 的支持情况有所差异。

> **运行前提**：需要 **Root**（`KernelSU` / `Magisk` / `APatch`）与 **LSPosed**（LibXposed API 101+），作用域需包含**系统框架**（`system`）及 **NetworkStack Tethering** 组件。

---

## 功能简介

- **分应用策略路由**：通过 Linux 内核策略路由为应用分别指定 **主 Wi-Fi**、**副 Wi-Fi**、**移动蜂窝**（双卡支持按 SIM 指定）、**以太网**或**系统默认网络**，不使用用户态 VPN；各网卡直连网段保留放行，局域网设备（如 NAS、打印机、投屏）可正常访问。
- **分身独立指派与场景方案**：应用分身（如小米 XSpace `(999)`）与工作资料会作为独立条目列出。可将当前分配保存为带条件（指定 Wi-Fi、双 Wi-Fi 在线、仅蜂窝、始终、仅手动）的场景方案；命中方案按优先级叠加，网络变化时由系统唤醒重新评估。
- **双 Wi-Fi 管理 (`STA + STA`)**：在主 Wi-Fi 之外建立并切换第二路 Wi-Fi 连接，按 BSSID 与频段区分同名接入点，并显示频段并发提示标签。
- **个人热点上游选择**：请求 Android Tethering 将个人热点的上游网络选为 **蜂窝网络**、**副 Wi-Fi** 或 **系统默认网络**（例如设备连接内网 Wi-Fi 时由热点共享蜂窝网络）。
- **通道速率监控与出口探测**：查看各网卡实时下载/上传速率、近 30 秒速率曲线、窗口峰值、开机以来累计流量及 Wi-Fi 协商速率，并支持单通道公网出口 IP 探测。
- **无线调试辅助**：支持 Android 11+ 原生 TLS 模式与传统 TCP 自定义端口模式（`1024..65535`），检测已连接电脑 IP，生成 `adb connect` 连接命令，并支持可选的开机解锁后十分钟内自动恢复。

---

## 运行环境与兼容性

| 项目 | 要求 |
| :--- | :--- |
| **Root** | KernelSU / Magisk / APatch（策略路由表与底层命令需要 Root） |
| **Xposed 框架** | LSPosed 或实现 LibXposed API 101+ 的兼容框架 |
| **模块作用域** | **系统框架**（`system` / `system_server`）及 **NetworkStack Tethering** 组件 |
| **Android 版本** | Android **11**（`minSdk 30`）及以上 |

| 已验证环境 | 状态 |
| :--- | :--- |
| **Android 17 / HyperOS（手机）** | 已验证双 Wi-Fi、副卡启停与按 SIM 分流、应用与分身分流、IPv4/IPv6 路由及规则恢复 |
| **Android 16 / HyperOS（平板）** | 已验证双 Wi-Fi、应用与分身分流、IPv4/IPv6 路由、DNS 改写及规则恢复（无蜂窝硬件） |
| **Android 16 / AOSP** | 已验证已保存副 Wi-Fi 的连接、断开与 IPv4 联网；其余功能未作全面验证 |
| **Android 11 – 15** | ⚠️ 未验证 |
| **Android 7 – 10** | ❌ 不支持（APK 最低要求 Android 11 / SDK 30） |

> 硬件、固件与 ROM 对功能有直接影响：双 Wi-Fi 射频组合、双卡并发数据与热点上游行为不能仅凭 Android 版本判断兼容性。

---

## 安装与使用准备

1. **安装 APK**：从 [Releases](https://github.com/Linoleic/MultiRoute/releases) 下载 `MultiRoute-<VersionCode>-<VersionName>.apk`，或直接在 LSPosed 管理器的仓库列表（`io.github.linoleic.multiroute`）中安装；两者均通过 [`xposed_update.json`](xposed_update.json) 提供更新检查。
2. **启用模块与作用域**：进入 **LSPosed 管理器 → 模块 → MultiRoute**，启用模块并核对作用域（`system` 与 NetworkStack Tethering 包）。APK 内已包含 `scope.list`，支持的管理器通常会自动勾选。
3. **重启系统**：重启设备，或重启系统服务（`su -c 'setprop ctl.restart zygote'`）。
4. **确认激活状态**：打开 MultiRoute 并授予 Root 权限，点击顶栏**模块状态图标**，确认显示为 **「已激活（hook 就绪）」**。
   - 若需要在连接 Wi-Fi 时同时让部分应用走移动数据，请在开发者选项中开启**「移动数据始终保持连接」**。

<details>
<summary>从 1.1.x 升级说明（包名变更）</summary>

自 v1.2 起，模块包名由 `com.multiroute` 改为 `io.github.linoleic.multiroute` 以符合模块仓库包名校验要求：
1. 先卸载旧版 `1.1.x`（原有指派规则不会自动迁移）。
2. 安装新版 APK 后，在 LSPosed 管理器中启用新条目并检查作用域，再删除已卸载的旧条目。

</details>

<details>
<summary>卸载与清理</summary>

1. 先在应用内将热点上游切回**「系统默认」**，断开由应用连接的副 Wi-Fi，再执行 **分流规则 → 右上角菜单 → 清空所有应用分流规则**。
2. 若启用过双 Wi-Fi，按需要恢复系统并发设置/Overlay，并删除 `/data/adb/service.d/01-multiroute-dualwifi.sh`（若存在）。
3. 在 LSPosed 管理器中停用模块、卸载应用并重启。
- 若卸载前应用已无法运行，可参考 [手工清理命令](ARCHITECTURE_CN.md#5-手工清理命令)。

</details>

---

## 使用说明

应用包含 **分流规则**、**网络通道**、**工具** 和 **设置** 四个页面；窄屏使用底部导航，宽屏使用侧边导航栏与双栏视图：

### 1. 分流规则（App Routing）
- **单应用与分身指派**：点击任意应用（分身如 `微信 (999)` 会单独列出）选择目标通道。
- **批量指派**：长按列表项进入多选模式，可同时修改或清除多个应用的配置。
- **场景方案**：保存带有条件（*指定 Wi-Fi*、*双 Wi-Fi 在线*、*仅蜂窝*、*始终* 或 *仅手动*）的分配组合。多个匹配方案按优先级叠加；链路切换时由模块触发重算，应用未在前台时也会自动切换。
- **规则操作**：右上角菜单提供「重新应用规则」与「清空所有应用分流规则」。

### 2. 网络通道（Channels）
- **通道列表与实时速率**：查看当前链路（主 Wi-Fi、副 Wi-Fi、蜂窝、VPN、热点下行、以太网等），以及实时下载/上传速率、近 30 秒速率曲线、窗口峰值速率与开机以来累计流量。
- **通道详情**：点击通道卡片可查看接口名、IP 地址、网关、DNS、MTU、计费标识、**系统估算吞吐**，WLAN 通道还会展示 **Wi-Fi 协商速率**（物理下行/上行 Mbps）以及频段与信道。
- **公网出口探测**：手动通过指定通道向内置 HTTPS 预设或自定义地址发起公网 IP 探测。
- **副卡通道与未激活通道**：双卡设备上的非默认数据卡等通道提供快捷管理入口，可在网络通道页或工具页启停。

### 3. 工具（Tools）
- **双 Wi-Fi 助手**：
  - 连接方案默认为**自动 (`AUTO`)**：小米 / HyperOS 设备使用 `SlaveWifiService` 协同与保活 hook；Android 13+ AOSP/其他 ROM 提供 Overlay 与 Root 路径。
  - 系统 Multi-Internet 方案仅连接已在系统 Wi-Fi 设置中保存的网络，MultiRoute 不在这些请求中发送密码。同名 SSID 会按 BSSID 与频段分条显示。
- **个人热点上游**：选择 **蜂窝网络**、**副 Wi-Fi** 或 **系统默认网络**（可在热点关闭时预设），并在卡片中查看当前热点频段与上报的上游状态。转发、NAT、IPv6 与卸载由 Android Tethering 管理。
- **无线调试辅助**：在**原生 TLS 模式**（系统随机端口与配对）和**传统 TCP 模式**（自定义端口 `1024..65535`，默认 `5555`）间切换，查看已连接电脑 IP 与 `adb connect` 命令，并可按需开启解锁后开机恢复。
- **运行日志与模块状态**：顶栏状态图标用于检查 hook 就绪与版本一致性；**工具 → 运行日志** 可查看各 UID 规则生效状态、开机恢复日志，以及导出或分享诊断快照。

### 4. 设置与备份（Settings）
- 配置**自动容灾回退**（目标链路断开时回退默认网络，可选开启 HTTP 204 连通性检查）、**分应用 DNS 改写**、防抖间隔、**主题 / 动态取色**与**应用语言**（跟随系统 / 简体中文 / English）。
- **配置备份与恢复（v3 JSON）**：支持导出与导入预览及分类勾选恢复（包含应用分流、SIM 目标、场景方案、网络策略、热点偏好、双 Wi-Fi 非敏感设置、外观与测速地址），兼容 v1/v2 文件；不包含 Wi-Fi 密码、UID 缓存、运行日志与无线调试开机恢复设置。

---

## 常见问题（FAQ）

<details>
<summary><strong>模块状态显示「未激活」或更新后显示「已加载旧版本」</strong></summary>

- **未激活**：确认已在 LSPosed 管理器中启用 MultiRoute 并勾选**系统框架 (`system`)** 与 **NetworkStack Tethering** 作用域，然后重启设备。
- **已加载旧版本**：系统框架进程 `system_server` 在应用更新后无法热重载。重启设备或执行 `su -c 'setprop ctl.restart zygote'` 后再重新查看状态图标。

</details>

<details>
<summary><strong>指派规则后看起来没有生效</strong></summary>

1. 打开 **工具 → 运行日志**，检查 **「规则生效对照」** 一节，确认目标 UID 显示为**已生效**或查看被跳过的具体原因（例如接口当时没有默认路由）。
2. 连着 Wi-Fi 使用蜂窝分流时，需在开发者选项中开启**「移动数据始终保持连接」**。
3. 若同时开启了 **VPN**，实际路径可能受 VPN 自身规则影响，可参考下方 DNS 与 VPN 说明。

</details>

<details>
<summary><strong>重启手机后分流规则丢失</strong></summary>

在 **工具 → 运行日志** 中查看第 3 节（`/data/adb/multiroute/last_boot_sync.log`），并确认所用 Root 管理器（`KernelSU` / `Magisk` / `APatch`）会在该 ROM 上执行 `/data/adb/service.d/` 脚本。开机脚本只恢复路由规则，不会自动连接副 Wi-Fi。

</details>

<details>
<summary><strong>看不到副 Wi-Fi 通道或连接失败</strong></summary>

1. 在 **工具 → 双 Wi-Fi 激活与管理** 中查看检测到的能力，连接方案通常保持**自动**（AOSP Multi-Internet 方案要求 Android 13+）。若硬件或固件不支持双 STA，Root 权限无法强制开启并发。
2. 首次作为副 Wi-Fi 连接前，先在系统 Wi-Fi 设置中连接并保存该网络。
3. 若当前芯片不支持两个同频段热点同时连接，可换用互补频段的热点（例如 2.4 GHz + 5 GHz）。

</details>

<details>
<summary><strong>DNS 行为不符合预期或受 VPN 影响</strong></summary>

MultiRoute 仅重定向 **IPv4 UDP/TCP 53 端口** 请求到所选通道的 DNS；**IPv6 DNS**、**Private DNS（DoT，853 端口）** 与 **DoH** 不作改写。存在活跃 VPN（`tun0`）时，DNS 与流量的实际路径取决于 VPN 的模式和分流策略；应用内选择器会限制 VPN 所属 UID 及常见 VPN 客户端选入 VPN 通道以降低回环风险。

</details>

<details>
<summary><strong>Wi-Fi 频段不显示或公网出口测试超时</strong></summary>

- **频段不显示**：系统接口和 `dumpsys` 均未返回该接口的频点信息。
- **测试超时**：该通道可能无外网连接或无法访问当前预设节点，可在**设置**中更换可达的测试服务器。

</details>

<details>
<summary><strong>已知限制有哪些？</strong></summary>

- 在内核层处理 IPv4/IPv6 双栈策略路由且不经过用户态 VPN，但 DNS 改写目前仅支持 IPv4 53 端口。
- 分身应用列表依赖 Root 查询包列表，界面显示数字空间号（如 `(999)`），仅覆盖以真实 Android 多用户实现分身的 ROM。
- 副卡通道控制要求系统存在两张已激活 SIM，仅控制非默认数据卡，不会修改默认数据卡或开机自动启用；实际并发能力取决于硬件与运营商，按 SIM 分流依赖 ROM 对 `mobile_data_preferred_uids` 的支持。
- 除受支持的小米专项 hook 外，其余系统的副 Wi-Fi 息屏重连依赖前台服务监控，硬件或系统 Doze 策略仍可能断开副路连接。

</details>

---

## 相关文档

- [技术架构与原理](ARCHITECTURE_CN.md)（[English](ARCHITECTURE.md)）
- [编译与发布指南](BUILDING_CN.md)（[English](BUILDING.md)）
- [更新日志](CHANGELOG.md)

## 免责声明

这是一个 **root** 网络模块，会修改内核路由规则并注入系统框架，请自行评估使用风险。通过蜂窝网络传输或热点分享数据可能产生运营商流量费用，或与运营商服务条款存在冲突，后果由使用者自行承担。测试前请保留恢复方式（如进入恢复模式或停用模块）。

## 隐私

- **无统计收集**：不包含统计、埋点、崩溃上报或广告后端，不需要账号登录。
- **本地存储与应用列表**：配置保存在应用私有目录（导出备份不包含 Wi-Fi 密码、运行日志与无线调试开机恢复设置）；`QUERY_ALL_PACKAGES` 仅用于在本地列出已安装应用与分身条目以便指派。
- **可控的对外请求**：公网出口探测仅在用户手动触发时请求目标服务器；自动容灾回退的可选「外网连通性校验」仅在通道连接或恢复时发送单次 HTTP 204 请求（无持续后台轮询，不携带设备标识、应用列表或规则），关闭后不会发起这些请求。
- **Root 权限用途**：`su` 仅用于安装路由/DNS 规则、生成开机脚本、控制双 Wi-Fi / 热点 / 无线调试、读取接口流量统计及采集系统诊断信息（详见[技术架构与原理](ARCHITECTURE_CN.md#隐私与数据流向)）。

## 开源协议与致谢

本项目基于 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 开源。依赖 [LibXposed API](https://github.com/libxposed)、[LSPosed](https://github.com/LSPosed/LSPosed)、Jetpack Compose 与 Material 3。
