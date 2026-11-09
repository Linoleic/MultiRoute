# MultiRoute — 技术架构与原理参考

[English](ARCHITECTURE.md) | [简体中文](ARCHITECTURE_CN.md) | [← 返回 README](README_CN.md)

本文档说明 MultiRoute 的内核策略路由规则、LSPosed 框架 Hook、个人热点上游调度、双 Wi-Fi 驱动方案、模块状态机制与隐私边界。

---

## 系统架构

MultiRoute 结合 **Android 框架层 Hook（LSPosed）** 与 **Linux 内核策略路由及 NAT 规则（Root）** 实现分流与链路管理：

```
        ┌──────────────────────── MultiRoute 界面 ────────────────────────┐
        │   分应用路由 · 网络通道与实时速率 · 工具箱 · 设置与备份           │
        └───────────────┬─────────────────────────────┬───────────────────┘
                        │                             │
        ┌───────────────▼──────────────┐   ┌──────────▼───────────────────┐
        │      框架层（LSPosed）        │   │       内核层（Root）          │
        │  注入 system_server 及网络栈 │   │     策略路由与 NAT 链         │
        │  · ConnectivityService 调度  │   │  · pref 14500 按 UID 出口     │
        │  · 双 Wi-Fi 助手与防休眠保活 │   │  · pref 14400 局域网直连放行   │
        │  · Tethering 上游网络重定向  │   │  · IPv4 nat 53 端口 DNS 改写  │
        │  · 场景变化唤醒与容灾降级    │   │  · IPv4 与 IPv6 双栈策略      │
        └──────────────────────────────┘   │  · /proc/net/dev 实时速率采集  │
                                           └──────────┬───────────────────┘
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

---

## 模块元数据与打包规范

| 项目 | 值 |
| :--- | :--- |
| 包名 (`applicationId`) | `io.github.linoleic.multiroute` |
| 模块名 | MultiRoute |
| 模块仓库收录 | [modules.lsposed.org/module/io.github.linoleic.multiroute](https://modules.lsposed.org/module/io.github.linoleic.multiroute) |
| Xposed API | `minApiVersion 101`、`targetApiVersion 102`（LibXposed） |
| 模块作用域 | `system`（系统框架 / `system_server`）、`com.android.networkstack.tethering`、`com.google.android.networkstack.tethering`、`com.android.networkstack`、`com.google.android.networkstack` |
| `staticScope` | `false`（作用域清单随 APK 元数据提供） |
| `autoHotReload` | 清单中声明为 `true`，但系统框架无法热重载，更新后需重启设备或重启系统服务 |
| 更新清单 | [`xposed_update.json`](xposed_update.json) |

APK 内包含 `META-INF/xposed/{module.prop, scope.list, java_init.list}`（入口为 `com.multiroute.hook.MultiRouteModule`），支持的 Xposed 管理器可直接读取作用域与入口配置。

---

## 内核层（Root）

### 1. 策略路由规则（`ip rule`）

每张网络接口在 Android `netd` 中均有独立路由表。MultiRoute 在内核层安装路由规则，不使用用户态 VPN 转发：

- `ip rule add uidrange <uid>-<uid> lookup <iface> pref 14500`：为每个被指派的应用 UID 安装 IPv4 与 IPv6 策略路由规则。系统原生的按 UID 网络选择规则位于 `15040` 优先级附近，因此 `pref 14500` 会优先命中。
- `ip rule add to <直连网段> lookup <iface> pref 14400`：根据各通道实际的直连前缀生成，使被分流到其他通道的应用仍可直接访问局域网设备。

### 2. 分应用 DNS 改写（`iptables`）

MultiRoute 在 IPv4 `nat` 表中创建 `MULTIROUTE_DNS` 链（挂接于 `OUTPUT`），将被指派 UID 的 **IPv4 UDP/TCP 53 端口**请求重定向至所选通道的 DNS 解析器。

- IPv6 DNS、**Private DNS（DoT，853 端口）** 与 **DoH** 不作改写；
- VPN 启用时，DNS 与应用流量的实际路径取决于 VPN 自身的路由策略。

### 3. 持久化 Root 终端池与接口统计

- **持久化 Root 终端池（`RootShellExecutor`）**：通过长连接交互终端池（常规 `su` 与全局挂载命名空间 `su -M`）在隔离 subshell 中执行命令，避免每次查询都新建 `su` 进程；超时或异常会话会被关闭并按需重建。
- **通道速率采集（`ChannelTrafficMonitor`）**：在「网络通道」页打开期间，通过终端池定期读取 `/proc/net/dev` 字节计数器，计算实时上下行速率、30 样本滚动曲线、窗口峰值与开机以来累计流量。
- **系统估算吞吐与 Wi-Fi 协商速率的区别**：
  - **系统估算吞吐**：来自 Android `NetworkCapabilities` 的上下行带宽估算值，反映平台或运营商估算而非实时测速。
  - **Wi-Fi 协商速率**：从 `WifiInfo` / `dumpsys wifi` 提取的底层物理 PHY 接收/发送协商速率（Mbps）。

### 4. 开机恢复与规则缓存

- **开机脚本（`/data/adb/service.d/00-multiroute-restore.sh`）**：当 Root 管理器支持 `service.d` 时生成。开机后最多等待 36 轮（每轮 5 秒）检测目标接口，就绪后应用一次 IPv4/IPv6 路由规则，记录日志到 `/data/adb/multiroute/last_boot_sync.log` 后退出。该脚本仅恢复路由规则，不会建立副 Wi-Fi 连接。
- **规则缓存（`/data/system/multiroute_rules_cache`）**：保存 UID → 接口 映射供框架 Hook 读取。

### 5. 手工清理命令

正常卸载前请先在应用内将热点上游恢复为「系统默认」、断开由应用连接的副 Wi-Fi，并执行 **分流规则 → 清空所有应用分流规则**。若应用已无法打开，可手工执行以下命令：

> 注意：以下命令会按优先级删除 `14400` 与 `14500` 的全部 IPv4/IPv6 路由规则、清空 `MULTIROUTE_DNS` 链并删除 MultiRoute 的开机脚本与规则缓存。执行前请检查是否与其他路由工具共用这些优先级；如使用过双 Wi-Fi，设备系统层面的设置与 Overlay 需另行恢复。

```bash
su -c 'rm -f /data/adb/service.d/00-multiroute-restore.sh /data/adb/service.d/01-multiroute-dualwifi.sh /data/system/multiroute_rules_cache'
su -c 'while ip rule del pref 14500 2>/dev/null; do :; done; while ip rule del pref 14400 2>/dev/null; do :; done'
su -c 'while ip -6 rule del pref 14500 2>/dev/null; do :; done; while ip -6 rule del pref 14400 2>/dev/null; do :; done'
su -c 'iptables -t nat -D OUTPUT -j MULTIROUTE_DNS 2>/dev/null; iptables -t nat -F MULTIROUTE_DNS 2>/dev/null; iptables -t nat -X MULTIROUTE_DNS 2>/dev/null'
```

---

## 框架层（LSPosed Hooks）

### 1. 系统框架 (`system_server`)

内核 `ip rule` 负责实际报文转发，而应用常通过 `ConnectivityManager` 查询活动网络状态。`system_server` 中的 Hook 让应用可见的网络状态与指派一致：

- `ConnectivityService.getDefaultNetworkForUid`
- `ConnectivityService.getActiveNetworkForUidInternal`
- `ConnectivityService.getMobileDataPreferredUids`（在遵循该接口的 ROM 上支持按 SIM 蜂窝分流）
- 链路变更唤醒：网络切换时触发场景方案重新评估，界面不在前台时同样会更新。

### 2. 个人热点上游调度（`NetworkStack / Tethering APEX`）

MultiRoute Hook NetworkStack / Tethering APEX 中的 `UpstreamNetworkMonitor.getCurrentPreferredUpstream`，监听 `Settings.Global`（`multiroute_hotspot_upstream`）与广播以请求 Android Tethering 异步重选热点上游：

```
                      ┌───────────────────────┐
                      │ 笔记本 / 平板 / 随身设备 │
                      └───────────┬───────────┘
                                  │ (Wi-Fi 客户端接入)
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

数据包转发、NAT、IPv6 前缀下发与硬件卸载仍由 Android 系统 Tethering 控制器管理。

### 3. 双 Wi-Fi 驱动与连接方案

| 适配方案 | 代码标识 | 机制与说明 |
| :--- | :--- | :--- |
| **自动** | `AUTO` | 根据厂商、ROM 与检测到的能力选择方案（默认）。 |
| **小米 / HyperOS 专用** | `XIAOMI_SLAVE` | 协同小米 `SlaveWifiService` / `DualStaImpl`，并使用尝试在息屏时维持副网连接的 Hook；效果取决于设备、固件与 ROM。需要时会写入 `/data/adb/service.d/01-multiroute-dualwifi.sh`。 |
| **AOSP Multi-Internet** | `AOSP_OVERLAY` | 要求 **Android 13 (API 33)+**。应用 Overlay 启用 `config_wifiMultiStaMultiInternetConcurrencyEnabled` 并请求 Multi-Internet 模式 2。资源名称和行为因 ROM 而异，尚未广泛验证。 |
| **Root 直连** | `ROOT_DIRECT` | 通过 Root 调用 `cmd wifi set-multi-internet-mode 2`。不会增加硬件或固件不支持的并发能力，尚未广泛验证。 |

> **「清理网络临时限制」**会清除用户禁用网络标记、重置 Root 请求许可并移除所有通过 `cmd wifi add-request` 创建的请求（包括其他 shell 工具发起的请求），不保证解除所有 BSSID 或固件惩罚记录。

---

## 模块状态说明

顶栏状态图标结合系统信标与 `system_server` 内运行代码的 `BuildConfig.VERSION_CODE`、`HOOK_BUILD_ID` 进行校验：

| 状态 | 含义 |
| :--- | :--- |
| **已激活（hook 就绪）** | Hook 已安装，且 `system_server` 加载的版本与当前安装的 APK 一致。 |
| **已加载，hook 缺失** | 代码已注入 `system_server`，但未能安装 `ConnectivityService` Hook。 |
| **已加载旧版本** | 升级或重装后 `system_server` 仍在运行旧构建，需重启系统或重启 `zygote`。 |
| **已加载（旧版标记）** | 检测到旧版标记，但缺少详细 Hook 状态字段。 |
| **状态记录已过期** | 记录的属主 PID 已不再是当前存活的 `system_server` 进程。 |
| **未激活** | 模块未启用、作用域未勾选或尚未注入成功。 |

---

## 技术边界与隐私

### 隐私与数据流向

MultiRoute 没有统计、埋点、崩溃上报或广告后端，也不需要账号。

- **本地数据与配置备份**：分流配置保存在应用私有目录，运行规则保存在内核与 `/data/system/multiroute_rules_cache`。SIM 目标使用设备内不透明标识，不保存原始 ICCID 或手机号。v3 JSON 备份包含应用分流、场景、网络策略、热点偏好、双 Wi-Fi 非敏感设置、外观与测速节点，不包含 Wi-Fi 密码、UID 缓存、运行日志和无线调试开机恢复设置。
- **对外请求**：
  - **公网出口探测**仅在用户手动点击测试时向目标地址发起请求。因支持自定义局域网 URL（如 `http://192.168.1.1/ip`），`usesCleartextTraffic` 保持开启；内置预设节点均为 HTTPS。
  - **自动容灾回退的连通性校验**（可在设置中关闭）仅在通道连接或恢复时发送单次 HTTP 204 请求（0 字节请求体，不含设备标识、应用列表或规则）以识别需认证 Wi-Fi 或离线网络。关闭后不发起这些请求。
- **权限用途**：
  - `su`：路由与 `iptables` 规则、开机脚本、双 Wi-Fi / 热点 / 无线调试控制、`/proc/net/dev` 速率读取与诊断快照。
  - `QUERY_ALL_PACKAGES`：仅用于在本地列出已安装应用及多用户分身以便选择。

### 技术局限

- **Android 7 – 10 (`< API 30`)**：不支持；所 Hook 的框架接口在 Android 11 以下不存在。
- **DNS 与 VPN**：仅改写 IPv4 UDP/TCP 53 端口；DoT（853）、DoH 与 IPv6 DNS 不改写。存在活跃 VPN（`tun0`）时，路径受 VPN 模式与策略影响；选择器限制 VPN 所属 UID 与常见 VPN 客户端选入 VPN 通道以减少回环，但不保证所有导入配置均不发生回环。
- **应用分身枚举**：通过 Root 读取包列表，界面显示数字空间号（如 `(999)`），仅覆盖以真实 Android 多用户实现分身的 ROM。
- **副卡通道控制**：要求两张已激活 SIM，仅控制非默认数据卡且不改变默认数据卡，开机不自动启用；双卡并发依赖硬件与运营商，按 SIM 分流依赖 ROM 对 `mobile_data_preferred_uids` 的支持。
- **副 Wi-Fi 息屏保持**：除受支持的小米专项 Hook 外，其他平台由前台服务监控并尝试重连，不能保证硬件或固件在 Doze 期间维持连接。
