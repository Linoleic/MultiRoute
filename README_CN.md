# MultiRoute

[English](README.md) | [简体中文](README_CN.md)

[![build](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml/badge.svg)](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Linoleic/MultiRoute?label=release)](https://github.com/Linoleic/MultiRoute/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20%28%20verified%20on%2016%20%2F%2017%20%29-green.svg)](https://developer.android.com)
[![LSPosed](https://img.shields.io/badge/LSPosed-module%20page-orange.svg)](https://modules.lsposed.org/module/io.github.linoleic.multiroute)
[![LibXposed API](https://img.shields.io/badge/LibXposed-min%20101%20%C2%B7%20target%20102-orange.svg)](https://github.com/libxposed)

**一个面向现代 Android 的多网并发与分流管理模块（LSPosed）。** 突破系统单一默认网络的限制，集成**分应用策略路由**、**双 Wi-Fi（STA + STA 并发）深度管理**与**个人热点（SoftAP）出口定向**三大核心能力。通过 Linux 内核策略路由（`ip rule`）与 Android 系统框架 / NetworkStack 深度协同，实现各应用独立走指定网络链路并发通信，解除系统对副 Wi-Fi 的连接限制与息屏休眠策略，并支持将个人热点下挂流量绑定至指定出口。

> **需要 Root**（KernelSU / Magisk / APatch）与 **LSPosed**，作用域包含系统框架（`system_server`）及网络堆栈（Tethering APEX）。

---

## 概述

Android 系统的原生网络栈采用「单一默认网络」的排他模型：即使设备同时连接主副双 Wi-Fi 与蜂窝数据，未显式调用底层绑定 API 的应用仅能走系统当前唯一的默认网络；同时，系统限制副 Wi-Fi 仅能用于厂商特定加速，并在屏幕关闭时将其拆除；此外，个人热点（Tethering）流量也强制与系统主网络绑定，无法自由选择出口链路。

MultiRoute 从系统框架与内核层对这些限制进行了系统化重构：

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
        │  · Tethering 上游网络重定向  │   │  · nat 链 UDP/TCP 53 DNS 改写 │
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
   - 完全基于 Linux 内核策略路由（`ip rule`），无任何用户态 VPN 转发开销；支持 DNS 随通道自动改写、局域网直连网段放行与通道离线自动降级保活。

2. **双 Wi-Fi 激活与管理（Dual Wi-Fi Management / STA + STA）**：
   - 充分利用现代芯片的 DBS（双频并发）双射频硬件，内建四大连接方案（智能自动、小米/HyperOS 专用、AOSP 原生多互联网 Overlay、通用 Root 直连）。
   - 解除系统对副 Wi-Fi 的连接限制与厂商白名单控制，实现同名 SSID 跨频段独立隔离呈现、信道互斥冲突规避与息屏防断开保活。

3. **个人热点出口定向（Hotspot Upstream Steering）**：
   - 突破 Android 热点必须随系统默认网络出口转发的机制，支持将个人热点流量指定从蜂窝移动数据、副 Wi-Fi 或系统默认网络出网。
   - 注入 NetworkStack Tethering 调度状态机，支持免重启热点的动态切换、出口离线回退及恢复后重选；转发和卸载仍由系统管理。

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
| Android | 已在 **16** 与 **17**（HyperOS）验证。模块所 hook 的接口——例如 `getMobileDataPreferredUids`——自 Android 11 起才存在，因此 11 是现实下限；更低版本不在支持范围内 |

| Android 版本 | 状态 |
| :-- | :-- |
| **Android 17 / HyperOS** | ✅ 已在 Xiaomi HyperOS 手机 + KernelSU + LSPosed v2.2.0 上验证：双 WLAN 与蜂窝并发、IPv4/IPv6 规则、局域网放行、分身分流、开机恢复、模块状态、息屏保活 |
| **Android 16 / HyperOS** | ✅ 已在 Xiaomi HyperOS 平板 + KernelSU + LSPosed v2.2.0 上验证：双 WLAN 分应用分流（IPv4 + IPv6）、分身独立分流、真实流量出口、软重启后恢复、DNS 改写、模块状态。该平板无蜂窝接口，蜂窝分流未在该设备上验证 |
| Android 11 – 15 | ⚠️ **未验证。** 模块 hook 的接口自 Android 11 起存在，因此理论可行，但没有任何实测——请当作"未知"，而不是"支持" |
| Android 7 – 10 | ❌ **不支持。** APK 要求 Android 11（`minSdk` 30），且这些版本缺少模块所 hook 的框架接口 |

详细命令、实测输出和验证记录仅保存在本地，不纳入 Git。上表汇总已验证范围，其余组合仍未验证。

---

## 主要特性

- **分应用通道指派**：为任意应用指定通道，其流量从该链路出口，其他应用不受影响。
- **分身独立配置**：应用分身空间（如小米 XSpace，用户 999）与工作资料会作为**独立条目**列出
  （`微信 (999)`），因此分身与主安装可以走**不同**通道。
- **DNS 随通道走**：被指派应用的域名查询会被改写到该通道自己的解析器，不会停留在默认网络
  （也不会留在 VPN 隧道里）。
- **场景方案**：把当前指派保存为「名称 + 触发条件」的方案（Wi-Fi 名称从当前已连接网络点选、双 Wi-Fi 在线、
  仅蜂窝、始终、仅手动）。命中时只覆盖方案涉及的应用，其余保持默认指派；多个方案可共存并按优先级取第一个，
  也可手动指定方案；方案还能把某应用移回「系统默认」。分流规则页顶栏显示当前生效方案；方案可改名、调整顺序、
  重新编辑覆盖应用；链路变化时由模块唤醒应用重新评估，因此**应用未运行时方案同样会切换**。
- **双栈下发**：IPv4 与 IPv6 规则同时安装。
- **局域网直连放行**：各网卡直连网段固定从属主网卡出口，内网设备（NAS、打印机、投屏）在应用被
  指派到其他通道时依然可达。
- **开机自动恢复**：规则跨重启保留——`service.d` 脚本、模块发起的唤醒广播、应用自身的网络回调
  三重互补；重启后在锁屏状态即可由 root 脚本恢复生效。
- **自动容灾回退**：被指派的出口链路离线时自动借道系统默认网络保活，链路恢复后自动迁回，支持自定义防抖缓冲时间与轻量外网探测校验。
- **个人热点出口定向与分流**：支持将个人热点（SoftAP / Tethering）流量指定从特定网络出口（蜂窝网络、副 Wi-Fi 或系统默认网络）转发。通过 Hook NetworkStack / Tethering 调度逻辑，实现动态重定向与故障降级回退；支持在未开启热点时预先设定目标出口。
- **通道类型识别与分流列表隔离**：识别个人热点下行接口（如 `ap0`、`swlan0` 等）与 VPN 虚拟接口（如 `tun0`），并将其从应用分流与场景方案的候选目标中排除，避免应用被指派至下行或虚拟接口引发通信异常；在通道详情中展示热点状态与 VPN 属性（创建应用、隧道内网 IP 及底层承载网络）。
- **内置双 Wi-Fi 助手**：支持副 WLAN 独立连接，包含自动探测、小米专用、AOSP Overlay 与 Root 直连方案；支持同名 SSID 跨频段隔离与异常惩罚清除。小米专用方案连接时自动联动后台守护维持链路。
- **通道详情**：接口、地址、网关、DNS、MTU、计费状态、SSID，以及 **Wi-Fi 频段与信道**；并可做按通道的公网出口探测（内置预设 + 自定义地址）。
- **外观与语言**：主题（跟随系统 / 浅色 / 深色，带渐变过渡）、动态取色，以及按应用语言（跟随系统 / 简体中文 / English）。
- **配置备份与恢复**：v2 JSON 包含分流、完整场景选择、网络策略、热点出口偏好、双 Wi-Fi 非敏感信息、外观与测速节点；导入可预览和按类别恢复，兼容 v1 文件。文件包含 Wi-Fi 网络名称和测速地址，不含 Wi-Fi 密码、UID 缓存或运行日志；导入不会自动开启热点或连接副 Wi-Fi，跨设备连接方案会检查厂商兼容性。
- **模块状态自检**：明确区分「hook 已就绪」「`system_server` 仍在跑旧版本」「状态记录已过期」等具体状态。
- **系统运行诊断**：诊断日志弹窗支持复制、SAF 文件导出与系统分享；涵盖双 Wi-Fi 状态、路由表与网关、上游 DNS 与 Private DNS (DoT)、规则生效状态及过滤后的 logcat。
- **不使用 VPN**：在内核层分流，没有用户态 TCP/IP 栈开销。

---

## 双 Wi-Fi 助手与机型适配

在 Android 系统中，操作系统默认仅保留单一活动默认互联网出口。虽然部分设备具备 **DBS（Dual-Band Simultaneous，双频并发）** 双射频硬件，但定制系统通常将副 Wi-Fi 接口限制在厂商指定功能内，或在屏幕熄灭时断开副链路。

MultiRoute 内建了**双 Wi-Fi 助手（STA + STA 并发管理）**，结合射频硬件能力检测、分层适配方案与特权通路，实现双 Wi-Fi 并发连接与路由分流。

### 1. 四大连接方案矩阵

| 适配方案 | 技术原理 | 适用场景与说明 |
| :--- | :--- | :--- |
| **自动 (智能分层适配)**<br>`AUTO` | 优先探测设备制造商、ROM 类型、射频芯片驱动与当前接口，自动调度适用的方案。 | **默认选项**。按设备环境向下兼容。 |
| **小米 / HyperOS 专用方案**<br>`XIAOMI_SLAVE` | 协同小米原厂 `SlaveWifiService` / `DualStaImpl` 驱动下发双 WLAN 连接，并联动 LSPosed 注入防休眠自动断开守护。 | 小米 / Redmi / POCO 官方系统，息屏保持连接。 |
| **AOSP / Pixel 标准多互联网方案**<br>`AOSP_OVERLAY` | 基于 Android 13+ AOSP 原生 Multi-Internet 模式（RRO Overlay 开启 `config_wifiMultiStaMultiInternetConcurrencyEnabled`，工作模式切至模式 2）。 | 类原生系统（LineageOS, crDroid, PixelOS 等）或标准 AOSP 框架设备。 |
| **通用 Root 强制直连方案**<br>`ROOT_DIRECT` | 借助 Root（UID 0）与系统特权（`SYSTEM_UID`）直接向底层 Wi-Fi 栈下发连接请求，绕过 Framework Overlay 限制与系统确认弹窗。 | 适用于 OPPO/一加 (ColorOS)、vivo/iQOO (OriginOS)、三星 (One UI) 及其他定制 ROM。 |

AOSP / 通用 Multi-Internet 方案仅连接系统已保存的网络。首次连接某个 AP 前，请先在系统 Wi-Fi 设置中保存该网络；小米方案沿用原有机制。
已保存网络使用系统配置完成认证，无需在 MultiRoute 中提供密码；未保存网络的提示提供打开系统 Wi-Fi 设置的入口。

### 2. 适用机型与硬件门槛

* **系统版本基准**：
  * **Android 13 (API 33) 及以上**（Android 13 开始在 AOSP Wi-Fi 栈中正式原生支持多互联网 ClientModeManager 独立调度与指令集）。
  * 设备需具备 **Root 权限**（KernelSU / APatch / Magisk）。
* **硬件射频要求（必须支持双 STA / DBS 并发）**：
  * **高通骁龙（Qualcomm FastConnect 6700 / 6800 / 6900 / 7800 / 7900 等）**：
    * 旗舰平台：骁龙 888 / 8 Gen 1 / 8+ Gen 1 / 8 Gen 2 / 8 Gen 3 / 8 Elite 等；
    * 次旗舰与中高端：骁龙 7+ Gen 2 / 7+ Gen 3 / 8s Gen 3 / 7 Gen 3 等。
  * **联发科天玑（MediaTek Filogic）**：
    * 天玑 8100 / 8200 / 8300 / 9000 / 9200 / 9300 / 9400 等支持双 Wi-Fi 加速的机型。
  * **Google Tensor 平台**：
    * Pixel 6 / 7 / 8 / 9 系列（配备博通 BCM4389 / BCM4398 等 DBS 芯片）。
  * **三星 Exynos 旗舰平台**：
    * Exynos 2100 / 2200 / 2400 等（搭配双射频芯片的 Galaxy 旗舰）。
* **不支持的情况**：
  * 硬件仅配备单射频/单天线架构的百元机或老旧芯片（如早期骁龙 4 系、6 系、低端联发科/紫光展锐）；
  * Android 12 及更早版本（底层缺少多互联网独立路由框架）。

### 3. 关键特性与连接注意事项

1. **射频频段互补规则**：
   * 手机芯片的双频并发（DBS）通常由两个独立的射频硬件通路支撑，因此两路 Wi-Fi 必须工作在**互补频段**（如 2.4GHz + 5GHz，或 5G 低频 5.2GHz + 5G 高频 5.8GHz 信道隔离）；
   * 若尝试在同一物理信道/频段上并发连接两路 Wi-Fi，会导致硬件射频争用而连接失败。扫描列表中会自动标识「推荐并发」与「同频冲突」。
2. **同名 Wi-Fi（SSID）跨频段隔离**：
   * 针对双频合一路由（2.4G 与 5G 广播相同 SSID），能够基于物理 MAC（BSSID）与频段精准隔离为独立节点，主副连接状态与操作按钮互不串扰。
3. **一键清理网络临时限制**：
   * Android 系统在密码输入错误或多次握手失败后，会将对应 Wi-Fi 加入底层的临时禁连黑名单（BSSID Blocklist）。点击卡片中的「清理网络临时限制」可一键重置惩罚状态，恢复快速直连。

---

## 个人热点出口定向（Hotspot Upstream Steering）

Android 系统网络共享（Tethering）默认将热点客户端流量通过当前系统默认网络转发。当设备同时连接 Wi-Fi 与移动数据或启用双 Wi-Fi 时，无法在系统设置中为热点单独指定出口链路。

MultiRoute 通过 Hook NetworkStack / Tethering APEX 运行时的 `UpstreamNetworkMonitor.getCurrentPreferredUpstream`，支持将个人热点（SoftAP）的下行流量定向至指定网络出口：

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
  - **网络隔离与流量分流**：手机主网络连接办公局域网 Wi-Fi，热点使用 5G 移动蜂窝网络供外置设备使用，避免个人流量进入企业内网。
  - **双 Wi-Fi 中继**：手机主 Wi-Fi 连接第一路网络，副 Wi-Fi 连接第二路网络，热点绑定副 Wi-Fi 进行中继转发。
  - **专用流量卡分流**：将移动数据专供热点客户端使用，手机前台保留使用 Wi-Fi。
- **动态重定向**：切换出口配置后即时生效，无需重启热点服务或重新连接客户端。
- **故障降级回退**：若指定的出口接口离线，系统自动回退至其他可用网络连接；指定接口重新就绪后切回。
- **原生转发与卸载**：经由 Tethering 内部选路调度，NAT 和卸载由系统管理；Hook 已加载不代表硬件加速已生效。
- **离线预设**：在未开启个人热点时即可预先设定目标出口，热点启动后按该配置生效。

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

1. 在应用内执行 **分流规则 → 清空所有应用分流规则**：一次清掉内核规则、生成的开机脚本与规则缓存。
2. 在 LSPosed 管理器中停用模块并重启。
3. 若在卸载前应用已无法运行（例如设备被双清），可手工清理残留：

```bash
su -c 'rm -f /data/adb/service.d/00-multiroute-restore.sh /data/system/multiroute_rules_cache'
su -c 'while ip rule del pref 14500 2>/dev/null; do :; done; while ip rule del pref 14400 2>/dev/null; do :; done'
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

**DNS**：Android 是按**默认网络**选择解析器的，与内核把报文路由到哪里无关；因此被指派的应用可能去查一个
只在另一条链路上可达的解析器，或者继续查 VPN 隧道内的解析器。MultiRoute 于是把被指派 UID 的
**UDP/TCP 53** 改写到该通道自己的解析器（`nat` 表里专用的 `MULTIROUTE_DNS` 链）。**853（DoT）刻意不改写**，
DoH 本身就是普通 HTTPS、天然跟随路由。开机脚本会清空该链，应用运行后再用**当前网络**的解析器补上，
因此不会出现"用上一次的旧解析器把解析弄坏"的情况。

**框架层（LSPosed）**：`system_server` 内的 hook 让**应用可见的网络状态**与指派结果保持一致
（`ConnectivityService.getDefaultNetworkForUid`、`getActiveNetworkForUidInternal`、
`getMobileDataPreferredUids`）；在双 Wi-Fi 助手使用小米专用方案连接时，联动注入守护维持副 WLAN 链路避免息屏断开。

**开机恢复**：规则在内核里，重启后必须重建。三重机制互补：生成的 `service.d` 脚本（接口就绪即应用）、
模块向应用发送的唤醒广播、以及应用自身的网络回调。注意：若重启后设备仍处于**锁屏未解锁**状态，应用
根本无法运行——其凭据加密存储不可访问，广播在首次解锁前也不会送达——此时**只有 root 的 `service.d`
脚本能恢复规则**。因此它被当作主要机制，而不是备用方案。

**规则缓存**：应用尚未启动时，LSPosed 的远程共享配置读不到。因此应用会把 UID→接口 映射写入缓存，
让 hook 从开机第一秒起就知道规则。

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
- **看不到副 Wi-Fi 通道**：需硬件支持双 Wi-Fi（DBS 架构）且运行 Android 13+。可在「网络通道 → 双 Wi-Fi 激活与分层设置」中检查硬件能力，并选择合适的适配方案（定制系统可选用通用 Root 直连方案）。
- **DNS 表现不符合预期**：只改写 53 端口。若你使用 **Private DNS（DoT）** 或 DoH，这部分流量不会被
  改写，处于 VPN 时仍可能从隧道解析。
- **VPN 活跃时指派似乎无效**：见「已知限制」里的 VPN 说明。
- **Wi-Fi 频段不显示**：该链路既没有被平台上报频率，`dumpsys` 兜底也没取到。
- **公网出口测试超时**：在设置页换成当前网络可达的预设节点，或自定义探测地址。

---

## 隐私

MultiRoute 不收集、不上传任何数据：没有统计、没有埋点、没有崩溃上报、没有广告、也没有账号。
**你的 IP 地址、设备标识、应用列表与分流规则都不会被上传到任何地方。** 依赖清单只有
AndroidX/Compose 与 LibXposed API，仅此而已。

- **数据都留在设备上**：分流规则保存在应用私有配置与内核规则里，仅为 hook 额外保留一份 UID→接口 的小
  缓存。
- **对外请求完全透明且可控**：
  - **网络通道页的出口探测**仅由你主动触发，访问你选择的公网 IP 接口；
  - **通道离线降级中的「外网连通性校验」**（可在设置中随时开启/关闭）：仅在通道连接或恢复时，通过该通道向轻量 204 探针（如 `generate_204`）发送单次 HTTP 请求（几百字节，0 字节响应体）检验是否真正具备外网通信能力，防止处于待认证 Wi-Fi（Captive Portal）、路由器欠费或局域网无外网状态时误判通道可用。绝不进行无休止后台死循环轮询，不携带任何设备标识、应用列表或规则内容；
  - 若你从不运行出口探测且关闭外网连通性校验，应用在运行中**不会产生任何网络请求**。
- **Root 权限只用于路由**：`su` 仅用于增删策略路由规则、写入开机恢复脚本，以及读取诊断里展示的解析器
  与开机日志信息，不会替任何人执行操作，也不会回传任何结果。
- **应用列表只读，不外传**：`QUERY_ALL_PACKAGES` 仅用于列出已安装应用（及其分身空间实例）以便指派。

---

## 已知限制

- **只改写 53 端口**：传统 DNS 会跟随被指派的通道；Private DNS（DoT，853）与 DoH 不会被改写。全流量
  VPN 场景下的实测结论是：数据流量已走出隧道，而域名解析可能仍留在隧道内。
- **VPN 的影响取决于其工作模式**：真机实测（全流量隧道客户端）显示，客户端的**按 UID 捕获规则在
  `24000`**，低于 MultiRoute 的 `14400`/`14500` → 被指派的应用会**离开隧道**（实测其连接改用被指派网卡的
  地址）。而 always-on VPN 且开启"阻止无 VPN 连接"时，Android 改用 `13000`/`14000` 规则，**高于**
  MultiRoute → 此时 VPN 优先、指派不生效。注意：VPN 活跃时指派应用，等于把该应用**移出隧道**。
- **平台自己的按 UID 网络选择低于 MultiRoute**（`15040` 一带），所以通道分配会覆盖它。
- **热点下行与 VPN 接口分流隔离**：个人热点 AP 下行接口（如 `ap0`、`swlan0` 等）与 VPN 虚拟接口（如 `tun0`）会在「网络通道」中列出并支持出口探测，但已被排除在应用分流及场景方案的目标通道之外，避免因将应用绑定至下行或虚拟接口导致通信中断或路由冲突。
- **分身识别依赖 root 包列表**：界面显示数字空间号而非空间名称；仅覆盖"分身空间是真实 Android
  用户"的 ROM。
- **副 Wi-Fi 息屏保活与多机型适配**：原先的保活开关已整合进**双 Wi-Fi 助手**。当使用「小米 / HyperOS 专用方案」连接副 Wi-Fi 时，模块会自动在后台联动并激活针对小米私有类（`SlaveWifiService`、`DualStaImpl`）的防断开守护 hook，无需在设置中手动切换；而在其他机型（AOSP、ColorOS、OriginOS、One UI 等）上，双 Wi-Fi 助手通过原生多网络架构（`AOSP_OVERLAY`）或 Root 特权直连（`ROOT_DIRECT`）配合前台服务保障链路稳定。
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

# 单元测试（78 个，仅纯逻辑）
./gradlew testDebugUnitTest
```

- 环境：JDK 17、Android SDK Platform 36；`minSdk` 30（Android 11）。
- 签名可选：复制 `keystore.properties.example` 为 `keystore.properties` 并填入凭证；未配置时会输出
  警告并降级为 debug 签名，保证 CI 与外部开发者可直接编译。
- 产物：`app/build/outputs/apk/release/app-release.apk`。
- `tools/probe` 是另一个极小的 App，用于在真机上测量按 UID 的出口，不属于模块本体。

### 版本号与发布

`versionName` 取自最近的 git 标签（工作区有未提交改动时标记 `-local`）；`versionCode` = **上游分支的
提交数 + 固定偏移**（`10000`）。统计的是 `origin/master` 而非本地 HEAD，因此 CI、全新克隆、以及多了
若干本地提交的 fork，在同一上游状态下**得到同一个版本号**。

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
