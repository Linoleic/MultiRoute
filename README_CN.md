# MultiRoute

[English](README.md) | [简体中文](README_CN.md)

[![build](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml/badge.svg)](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Linoleic/MultiRoute?label=release)](https://github.com/Linoleic/MultiRoute/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20%28%20verified%20on%2016%20%2F%2017%20%29-green.svg)](https://developer.android.com)
[![LibXposed API](https://img.shields.io/badge/LibXposed-min%20101%20%C2%B7%20target%20102-orange.svg)](https://github.com/libxposed)

**一个 LSPosed 模块：让每个应用走各自的网络通道。** 把应用分别指派到主 Wi-Fi、副 Wi-Fi（双 WLAN）、
移动蜂窝或有线以太网，让它们**同时**通信。出口通过内核策略路由（`ip rule`）落实，而不是走 VPN。

> **需要 Root**（KernelSU / Magisk / APatch）与 **LSPosed**，作用域为**系统框架**（`system_server`）。

---

## 概述

Android 的网络栈是「单一默认网络」的排他模型：即使同时连着双 WLAN 与蜂窝，未主动调用底层绑定
API 的应用也只能走系统当前的那一个默认网络。

MultiRoute 按应用解开这个限制：

```
        ┌──────────────────────── MultiRoute 界面 ─────────────────────────┐
        │    分应用通道指派 · 多选批量配置 · 出口 IP 诊断                   │
        └───────────────┬─────────────────────────────┬───────────────────┘
                        │                             │
        ┌───────────────▼──────────────┐   ┌──────────▼───────────────────┐
        │      框架层（LSPosed）        │   │       内核层（Root）          │
        │      注入 system_server      │   │     策略路由表                │
        │  · ConnectivityService       │   │  · pref 14500 按 UID 出口     │
        │  · 小米副 WLAN 息屏保活        │   │  · pref 14400 局域网直连放行   │
        │                              │   │  · nat 链 DNS 改写           │
        │                              │   │  · IPv4 与 IPv6              │
        └──────────────────────────────┘   └──────────┬───────────────────┘
                                                      │
        ┌─────────────────────────────────────────────▼───────────────────┐
        │     主 Wi-Fi   │   副 Wi-Fi   │   移动蜂窝   │    以太网          │
        └─────────────────────────────────────────────────────────────────┘
```

---

## 模块信息

| 项目 | 值 |
| :-- | :-- |
| 包名 | `io.github.linoleic.multiroute` |
| 模块名 | MultiRoute |
| Xposed API | `minApiVersion 101`、`targetApiVersion 102`（LibXposed） |
| 作用域 | `system` —— **仅**系统框架（`system_server`） |
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
| 模块作用域 | **仅勾选系统框架**（`system` / `system_server`） |
| Android | 已在 **16** 与 **17**（HyperOS）验证。模块所 hook 的接口——例如 `getMobileDataPreferredUids`——自 Android 11 起才存在，因此 11 是现实下限；更低版本不在支持范围内 |

| Android 版本 | 状态 |
| :-- | :-- |
| **Android 17 / HyperOS** | ✅ 已在 Xiaomi HyperOS 手机 + KernelSU + LSPosed v2.2.0 上验证：双 WLAN 与蜂窝并发、IPv4/IPv6 规则、局域网放行、分身分流、开机恢复、模块状态、息屏保活 |
| **Android 16 / HyperOS** | ✅ 已在 Xiaomi HyperOS 平板 + KernelSU + LSPosed v2.2.0 上验证：双 WLAN 分应用分流（IPv4 + IPv6）、分身独立分流、真实流量出口、软重启后恢复、DNS 改写、模块状态。该平板无蜂窝接口，蜂窝分流未在该设备上验证 |
| Android 11 – 15 | ⚠️ **未验证。** 模块 hook 的接口自 Android 11 起存在，因此理论可行，但没有任何实测——请当作"未知"，而不是"支持" |
| Android 7 – 10 | ❌ **不支持。** APK 要求 Android 11（`minSdk` 30），且这些版本缺少模块所 hook 的框架接口 |

上表所依据的真机证据（使用的命令与实测输出，以及**尚未验证**的部分）整理在
[docs/VERIFICATION.md](docs/VERIFICATION.md)。

---

## 主要特性

- **分应用通道指派**：为任意应用指定通道，其流量从该链路出口，其他应用不受影响。
- **分身独立配置**：应用分身空间（如小米 XSpace，用户 999）与工作资料会作为**独立条目**列出
  （`微信 (999)`），因此分身与主安装可以走**不同**通道。
- **DNS 随通道走**：被指派应用的域名查询会被改写到该通道自己的解析器，不会停留在默认网络
  （也不会留在 VPN 隧道里）。
- **双栈下发**：IPv4 与 IPv6 规则同时安装。
- **局域网直连放行**：各网卡直连网段固定从属主网卡出口，内网设备（NAS、打印机、投屏）在应用被
  指派到其他通道时依然可达。
- **开机自动恢复**：规则跨重启保留——`service.d` 脚本、模块发起的唤醒广播、应用自身的网络回调
  三重互补。
- **副 Wi-Fi 息屏保活**：可选；防止 OEM 省电策略在息屏后拆掉副 WLAN 链路（小米双 WLAN）。
- **通道详情**：接口、地址、网关、DNS、MTU、计费状态、SSID，以及 **Wi-Fi 频段与信道**；并可做
  按通道的公网出口探测（内置预设 + 自定义地址）。
- **外观与语言**：主题（跟随系统 / 浅色 / 深色，带渐变过渡）、动态取色，以及按应用语言
  （跟随系统 / 简体中文 / English）。
- **精准模块状态**：明确区分「hook 已就绪」「`system_server` 仍在跑旧版本」「状态记录已过期」等，
  而不是只用一句"已激活"糊过去。
- **可直接转交的诊断**：一键复制的诊断快照，含 Root 与模块状态、Wi-Fi 映射、**规则生效对照**
  （配置了什么 vs 内核里实际有什么）、开机恢复日志与近期内核规则。
- **不使用 VPN**：在内核层分流，没有用户态 TCP/IP 栈，吞吐与时延接近原生。

---

## 安装

1. 从 [Releases](https://github.com/Linoleic/MultiRoute/releases) 安装 APK，资产名为
   `MultiRoute-<VersionCode>-<VersionName>.apk`。模块已声明更新清单，管理器可直接提示更新。
2. 打开 **LSPosed 管理器 → 模块 → MultiRoute**，启用并将作用域设为**系统框架**（`system`）。
   APK 已内置 `META-INF/xposed/scope.list`。
3. 重启设备，或重启系统服务：`su -c 'setprop ctl.restart zygote'`。
4. 打开 MultiRoute 并授予 root。**设置**页应显示「已激活（hook 就绪）」。若显示「已加载旧版本」，
   再重启一次系统服务。
5. 如需在连接 Wi-Fi 时同时用蜂窝分流，请开启系统/开发者选项中的「移动数据始终保持连接」。

### 从 1.1.x 升级

包名由 `com.multiroute` 改为 `io.github.linoleic.multiroute`（模块仓库只接受"作者能证明所有权"的包名
命名空间）。Android 会把它视为**另一个应用**：

1. 先卸载旧版本 —— 分流配置**不会**迁移。
2. 安装新 APK 后，在 LSPosed 管理器中启用**新包名**这个条目并把作用域设为系统框架。它会作为一个新模块
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
| **分流规则** | 点应用选通道；长按进入多选批量指派。分身会以独立条目出现并带空间号。 |
| **网络通道** | 查看各条链路的接口、IP、网关、DNS、MTU、SSID、**Wi-Fi 频段与信道**，并按通道做公网出口探测。 |
| **设置** | 模块状态、Root 状态、当前内核规则、外观与语言，以及保活开关。**复制诊断日志**会把完整快照放进剪贴板。 |

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
`getMobileDataPreferredUids`）；在小米 ROM 上还负责息屏时维持副 WLAN 链路。

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
| 未激活 | 模块未启用、作用域未选系统框架，或未注入成功 |

---

## 常见问题

- **提示「模块未激活」**：在 LSPosed 管理器中启用并勾选**系统框架**，然后重启或重启系统服务。
- **更新后显示「已加载旧版本」**：这是预期行为——框架无法热重载。重启，或
  `su -c 'setprop ctl.restart zygote'`。
- **指派似乎没生效**：打开 **设置 → 复制诊断日志**，看「规则生效对照」一节：每个已配置 UID 都会
  标明「已生效 / 未生效」。某通道当时没有默认路由时会被自动跳过，日志里也会写出来。
- **重启后规则丢失**：在同一份快照的第 3 节查看开机恢复日志
  （`/data/adb/multiroute/last_boot_sync.log`），并确认该 ROM 会执行 KernelSU/Magisk/APatch 的
  `service.d` 脚本。
- **看不到副 Wi-Fi 通道**：需硬件支持双 WLAN，且系统已开启并连接到第二个热点。
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
- **唯一的对外请求由你主动触发**：网络通道页的出口探测会访问你选择的公网 IP 接口——内置预设（全部
  HTTPS）或你自定义的地址。与被浏览器访问一样，该接口必然会看到你**用于探测的公网 IP**；除此之外不会
  发送任何设备标识、应用列表或规则内容。**若你从不运行探测，应用不会产生任何网络请求。**
- **Root 权限只用于路由**：`su` 仅用于增删策略路由规则、写入开机恢复脚本，以及读取诊断里展示的解析器
  与开机日志信息，不会替任何人执行操作，也不会回传任何结果。
- **应用列表只读，不外传**：`QUERY_ALL_PACKAGES` 仅用于列出已安装应用（及其分身空间实例）以便指派。

---

## 已知限制

- **只改写 53 端口**：传统 DNS 会跟随被指派的通道；Private DNS（DoT，853）与 DoH 不会被改写。全流量
  VPN 场景下的实测结论是：数据流量已走出隧道，而域名解析可能仍留在隧道内——具体观测见
  [docs/VERIFICATION.md](docs/VERIFICATION.md)。
- **VPN 的影响取决于其工作模式**：真机实测（全流量隧道客户端）显示，客户端的**按 UID 捕获规则在
  `24000`**，低于 MultiRoute 的 `14400`/`14500` → 被指派的应用会**离开隧道**（实测其连接改用被指派网卡的
  地址）。而 always-on VPN 且开启"阻止无 VPN 连接"时，Android 改用 `13000`/`14000` 规则，**高于**
  MultiRoute → 此时 VPN 优先、指派不生效。注意：VPN 活跃时指派应用，等于把该应用**移出隧道**。
- **平台自己的按 UID 网络选择低于 MultiRoute**（`15040` 一带），所以通道分配会覆盖它。
- **分身识别依赖 root 包列表**：界面显示数字空间号而非空间名称；仅覆盖"分身空间是真实 Android
  用户"的 ROM。
- **息屏保活针对小米双 WLAN 私有类**（`SlaveWifiService`、`DualStaImpl`），且**只在该开关开启时才安装**
  hook；其他 ROM 上该开关无效。
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

# 单元测试（43 个，仅纯逻辑）
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
