# MultiRoute

[English](README.md) | [简体中文](README_CN.md)

[![build](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml/badge.svg)](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20(已在%2017%20验证)-green.svg)](https://developer.android.com)
[![LibXposed](https://img.shields.io/badge/LibXposed-API%20102-orange.svg)](https://github.com/libxposed)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-purple.svg)](https://kotlinlang.org)

**让每个应用走各自的网络通道。** 把应用分别指派到主 Wi-Fi、副 Wi-Fi（双 WLAN）、移动蜂窝或有线
以太网，让它们**同时**通信。出口通过内核策略路由（`ip rule`）落实，而不是走 VPN。

> 需要 **Root**（KernelSU / Magisk / APatch）与 **LSPosed**，模块作用域为系统框架
> （`system_server`）。本项目与 LSPosed 官方无隶属关系。

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
        │                              │   │  · IPv4 与 IPv6              │
        └──────────────────────────────┘   └──────────┬───────────────────┘
                                                      │
        ┌─────────────────────────────────────────────▼───────────────────┐
        │     主 Wi-Fi   │   副 Wi-Fi   │   移动蜂窝   │    以太网          │
        └─────────────────────────────────────────────────────────────────┘
```

---

## 主要特性

- **分应用通道指派**：为任意应用指定通道，其流量从该链路出口，其他应用不受影响。
- **分身独立配置**：应用分身空间（如小米 XSpace，用户 999）与工作资料会作为**独立条目**列出
  （`微信 (999)`），因此分身与主安装可以走**不同**通道。
- **双栈下发**：IPv4 与 IPv6 规则同时安装。
- **局域网直连放行**：各网卡直连网段固定从属主网卡出口，内网设备（NAS、打印机、投屏）在应用被
  指派到其他通道时依然可达。
- **开机自动恢复**：规则跨重启保留——`service.d` 脚本、模块发起的唤醒广播、应用自身的网络回调
  三重互补。
- **副 Wi-Fi 息屏保活**：可选；防止 OEM 省电策略在息屏后拆掉副 WLAN 链路（小米双 WLAN）。
- **出口实时诊断**：按通道做公网出口 IP 探测，内置预设并支持自定义地址。
- **精准模块状态**：明确区分「hook 已就绪」「`system_server` 仍在跑旧版本」「状态记录已过期」等，
  而不是只用一句"已激活"糊过去。
- **不使用 VPN**：在内核层分流，没有用户态 TCP/IP 栈，吞吐与时延接近原生。

---

## 运行环境与兼容性

| 项目 | 要求 |
| :-- | :-- |
| Root | KernelSU / Magisk / APatch（操作策略路由表需要 root） |
| Xposed 框架 | LSPosed（或实现了 LibXposed API 102 的框架） |
| 模块作用域 | **仅勾选系统框架**（`system` / `system_server`） |
| Android | `minSdk` 24；hook 同时兼容 APEX 模块化与传统 `ConnectivityService` 结构 |

| Android 版本 | 状态 |
| :-- | :-- |
| **Android 17 / HyperOS** | ✅ 已在 Xiaomi HyperOS 手机 + KernelSU + LSPosed v2.2.0 上验证：双 WLAN 与蜂窝并发、IPv4/IPv6 规则、局域网放行、分身分流、开机恢复、模块状态、息屏保活 |
| **Android 16 / HyperOS** | ✅ 已在 Xiaomi HyperOS 平板 + KernelSU + LSPosed v2.2.0 上验证：双 WLAN 分应用分流（IPv4 + IPv6）、分身独立分流、真实流量出口、软重启后恢复、模块状态。该平板无蜂窝接口，蜂窝分流未在该设备上验证 |
| Android 11 – 15 | ⚠️ 预期可用（hook 目标与规则结构一致），尚未验证 |
| Android 7 – 10 | ⚠️ 可编译（`minSdk` 24），未测试；策略路由行为存在差异 |

> [!IMPORTANT]
> **更新模块后必须重启。** 模块注入系统框架，LSPosed 无法热重载：安装或更新 APK 后需重启设备，
> 或重启系统服务（`su -c 'setprop ctl.restart zygote'`）。若仍在跑旧版本，界面会明确提示
> 「已加载旧版本，需软重启」，而不会假装一切正常。

> [!NOTE]
> **日常规则调整无需重启。** 增删改分应用规则立即生效。

上表所依据的真机证据（使用的命令与实测输出，以及**尚未验证**的部分）整理在
[docs/VERIFICATION.md](docs/VERIFICATION.md)。

---

## 安装

1. 安装 `app-release.apk`（需要 root 与 LSPosed）。预编译包见
   [Releases](https://github.com/Linoleic/MultiRoute/releases)；模块已声明更新清单，管理器可直接提示更新。
2. 打开 **LSPosed 管理器 → 模块 → MultiRoute**，启用并将作用域设为**系统框架**（`system`）。
   模块已内置 `META-INF/xposed/scope.list` 元数据。
3. 重启设备，或重启系统服务：`su -c 'setprop ctl.restart zygote'`。
4. 打开 MultiRoute 并授予 root。**设置**页应显示「已激活（hook 就绪）」。若显示「已加载旧版本」，
   再重启一次系统服务。
5. 如需在连接 Wi-Fi 时同时用蜂窝分流，请开启系统/开发者选项中的「移动数据始终保持连接」。

---

## 使用

| 页面 | 作用 |
| :-- | :-- |
| **分流规则** | 点应用选通道；长按进入多选批量指派。分身会以独立条目出现并带空间号。 |
| **网络通道** | 查看各条链路的接口、IP、网关、DNS、SSID，并按通道做公网出口探测。 |
| **设置** | 模块状态、Root 状态、当前内核规则，以及保活开关。 |

---

## 工作原理

**内核层（Root）**：每个网卡在 Android 的 `netd` 中都有独立路由表，MultiRoute 安装：

- `ip rule add uidrange <uid>-<uid> lookup <iface> pref 14500` —— 每个被指派的 UID 一条，IPv4/IPv6 各一份；
- `ip rule add to <直连网段> lookup <iface> pref 14400` —— 局域网放行，网段由各通道实际的直连前缀生成。

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

- **提示「模块未激活」**：在 LSPosed 管理器中启用并勾选系统框架，然后重启或重启系统服务。
- **看不到副 Wi-Fi 通道**：需硬件支持双 WLAN，且系统已开启并连接到第二个热点。
- **重启后规则丢失**：查看 `/data/adb/multiroute/last_boot_sync.log`，并确认该 ROM 会执行
  KernelSU/Magisk/APatch 的 `service.d` 脚本。
- **公网出口测试超时**：在设置页换成当前网络可达的预设节点，或自定义探测地址。
- **被指派的应用断网**：该通道的网卡可能已下线；改指派到其他通道，或设回「系统默认」。

---

## 隐私

MultiRoute 不收集、不上传任何数据：没有统计、没有埋点、没有崩溃上报、没有广告、也没有账号——依赖清单
只有 AndroidX/Compose 与 LibXposed API，仅此而已。

- **数据都留在设备上**：分流规则保存在应用私有配置与内核规则里，仅为 hook 额外保留一份 UID→接口 的小
  缓存，不会上传到任何地方。
- **唯一的对外请求由你主动触发**：网络通道页的出口探测会访问你选择的公网 IP 接口——内置预设（全部
  HTTPS）或你自定义的地址。与被浏览器访问一样，该接口必然会看到你**用于探测的公网 IP**；除此之外不会
  发送任何设备标识、应用列表或规则内容。**若你从不运行探测，应用不会产生任何网络请求。**
- **Root 权限只用于路由**：`su` 仅用于增删策略路由规则与写入开机恢复脚本，不会替任何人执行操作，也不
  会回传任何结果。
- **应用列表只读，不外传**：`QUERY_ALL_PACKAGES` 仅用于列出已安装应用（及其分身空间实例）以便指派，
  该列表不会离开设备。

---

## 已知限制

- **不管理 DNS**：只重定向路由，域名解析仍走平台解析器；各链路 DNS 不同（或启用 Private DNS/DoT）
  时，解析结果可能不随指派通道变化。
- **VPN 的影响取决于其工作模式**：真机实测（全流量隧道客户端）显示，客户端的**按 UID 捕获规则在
  `24000`**，低于 MultiRoute 的 `14400`/`14500` → 被指派的应用会**离开隧道**（实测其连接改用被指派网卡的
  地址）。而 always-on VPN 且开启"阻止无 VPN 连接"时，Android 改用 `13000`/`14000` 规则，**高于**
  MultiRoute → 此时 VPN 优先、指派不生效。注意：VPN 活跃时指派应用，等于把该应用**移出隧道**。
- **若 VPN 通告的是隧道内 DNS，被指派应用可能解析失败**：该 UID 的查询会跟随分配走出隧道，而 DNS 服务器
  只在隧道内可达。实测该 VPN 通告的是局域网网关地址，因此解析正常。见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。
- **平台自己的按 UID 网络选择低于 MultiRoute**（`15040` 一带），所以通道分配会覆盖它。
- **分身识别依赖 root 包列表**：界面显示数字空间号而非空间名称；仅覆盖"分身空间是真实 Android
  用户"的 ROM。
- **息屏保活针对小米双 WLAN 私有类**（`SlaveWifiService`、`DualStaImpl`），其他 ROM 上该开关无效。
- **蜂窝分流**取决于 ROM 是否遵循 `mobile_data_preferred_uids`。
- **界面目前仅中文**，英文资源尚不完整。
- **出口探测允许明文 HTTP**：内置预设均为 HTTPS，但自定义地址可能是明文（例如路由器页面
  `http://192.168.1.1/ip`），因此 `usesCleartextTraffic` 保持开启。
- 目前验证覆盖的机型与系统版本有限（见兼容性表），其他 ROM 可能存在差异。

---

## 源码构建

```bash
# Debug / Release APK
./gradlew assembleDebug
./gradlew assembleRelease

# 单元测试（36 个）
./gradlew testDebugUnitTest
```

- 环境：JDK 17、Android SDK Platform 36。
- 签名可选：复制 `keystore.properties.example` 为 `keystore.properties` 并填入凭证；未配置时会输出
  警告并降级为 debug 签名，保证 CI 与外部开发者可直接编译。
- 产物：`app/build/outputs/apk/release/app-release.apk`。

### 版本号规则

采用 LSPosed 自身发布所用的方案：`versionName` 取自最近的 git 标签（工作区有未提交改动时标记 `-local`），
`versionCode` = **上游分支的提交数 + 固定偏移**。统计的是 `origin/master` 而非本地 HEAD，因此 CI、全新
克隆、以及多了若干本地提交的 fork，在同一上游状态下**得到同一个版本号**。

需要自己的编号时可覆盖两者：

```bash
./gradlew assembleRelease -PmultiRouteVersionName=1.0.0-fork -PmultiRouteVersionCode=19999
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

## 致谢

- [LibXposed API](https://github.com/libxposed) 与 [LSPosed](https://github.com/LSPosed/LSPosed)：
  模块所依赖的框架接口。
- Jetpack Compose 与 Material 3：界面实现。
