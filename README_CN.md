# MultiRoute

[English](README.md) | [简体中文](README_CN.md)

> 本文描述当前开发分支，包含待发布的 v1.5.0 改动。已发布构建请查看 [Releases](https://github.com/Linoleic/MultiRoute/releases)。

[![build](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml/badge.svg)](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Linoleic/MultiRoute?label=release)](https://github.com/Linoleic/MultiRoute/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20%28%20verified%20on%2016%20%2F%2017%20%29-green.svg)](https://developer.android.com)
[![LSPosed](https://img.shields.io/badge/LSPosed-module%20page-orange.svg)](https://modules.lsposed.org/module/io.github.linoleic.multiroute)
[![LibXposed API](https://img.shields.io/badge/LibXposed-min%20101%20%C2%B7%20target%20102-orange.svg)](https://github.com/libxposed)

MultiRoute 是一个 Android LSPosed 模块，提供分应用策略路由、副 Wi-Fi 连接管理和个人热点上游选择。它使用 Linux 策略路由规则及 Android 框架和 Tethering hook。不同功能对设备与 ROM 的要求不同。

> **需要 Root**（KernelSU / Magisk / APatch）与 **LSPosed**，作用域包含系统框架（`system_server`）及网络堆栈（Tethering APEX）。

---

## 概述

Android 可以同时维护多条网络链路，但许多应用默认使用系统默认网络，除非应用显式绑定网络。副 Wi-Fi 和热点上游行为还取决于 Android 版本、ROM 与设备配置。

MultiRoute 结合 Android 框架 hook 与 Linux 策略路由规则实现这些功能：

```
        ┌──────────────────────── MultiRoute 界面 ─────────────────────────┐
        │  分应用通道指派 · 双 Wi-Fi 激活与管理 · 热点出口定向 · 出口 IP 诊断 │
        └───────────────┬─────────────────────────────┬───────────────────┘
                        │                             │
        ┌───────────────▼──────────────┐   ┌──────────▼───────────────────┐
        │      框架层（LSPosed）        │   │       内核层（Root）          │
        │  注入 system_server 及网络栈 │   │     策略路由与 NAT 链         │
        │  · ConnectivityService 调度  │   │  · pref 14500 按 UID 出口     │
        │  · 双 Wi-Fi 助手与防休眠保活 │   │  · pref 14400 局域网直连放行   │
        │  · Tethering 上游网络重定向  │   │  · IPv4 nat 链 53 端口 DNS │
        │  · 场景方案与故障自动降级    │   │  · IPv4 与 IPv6 双栈策略      │
        └──────────────────────────────┘   └──────────┬───────────────────┘
                                                      │
        ┌─────────────────────────────────────────────▼───────────────────┐
        │   主 Wi-Fi    │   副 Wi-Fi (DBS)  │   移动蜂窝   │   以太网 / 其他   │
        └───────┬───────────────┬───────────────────┬─────────────┬───────┘
                │               │                   │             │
                │ 本机应用分流   │ 本机分流/热点出口  │ 本机/热点   │ 局域网直连
                ▼               ▼                   ▼             ▼
        ┌───────────────┐┌───────────────┐ ┌─────────────────┐ ┌───────────┐
        │ 浏览器 / 视频 ││ 局域网 / 投屏 │ │ 热点设备 (PC/平板)│ │ NAS / 打印│
        └───────────────┘└───────────────┘ └─────────────────┘ └───────────┘
```

### 三大核心功能维度

1. **分应用策略路由（Per-App Policy Routing）**：
   - 为每个应用独立指定网络通道（主 Wi-Fi、副 Wi-Fi、移动蜂窝或以太网），支持应用分身（如双开微信）独立走不同通道。
   - 基于 Linux 内核策略路由（`ip rule`），不使用用户态 VPN 转发；支持 IPv4 UDP/TCP 53 端口 DNS 改写、局域网直连网段放行与链路不可用时的回退。

2. **双 Wi-Fi 激活与管理（Dual Wi-Fi Management / STA + STA）**：
   - 提供多种副 Wi-Fi 连接方式，并处理 SSID/BSSID；小米设备另有息屏连接 hook。实际效果取决于设备、ROM 和 Wi-Fi 硬件。
   - 支持跨频段 SSID 处理；小米方案会启用尝试维持副链路的 hook，效果因设备、固件和 ROM 而异。

3. **个人热点上游选择（Hotspot Upstream Selection）**：
   - 请求 Android Tethering 将个人热点上游选为蜂窝网络、副 Wi-Fi 或系统默认网络。
   - 上游变更由系统异步重选；转发、NAT、IPv6 和卸载由 Android 管理，具体行为取决于系统。本项目尚未用独立热点客户端完成端到端验证。

---

## 模块信息

| 项目 | 值 |
| :-- | :-- |
| 包名 | `io.github.linoleic.multiroute` |
| 模块名 | MultiRoute |
| 官方收录 | [modules.lsposed.org/module/io.github.linoleic.multiroute](https://modules.lsposed.org/module/io.github.linoleic.multiroute) |
| Xposed API | `minApiVersion 101`、`targetApiVersion 102`（LibXposed） |
| 作用域 | 系统框架（`system` / `android`）及网络堆栈（`com.android.networkstack.tethering`、`com.google.android.networkstack.tethering`、`com.android.networkstack`、`com.google.android.networkstack`） |
| `staticScope` | `false`（作用域清单随 APK 元数据下发） |
| `autoHotReload` | `true`，但系统框架实际上无法热重载 → 需重启，或重启 `system_server` |
| Root | 必需：KernelSU / Magisk / APatch |

APK 内已包含 `META-INF/xposed/{module.prop, scope.list, java_init.list}`，作用域与入口由包自身声明，
管理器无需手工配置作用域清单。

---

## 运行环境与兼容性

| 项目 | 要求 |
| :-- | :-- |
| Root | KernelSU / Magisk / APatch（操作策略路由表需要 root） |
| Xposed 框架 | LSPosed（或实现了 LibXposed API 101+ 的框架） |
| 模块作用域 | 勾选**系统框架**（`system` / `system_server`）与 **Tethering 网络栈**（`com.android.networkstack.tethering` 等，用于热点上游调度） |
| Android | 已在 HyperOS Android **16** 与 **17** 验证；另在 Pixel 7 Pro 上对 AOSP Android **16** 做了有限的连接生命周期测试。应用 `minSdk` 为 Android 11；其他 Android 版本及设备/ROM 组合尚未验证 |

| Android 版本 | 状态 |
| :-- | :-- |
| **Android 17 / HyperOS** | ✅ 已在 Xiaomi HyperOS 手机 + KernelSU + LSPosed v2.2.0 上验证：双 WLAN 与蜂窝并发、IPv4/IPv6 规则、局域网放行、分身分流、开机恢复、模块状态、息屏保活 |
| **Android 16 / HyperOS** | ✅ 已在 Xiaomi HyperOS 平板 + KernelSU + LSPosed v2.2.0 上验证：双 WLAN 分应用分流（IPv4 + IPv6）、分身独立分流、真实流量出口、软重启后恢复、DNS 改写、模块状态。该平板无蜂窝接口，蜂窝分流未在该设备上验证 |
| **Android 16 / AOSP（Pixel 7 Pro）** | ✅ 有限验证：通过应用连接系统已保存的加密副 Wi-Fi，确认主 Wi-Fi 保持在线；验证副网 IPv4 HTTPS 出口，并在已安装 Release APK 上检查连接/断开生命周期清理。未测试未保存网络首次设置、IPv6、长时间息屏行为或更广泛的双 Wi-Fi 功能 |
| Android 11 – 15 | ⚠️ **未验证。** |
| Android 7 – 10 | ❌ APK 要求 Android 11（`minSdk` 30） |

HyperOS 行汇总的是历次构建的验证结果，不代表每项改动均完成全量验收。最新小米 15 回归覆盖
11 项连接与服务生命周期断言，以及原生副网 IPv4 HTTPS 出口；最新 Pixel 7 Pro 回归覆盖已保存网络入口
和 10 项生命周期断言。长时间息屏和独立热点客户端流量不在这两轮回归范围内。

详细命令、实测输出和验证记录仅保存在本地，不纳入 Git。上表汇总已验证范围，其余组合仍未验证。

---

## 主要特性

- **分应用通道指派**：为任意应用指定通道，其流量从该链路出口，其他应用不受影响。
- **分身独立配置**：应用分身空间（如小米 XSpace，用户 999）与工作资料会作为**独立条目**列出
  （`微信 (999)`），因此分身与主安装可以走**不同**通道。
- **分应用 DNS 改写**：被指派应用的 IPv4 传统 DNS（UDP/TCP 53 端口）会改写到所选通道的解析器。Private DNS（DoT）与 DNS-over-HTTPS（DoH）不改写；VPN 下的行为取决于其模式（见「已知限制」）。
- **场景方案**：把当前指派保存为「名称 + 触发条件」的方案（Wi-Fi 名称从当前已连接网络点选、双 Wi-Fi 在线、
  仅蜂窝、始终、仅手动）。命中时只覆盖方案涉及的应用，其余保持基础指派。多个命中方案按优先级依次叠加，
  同一应用由后叠加的方案覆盖；手动模式可同时选择多个方案并忽略触发条件。方案还能把某应用移回「系统默认」。分流规则页顶栏显示当前生效方案；方案可改名、调整顺序、
  重新编辑覆盖应用；链路变化时由模块唤醒应用重新评估，因此**应用未运行时方案同样会切换**。
- **双栈路由**：同时安装 IPv4 与 IPv6 策略路由规则。DNS 改写目前仅适用于 IPv4 UDP/TCP 53 端口。
- **局域网直连放行**：各网卡直连网段固定从属主网卡出口，内网设备（NAS、打印机、投屏）在应用被
  指派到其他通道时依然可达。
- **开机规则恢复**：`service.d` 脚本等待目标接口，并尝试应用一次路由规则；后续变化由应用和模块回调处理。此机制恢复路由规则，不会建立副 Wi-Fi 连接；时间取决于接口和 ROM 状态。
- **自动容灾回退**：被指派链路不可用时，模块可将流量改走系统默认网络，并在链路恢复后重新应用指派；切换时间取决于链路检测与防抖设置。
- **个人热点上游选择**：请求 Android Tethering 异步重选蜂窝、副 Wi-Fi 或系统默认网络。转发、NAT、IPv6 和卸载由系统管理；尚未通过独立客户端完成端到端验证。
- **热点与 VPN 通道信息**：识别热点下行与 VPN 虚拟接口并展示相关属性。热点下行接口从应用分流和场景目标中排除。VPN 保留为可选目标，分应用选择器会限制 VPN 所属 UID 与识别出的常见 VPN 客户端选择 VPN 通道；实际流量路径受 VPN 策略影响。
- **内置双 Wi-Fi 助手**：提供多种副 WLAN 连接方案、同名 SSID 跨频段处理与临时网络限制重置。在受支持的 Xiaomi ROM 上会启用尝试维持副网的 hook；效果取决于设备与 ROM。
- **通道详情**：接口、地址、网关、DNS、MTU、计费状态、SSID，以及 **Wi-Fi 频段与信道**；并可做按通道的公网出口探测（内置预设 + 自定义地址）。
- **外观与语言**：主题（跟随系统 / 浅色 / 深色，带渐变过渡）、动态取色，以及按应用语言（跟随系统 / 简体中文 / English）。
- **配置备份与恢复**：v2 JSON 包含分流、完整场景选择、网络策略、热点出口偏好、双 Wi-Fi 非敏感信息、外观与测速节点；导入可预览和按类别恢复，兼容 v1 文件。文件包含 Wi-Fi 网络名称和测速地址，不含 Wi-Fi 密码、UID 缓存或运行日志；导入不会自动开启热点或连接副 Wi-Fi，跨设备连接方案会检查厂商兼容性。
- **模块状态自检**：明确区分「hook 已就绪」「`system_server` 仍在跑旧版本」「状态记录已过期」等具体状态。
- **系统运行诊断**：诊断日志弹窗支持复制、SAF 文件导出与系统分享；涵盖双 Wi-Fi 状态、路由表与网关、上游 DNS 与 Private DNS (DoT)、规则生效状态及过滤后的 logcat。
- **不使用 VPN**：在内核层分流，没有用户态 TCP/IP 栈开销。

---

## 双 Wi-Fi 助手与机型适配

Android 可以维护多条网络链路，但许多应用默认使用活动的系统网络，除非显式绑定。副 Wi-Fi 可用性和息屏行为取决于 Wi-Fi 硬件、固件与 ROM。

MultiRoute 内建了**双 Wi-Fi 助手（STA + STA 并发管理）**，结合射频硬件能力检测、分层适配方案与特权通路，实现双 Wi-Fi 并发连接与路由分流。

### 1. 四大连接方案矩阵

| 适配方案 | 技术原理 | 适用场景与说明 |
| :--- | :--- | :--- |
| **自动 (智能分层适配)**<br>`AUTO` | 优先探测设备制造商、ROM 类型、射频芯片驱动与当前接口，自动调度适用的方案。 | **默认选项**。按设备环境向下兼容。 |
| **小米 / HyperOS 专用方案**<br>`XIAOMI_SLAVE` | 协同小米 `SlaveWifiService` / `DualStaImpl`，并使用 LSPosed hook 尝试维持副网连接。 | 小米 / Redmi / POCO ROM；行为取决于 ROM，长时间息屏表现尚未全面测试。 |
| **AOSP Multi-Internet**<br>`AOSP_OVERLAY` | 应用 Overlay 强制启用 Google 的 `config_wifiMultiStaMultiInternetConcurrencyEnabled` 资源，并请求 Multi-Internet 模式 2。 | 面向 AOSP 衍生系统的候选方案；资源名称和行为因 ROM 而异，尚未广泛验证。 |
| **Root 直连**<br>`ROOT_DIRECT` | 当前激活路径调用 `set-multi-internet-mode 2`。Root 不会绕过 Wi-Fi 硬件或固件的并发限制。 | 仅适用于支持该系统命令和模式的 ROM，尚未广泛验证。 |

AOSP / 通用 Multi-Internet 方案仅连接系统已保存的网络。首次连接某个 AP 前，请先在系统 Wi-Fi 设置中保存该网络。已保存网络使用系统配置；MultiRoute 的连接请求不携带凭据。未保存网络的提示提供打开系统 Wi-Fi 设置的入口。

### 2. 适用机型与硬件门槛

* **AOSP Multi-Internet 方案**：该方案要求 Android 13 (API 33) 或更高版本；这不是 MultiRoute 所有功能或连接方案的通用最低版本。
* **Wi-Fi 并发**：实际能力取决于 Wi-Fi 硬件、固件、系统配置和 ROM。Root 权限不能增加硬件不支持的射频并发能力。
* **验证范围**：方案表中的 ROM 家族仅为候选，不构成兼容列表。实机检查覆盖上文列出的 Xiaomi HyperOS 手机和平板，以及 Pixel 7 Pro AOSP 的有限回归；其他芯片和 ROM 尚未全面测试。

### 3. 关键特性与连接注意事项

1. **射频组合**：
   * Wi-Fi 硬件、固件与 ROM 支持的频段和信道组合各不相同；互补频段可能可用，但不能保证适用于每台设备；
   * 将列表中的「推荐并发」和「同频冲突」标签作为提示。实际并发能力取决于设备报告的组合支持。
2. **同名 Wi-Fi（SSID）跨频段隔离**：
   * 针对双频合一路由（2.4G 与 5G 广播相同 SSID），能够基于物理 MAC（BSSID）与频段精准隔离为独立节点，主副连接状态与操作按钮互不串扰。
3. **一键清理网络临时限制**：
   * 「清理网络临时限制」会清除用户禁用网络标记、重设 Root 请求许可，并移除所有通过 `cmd wifi add-request` 创建的请求，包括其他 shell 工具发起的请求。它不保证解除所有 BSSID 或固件惩罚；仅在确实需要此范围的重置时使用。

---

## 个人热点出口定向（Hotspot Upstream Steering）

Android 系统网络共享（Tethering）默认将热点客户端流量通过当前系统默认网络转发。当设备同时连接 Wi-Fi 与移动数据或启用双 Wi-Fi 时，无法在系统设置中为热点单独指定出口链路。

MultiRoute Hook NetworkStack / Tethering APEX 运行时的 `UpstreamNetworkMonitor.getCurrentPreferredUpstream`，并请求 Android Tethering 重新选择个人热点（SoftAP）的上游网络：

```
                      ┌───────────────────────┐
                      │ 笔记本 / 平板 / 随身设备 │
                      └───────────┬───────────┘
                                  │ (Wi-Fi 接入)
                      ┌───────────▼───────────┐
                      │    手机个人热点 (SoftAP) │
                      │  (ap0 / swlan0 / wlan2)│
                      └───────────┬───────────┘
                                  │
                 ┌────────────────┼────────────────┐
                 │                │                │
          ┌──────▼──────┐  ┌──────▼──────┐  ┌──────▼──────┐
          │  系统默认网络 │  │   蜂窝网络   │  │   副 Wi-Fi   │
          │   (Default)  │  │  (Cellular) │  │  (Secondary)│
          └─────────────┘  └─────────────┘  └─────────────┘
```

### 1. 功能特性
- **指定上游出口**：可将热点上游绑定至「蜂窝网络」、「副 Wi-Fi」或「系统默认网络」。
- **应用场景**：
  - **分开使用网络**：主机连接内网 Wi-Fi 时，可请求热点使用蜂窝网络；结果仍受 Android Tethering 和运营商策略影响。
  - **双 Wi-Fi 中继**：在系统支持的情况下，主 Wi-Fi 连接第一路网络、副 Wi-Fi 连接第二路网络，并请求热点使用副网作为上游。
  - **专用流量卡分流**：手机使用 Wi-Fi 时，可请求热点使用蜂窝数据；结果仍受系统和运营商策略影响。
- **异步重选**：目标变化时请求 Tethering 重新选择上游；生效时间和客户端连接取决于 Android 与设备实现。
- **故障回退与重选**：目标网络变化时模块请求重新选择；实际回退行为取决于 Tethering 可用网络及其策略。
- **系统管理转发**：转发、NAT、IPv6 和卸载由 Android 管理。Hook 已加载不代表硬件加速已生效；尚无独立热点客户端端到端验证。
- **离线预设**：可在热点未开启时设置目标；热点启动时由 Tethering 读取并评估。

---

## 安装

1. 从 [Releases](https://github.com/Linoleic/MultiRoute/releases) 安装 APK，资产名为
   `MultiRoute-<VersionCode>-<VersionName>.apk`；也可以直接在 **LSPosed 管理器的模块列表**里安装 ——
   模块已收录进[官方仓库](https://modules.lsposed.org/module/io.github.linoleic.multiroute)。
   两种方式都能在管理器内提示更新（依据模块声明的更新清单）。
2. 打开 **LSPosed 管理器 → 模块 → MultiRoute**，启用并检查作用域（勾选**系统框架**及 **Tethering 网络栈** `com.android.networkstack.tethering` 等）。APK 已内置包含上述作用域的 `scope.list` 与清单元数据，支持的管理器通常会自动选中对应作用域。
3. 重启设备，或重启系统服务：`su -c 'setprop ctl.restart zygote'`。
4. 打开 MultiRoute 并授予 root。**设置**页应显示「已激活（hook 就绪）」。若显示「已加载旧版本」，
   再重启一次系统服务。
5. 如需在连接 Wi-Fi 时同时用蜂窝分流，请开启系统/开发者选项中的「移动数据始终保持连接」。

### 从 1.1.x 升级

包名由 `com.multiroute` 改为 `io.github.linoleic.multiroute`（模块仓库只接受"作者能证明所有权"的包名
命名空间）。Android 会把它视为**另一个应用**：

1. 先卸载旧版本 —— 分流配置**不会**迁移。
2. 安装新 APK 后，在 LSPosed 管理器中启用**新包名**这个条目并设置作用域。它会作为一个新模块
   条目出现；旧的（已卸载）条目可以删掉。

`tools/probe`（用于真机测量的辅助 App）有意保留为 `com.multiroute.probe`。

### 卸载与清理

1. 若使用过热点出口选择，先在应用内切回「系统默认」；断开应用管理的副 Wi-Fi，再执行 **分流规则 → 清空所有应用分流规则**，清掉内核路由规则、生成的路由开机脚本与规则缓存。
2. 若激活过双 Wi-Fi，先恢复使用前的设备设置与 overlay，并移除本模块生成的 `/data/adb/service.d/01-multiroute-dualwifi.sh` 脚本，再在 LSPosed 管理器中停用模块并重启。清空应用分流规则不会恢复这些设备特定设置。
3. 若在卸载前应用已无法运行（例如设备被双清），可手工清理残留。以下命令不会恢复双 Wi-Fi 设置，并会按优先级整体删除 `14400`、`14500` 规则；执行前请检查是否与其他路由工具共用这些优先级：

```bash
su -c 'rm -f /data/adb/service.d/00-multiroute-restore.sh /data/adb/service.d/01-multiroute-dualwifi.sh /data/system/multiroute_rules_cache'
su -c 'while ip rule del pref 14500 2>/dev/null; do :; done; while ip rule del pref 14400 2>/dev/null; do :; done'
su -c 'while ip -6 rule del pref 14500 2>/dev/null; do :; done; while ip -6 rule del pref 14400 2>/dev/null; do :; done'
su -c 'iptables -t nat -D OUTPUT -j MULTIROUTE_DNS 2>/dev/null; iptables -t nat -F MULTIROUTE_DNS 2>/dev/null; iptables -t nat -X MULTIROUTE_DNS 2>/dev/null'
```

---

## 使用

| 页面 | 作用 |
| :-- | :-- |
| **分流规则** | 点应用选通道；长按多选批量指派；分身以独立条目列出；支持根据网络与连接条件切换的「场景方案」。 |
| **网络通道** | 查看各链路状态（接口、IP、网关、DNS、MTU、SSID、频段信道）；内嵌**双 Wi-Fi 激活与管理**与**热点出口与分流**卡片；支持热点下行与 VPN 虚拟接口识别与属性展示；支持单通道公网出口探测。 |
| **设置** | 模块与 Root 状态、内核规则统计、蜂窝常活、DNS 分流、自动容灾与防抖、配置备份还原、诊断日志（查看/分享/导出/复制）与规则清空维护。 |

---

## 工作原理

**内核层（Root）**：每个网卡在 Android 的 `netd` 中都有独立路由表，MultiRoute 安装：

- `ip rule add uidrange <uid>-<uid> lookup <iface> pref 14500` —— 每个被指派的 UID 一条，IPv4/IPv6 各一份；
- `ip rule add to <直连网段> lookup <iface> pref 14400` —— 局域网放行，网段由各通道实际的直连前缀生成。

**DNS**：MultiRoute 将被指派 UID 的 **IPv4 UDP/TCP 53** 流量改写到所选通道的解析器
（IPv4 `nat` 表的 `MULTIROUTE_DNS` 链）。IPv6 DNS、Private DNS（DoT，853）与 DoH 不改写。VPN 开启时 DNS
和应用流量的实际路径取决于 VPN 模式与策略；见「已知限制」。

**框架层（LSPosed）**：`system_server` 内的 hook 让**应用可见的网络状态**与指派结果保持一致
（`ConnectivityService.getDefaultNetworkForUid`、`getActiveNetworkForUidInternal`、
`getMobileDataPreferredUids`）；小米专用方案会启用尝试在息屏时维持副 WLAN 链路的 hook。

**开机恢复**：生成的 `service.d` 脚本最多等待 36 轮、每轮 5 秒。目标通道可用时，脚本应用一次路由规则后退出；它恢复路由规则，不会建立副 Wi-Fi 连接。后续网络变化由应用和模块回调处理。设备锁屏时脚本可能先于应用运行，但恢复时间取决于接口和 ROM 状态，不能保证开机后立即完成。

**规则缓存**：模块维护 UID→接口 映射供框架 hook 使用。缓存本身不会建立网络连接，也不能保证路由规则在开机某个固定时刻恢复。

---

## 模块状态

设置页会显示以下之一：

| 状态 | 含义 |
| :-- | :-- |
| 已激活（hook 就绪） | hook 已安装，且 `system_server` 加载的版本与已安装 APK 一致 |
| 已加载，hook 缺失 | 注入成功，但 `ConnectivityService` 的 hook 未装上 |
| 已加载旧版本 | `system_server` 仍运行旧构建 —— 需要软重启 |
| 已加载（旧版标记） | 模块在，但不上报 hook 细节（旧模块构建） |
| 状态记录已过期 | 记录里的属主进程已不是 `system_server` |
| 未激活 | 模块未启用、作用域未勾选，或未注入成功 |

---

## 常见问题

- **提示「模块未激活」**：在 LSPosed 管理器中启用并勾选模块作用域，然后重启系统服务。
- **更新后显示「已加载旧版本」**：这是预期行为——框架无法热重载。重启，或
  `su -c 'setprop ctl.restart zygote'`。
- **指派似乎没生效**：打开 **设置 → 查看诊断日志**（支持复制、导出文件或系统分享），查看「规则生效对照」一节：每个已配置 UID 都会标明「已生效 / 未生效」。某通道当时没有默认路由时会被自动跳过，日志里也会写出来。
- **重启后规则丢失**：在同一份快照的第 3 节查看开机恢复日志
  （`/data/adb/multiroute/last_boot_sync.log`），并确认该 ROM 会执行 KernelSU/Magisk/APatch 的
  `service.d` 脚本。
- **看不到副 Wi-Fi 通道**：在「网络通道 → 双 Wi-Fi 管理」中查看检测到的能力并选择可用方案。Android 13+ 是 AOSP Multi-Internet 方案的要求；其他方案的条件取决于 ROM 和设备。
- **DNS 表现不符合预期**：只改写 IPv4 UDP/TCP 53 端口。IPv6 DNS、**Private DNS（DoT）** 与 DoH 不会
  改写；VPN 下的表现也取决于其模式。
- **VPN 活跃时指派似乎无效**：见「已知限制」里的 VPN 说明。
- **Wi-Fi 频段不显示**：该链路既没有被平台上报频率，`dumpsys` 兜底也没取到。
- **公网出口测试超时**：在设置页换成当前网络可达的预设节点，或自定义探测地址。

---

## 隐私

MultiRoute 没有统计、埋点、崩溃上报或广告后端，也不需要账号。用户发起的出口探测和可选连通性检查会连接配置的目标服务器，该服务器可以看到请求来源 IP。诊断日志和导出的配置可能含有网络名称与测速地址，请自行保管和决定是否分享。依赖包括 AndroidX/Compose 与 LibXposed API。

- **本地保存**：分流配置保存在应用私有目录，内核中保存运行规则，并为 hook 保留 UID→接口 缓存。小米连接方案可能在本地读取或保存 Wi-Fi 凭据，配置导出不包含 Wi-Fi 密码；Android 系统备份或设备迁移是否复制应用数据，取决于系统设置。
- **对外请求完全透明且可控**：
  - **网络通道页的出口探测**仅由你主动触发，访问你选择的公网 IP 接口；
  - **通道离线降级中的「外网连通性校验」**（可在设置中随时开启/关闭）：仅在通道连接或恢复时，通过该通道向轻量 204 探针（如 `generate_204`）发送单次 HTTP 请求（几百字节，0 字节响应体）检验是否真正具备外网通信能力，防止处于待认证 Wi-Fi（Captive Portal）、路由器欠费或局域网无外网状态时误判通道可用。绝不进行无休止后台死循环轮询，不携带任何设备标识、应用列表或规则内容；
  - 若不运行出口探测且关闭外网连通性校验，这些可选检查不会发起请求。
- **Root 用于系统操作**：`su` 用于路由规则和开机脚本、双 Wi-Fi 与热点设置、读取系统已保存的 Wi-Fi 配置（适用方案）、释放本模块发起的连接请求以及收集系统诊断信息。
- **应用列表只读，不外传**：`QUERY_ALL_PACKAGES` 仅用于列出已安装应用（及其分身空间实例）以便指派。

---

## 已知限制

- **DNS 与 VPN 行为**：MultiRoute 只改写 IPv4 UDP/TCP 53 端口；IPv6 DNS、DoT 和 DoH 不改写。根据 VPN 模式和 UID 规则，DNS 或应用流量可能走指定链路，也可能留在隧道中。依赖特定路径时，请检查所用 VPN 的行为。
- **平台自己的按 UID 网络选择低于 MultiRoute**（`15040` 一带），所以通道分配会覆盖它。
- **热点与 VPN 目标**：热点 AP 下行接口（如 `ap0`、`swlan0`）从应用分流和场景目标中排除。VPN 虚拟接口（如 `tun0`）保留为可选目标，分应用选择器会限制 VPN 所属 UID 与识别出的常见 VPN 客户端。这些选择器检查不保证所有导入配置和场景都能避免路由回环；实际效果取决于 VPN 策略。
- **分身识别依赖 root 包列表**：界面显示数字空间号而非空间名称；仅覆盖"分身空间是真实 Android
  用户"的 ROM。
- **副 Wi-Fi 与息屏行为**：小米专用 hook 会尝试在受支持的 Xiaomi ROM 上维持副网连接。其他平台由前台服务监控连接并尝试恢复；无法保证硬件或固件在息屏或 Doze 时保持链路。长时间息屏行为尚未全面测试。
- **蜂窝分流**取决于 ROM 是否遵循 `mobile_data_preferred_uids`。
- **同一网卡所处频段会在运行中变化**——这正是「Wi-Fi 频段」那一行的用途。
- **出口探测允许明文 HTTP**：内置预设均为 HTTPS，但自定义地址可能是明文（例如路由器页面
  `http://192.168.1.1/ip`），因此 `usesCleartextTraffic` 保持开启。
- **Android 7 – 10 不支持。** APK 要求 Android 11（`minSdk` 30），这些版本缺少模块所 hook 的框架接口。
- 目前验证覆盖的机型与系统版本有限（见兼容性表），其他 ROM 可能存在差异。

---

## 源码构建

```bash
# Debug / Release APK
./gradlew assembleDebug
./gradlew assembleRelease

# 单元测试（纯逻辑）
./gradlew testDebugUnitTest
```

- 环境：JDK 17、Android SDK Platform 36；`minSdk` 30（Android 11）。
- 签名可选：复制 `keystore.properties.example` 为 `keystore.properties` 并填入凭证；未配置时会输出
  警告并降级为 debug 签名，保证 CI 与外部开发者可直接编译。
- 产物：`app/build/outputs/apk/release/app-release.apk`。
- `tools/probe` 是另一个极小的 App，用于在真机上测量按 UID 的出口，不属于模块本体。

### 版本号与发布

`versionName` 取自最近可达的 git 标签；工作区有未提交改动时会加上 `-local`。标签只是版本控制标记，并不表示对应版本已发布；例如 v1.5.0 的 Changelog 当前标记为待发布。

`versionCode` 为 `10000` 加上 `origin/master` 与 `HEAD` 可用提交数中的较大值；两者都不可用时计数默认为 `1`。本地提交数可能影响结果，fork 也不保证与上游编号相同。

发布方式是推送一个形如 **`<versionCode>-<versionName>`** 的标签——这正是 Xposed 模块仓库索引所用的格式。
[发版工作流](.github/workflows/release.yml) 会自动构建、签名、校验 APK 与 `xposed_update.json` 是否一致，
并发布为 `MultiRoute-<VersionCode>-<VersionName>.apk`。若标签与构建出的版本不符，工作流会直接失败并给出
正确的改标签命令。

需要自己的编号时可覆盖两者：

```bash
./gradlew assembleRelease -PmultiRouteVersionName=1.1.0-fork -PmultiRouteVersionCode=19999
```

注意：自建包用的是你自己的签名（与官方发布包不同），Android 会拒绝覆盖安装——请先卸载已发布的版本，或
使用同一签名密钥。

---

## 免责声明

本项目是 **root** 网络模块：会修改内核路由规则并注入系统框架，请自行评估风险。通过蜂窝传输可能产生
**运营商流量费用**，分流方式也可能与运营商条款冲突，后果由使用者自负。实验前请先确保有恢复手段
（recovery 引导 / 停用模块）。

---

## 开源协议

本项目采用 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 协议开源。

## 更新日志

见 [CHANGELOG.md](CHANGELOG.md)。

## 致谢

- [LibXposed API](https://github.com/libxposed) 与 [LSPosed](https://github.com/LSPosed/LSPosed)：
  模块所依赖的框架接口。
- Jetpack Compose 与 Material 3：界面实现。
