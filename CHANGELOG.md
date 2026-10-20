# Changelog

<!--
Release notes are generated from the matching `## vX.Y.Z` section, verbatim. Keep every section
bilingual, Chinese first: the release page is what most users read.
-->

## v1.4.0

**中文**

- **双 Wi-Fi 助手（STA + STA 并发管理）**：新增独立管理面板，支持副 Wi-Fi 的扫描、认证连接、状态诊断与断开全生命周期控制，实时检测双频硬件能力与网卡链路信息。
- **多方案自适应分层连接架构**：支持智能自动（AUTO）、小米/HyperOS 专用（XIAOMI_SLAVE）、AOSP/Pixel 标准（AOSP_OVERLAY）与通用 Root 直连（ROOT_DIRECT）；通用模式借助 SYSTEM_UID 特权通道穿透定制 ROM 限制，实现免 Overlay 直连。
- **同名 Wi-Fi（SSID）跨频段独立隔离**：彻底解决 2.4G / 5G / 6G 同名 Wi-Fi 被合并的问题，各频段独立呈现信号与状态，支持针对特定频段精准发起连接。
- **可用 Wi-Fi 详情与连接诊断**：点击 Wi-Fi 卡片展开详情面板，直观查看物理 MAC (BSSID)、频段信道、信号强度、加密套件、凭据状态及并发评估；引入动态信号图标与多层级智能排序。
- **运行诊断日志全面扩充与导出分享**：大幅丰富系统运行与诊断日志信息，新增双 Wi-Fi 硬件并发能力与芯片参数、各通道实际 DNS 与系统全局私有 DNS (DoT) 状态、各接口默认网关策略路由表，并拓宽 Logcat 抓取深度至 50 行覆盖 Wi-Fi 协议栈；支持一键将日志导出为文本文件或通过系统面板快速分享反馈。
- **网络限制清理与体验优化**：新增一键清除底层 BSSID 惩罚黑名单功能；修复双 Wi-Fi 软硬件卡片背景透明度导致的灰色边框异常，优化卡片手势层级与文本表述。

**English**

- **Dual Wi-Fi Manager (STA + STA Concurrency):** Added a dedicated manager for scanning, connecting, diagnosing, and disconnecting the secondary Wi-Fi, alongside real-time hardware concurrency and link status tracking.
- **Multi-tier Adaptive Architecture:** Introduced 4 connection schemes: AUTO, XIAOMI_SLAVE, AOSP_OVERLAY, and ROOT_DIRECT; the root scheme leverages a privileged SYSTEM_UID channel to bypass OEM restrictions without requiring overlays.
- **Independent Cross-band Isolation for Identical SSIDs:** Fully disambiguates multi-band Wi-Fi sharing the same SSID (2.4G / 5G / 6G) into isolated entries with independent signal tracking and connection states.
- **Wi-Fi Details & Diagnostics:** Tapping any Wi-Fi card opens an interactive sheet displaying BSSID, band, channel, signal strength, security suites, credentials, and concurrency readiness, with dynamic signal icons and prioritized sorting.
- **Enhanced Diagnostic Log, Export & Share:** Substantially expanded diagnostic logs to include Dual Wi-Fi hardware concurrency and chipset specs, per-channel upstream DNS and Private DNS (DoT) status, interface default gateways, and an expanded 50-line Wi-Fi stack logcat trace; added SAF file export and system share sheet integration for quick feedback.
- **Blocklist Reset & UI Polish:** Added a one-click action to clear system-level BSSID temporary penalties; fixed grey border rendering artifacts on hardware cards and refined gesture handling.

## v1.3.1-beta.1

**中文**

- **多语言即时切换兼容修复**：修复在部分定制 ROM 与旧版本 Android 上语言切换无法即时生效或重启后失效的问题（引入应用级与 Activity 级上下文配置封装，全兼容 Android 11 ~ 17）。
- **运行诊断日志增强**：在系统运行与诊断日志中输出设备的完整品牌、具体机型、Android 系统版本与构建版本号，便于排查特定机型的系统网络策略与兼容性问题。
- **发布脚本优化**：CI 发布工作流自动识别 Beta / RC / Preview 版本并标记为 Pre-release。

**English**

- **Reliable in-app language switching:** Fixed an issue where switching the app language to English had no effect on certain ROMs and Android versions. Both application and activity context configurations are now explicitly wrapped to ensure immediate and persistent locale resolution across Android 11 to 17.
- **Diagnostic report enhancement:** The runtime diagnostic log now includes device manufacturer, model, Android OS version (release and API level), and display build ID for accurate environment diagnosis.
- **CI release workflow improvement:** Pre-release tags (beta, rc, preview) are now automatically tagged with the `--prerelease` flag on GitHub and repository releases.

## v1.3.0

**中文**

- **通道故障自动降级 (Auto Failover)**：当被指派的网络通道离线、断开连接或丢失默认网关时，其绑定的应用会自动即时回退至系统默认网络，避免应用陷入无网死锁。
- **主动外网连通性校验 (204 探针)**：通道建立或恢复时，可选通过该通道向极轻量 204 探针发起无数据体请求，精准识别欠费、断网 Wi-Fi 或待认证门户（Captive Portal）；纯内网环境可一键关闭。
- **网络恢复/切换防抖延迟**：统一通道恢复回切与情景策略重新激活的防抖时间窗口（支持 1s / 2s / 3s / 5s / 8s 可选，默认 3s），等待链路稳定与 DHCP 握手完成，杜绝边缘弱信号区的频繁颠簸与连接横跳。
- **安全加固 (Security Hardening)**：
  - 严格限制开机与动态恢复广播的接收权限（`signature` 签名级别防护），阻断未授权应用假借恢复之名滥发广播。
  - 收紧规则持久化缓存与诊断日志文件权限（仅属主读写 `0600` / 内部私有存储 `MODE_PRIVATE`）。
  - 设置页显式提供「DNS 随指派通道解析」启闭开关，便于与全局加密 DNS (DoT / DoH) 灵活共存。
- **界面体验精简**：设置页全面优化视觉呼吸感，剔除各卡片多余堆叠的二级提示小字，将关键限制与功耗提示提炼融入单行描述，交互更加清爽直观。

**English**

- **Auto Failover on Channel Outage:** When an assigned channel disconnects, drops offline, or loses its default route, bound apps automatically fall back to the system default network immediately to prevent network dropouts.
- **Active Internet Connectivity Validation (204 Probe):** Optionally validates real internet connectivity upon channel connection or recovery via lightweight HTTP 204 probes (0-byte body) to accurately detect captive portals or offline Wi-Fi (can be disabled for pure LAN environments).
- **Unified Network Recovery Hold-off Delay:** Synchronizes anti-flapping debounce delay across channel failover recovery and contextual scenario activation (selectable: 1s / 2s / 3s / 5s / 8s, default: 3s) to prevent route flapping at weak signal boundaries.
- **Security Hardening:**
  - Enforced `signature`-level permission guards on boot and dynamic restoration broadcast receivers to prevent unauthorized spoofing.
  - Hardened file permissions on rule cache and diagnostic logs (`0600` / `MODE_PRIVATE`).
  - Added toggle in Settings for transparent per-channel DNS redirection.
- **Streamlined Settings UI:** Cleaned up redundant secondary note captions and refined descriptions into clear, concise single-line headers.

## v1.2.3

**中文**

- **场景方案在应用未运行时也生效**：模块在 `system_server` 侧监听链路变化，仅在存在方案时唤醒应用重新评估
  （没有方案就保持休眠）；应用进程被杀时同样会切换。
- 方案支持**编辑覆盖应用**（编辑态下的指派写入方案而不是默认方案）、**改名与触发条件**、**调整顺序**
  （顺序即判定优先级）。
- **配置导出与导入**：一份 JSON 文档携带分应用指派（含分身空间）、全部场景方案与相关设置；可写入
  `Download` 或复制到剪贴板，也能从两者导入。导入是整体替换，但会**先备份当前配置**并报告跳过了多少项，
  因此指向本机不存在应用或通道的条目不会静默改动任何东西。方案列表里每个方案各自一层容器；Wi-Fi 条件
  未选网络时会明确标注（这种方案不会自动命中）。
- **多个方案可同时生效**：命中多个方案时不再只取一个，而是按优先级依次叠加（后一个可改写前一个给某应用设的通道，
  也能用「系统默认」把应用移出分流）。同一个应用被多个方案设定时**优先级高的生效，被改写的那一个会在方案行上标出**。
- **策略绑定网络而不是网卡**：在方案里指派 Wi-Fi 链路时保存的是 `ssid:<名称>`，每次同步解析成当时的接口 ——
  哪张网卡是 `wlan0` 在运行中会变。网络不在线时保持原有指派，而不是指向一条不存在的链路。
- **手动模式**：抽屉里新增「自动 / 手动」两个模式。「手动」下**所有触发条件都不生效**，只应用手动开启的方案，
  且**可同时开启多个**（同样按优先级叠加）；点一个方案不会取消其它方案。
- 抽屉里应用/取消不再关闭抽屉，方便连续操作；配置备份卡片移到「模块与路由状态」之后，导入改为**系统文件选择器**
  （不必输入路径，也不需要 root）。

**English**

- **Scenario plans now apply while the app is not running.** The module watches link changes inside
  `system_server` and wakes the app to re-evaluate, but only while a plan exists - without one the app stays
  asleep. The app's own callback is no longer the only trigger.
- Plans can be **re-edited** (in edit mode assignments go into the plan instead of the default plan),
  **renamed**, have their **condition changed**, and be **reordered** - the stored order is the priority order.
- **Configuration export and import.** One JSON document carries the per-app assignments (including clone
  spaces), every plan and the routing settings; it can be written to `Download` or copied to the clipboard,
  and imported from either. Import replaces the configuration, but backs the current one up first and reports
  what it skipped, so entries pointing at apps or channels that are missing here cannot silently change
  anything. Each plan in the sheet now sits in its own surface, and a Wi-Fi condition with no network chosen
  says so, because such a plan can never match on its own.
- **Several plans can apply at once.** Matching plans now layer in priority order instead of only the first one
  winning: a later plan can re-route an app an earlier one set, or take it out of routing with `default`. When
  more than one active plan sets the same app the higher-priority one wins, and the overwritten plan is labelled
  on its row instead of being dropped silently.
- **A plan binds to the network, not to the interface.** Assigning a Wi-Fi link inside a plan stores
  `ssid:<name>`, resolved to whatever interface that network is on at sync time, because which interface is
  `wlan0` changes while the device runs. A network that is not up leaves the previous assignment alone.
- **Manual mode.** The sheet offers Automatic / Manual: in Manual no condition is evaluated at all, only the
  plans switched on by hand apply, and several can be on at once, layered by priority. Switching one plan on
  never cancels another.
- Applying or cancelling a plan no longer closes the sheet, the backup card moved below the module state card,
  and importing uses the system file picker instead of a typed path.

## v1.2.2

**中文**

- **场景方案**：给应用指派通道后，可以把它保存为一个方案（名称 + 触发条件）。命中时，方案只覆盖它涉及的应用，
  其余应用保持默认指派。触发条件支持 **Wi-Fi 名称**（从当前已连接的网络里直接点选）、**双 Wi-Fi 在线**、
  **仅蜂窝**、**始终**与**仅手动**；多个方案同时命中时按优先级取第一个，也可以在列表里**手动指定**方案
  （此时忽略条件）。方案还能把某个应用**移出分流**（覆盖为「系统默认」）。分流规则页顶栏显示当前生效方案，
  点击即可切换；诊断快照会写出当前方案与命中原因。

**English**

- **Scenario plans.** Once apps are assigned to channels, that assignment can be saved as a plan (a name plus a
  condition) which overrides the apps it mentions when the condition matches, while every other app keeps the
  default assignment. Conditions: a **Wi-Fi name** picked from the networks connected right now, **two Wi-Fi
  links**, **cellular only**, **always**, or **manual only**. When several plans match, the lowest priority
  wins, and a plan can also be pinned by hand (which ignores its condition). A plan may also put an app **back
  on the system default**. The routing screen shows the active plan in its top bar, and the diagnostic
  snapshot names the plan and why it matched.

## v1.2.1

**中文**

- **发版体积门禁**：APK 超过 20 MB 时发布直接失败。1.1.1 曾以 43 MB 发布出去 —— 原因是 R8 一直没启用，
  而 `minSdk` 升到 30 后 dex 不再被压缩；启用 R8 后约 3 MB，这道门禁可拦住同类回归。应用本身无功能变更。

**English**

- **A release now fails if the APK exceeds 20 MB.** The 1.1.1 release went out at 43 MB because R8 had
  never been enabled and `minSdk` 30 stopped the dex from being compressed; it is ~3 MB since. The guard
  makes a regression like that impossible to publish unnoticed. No functional change in the app itself.

## v1.2.0

**中文**

- **包名改为 `io.github.linoleic.multiroute`**：模块仓库只接受"作者能证明所有权"的包名命名空间，而
  `com.multiroute` 需要 `multiroute.com`。Android 会把它视为另一个应用，因此**安装本版前必须先卸载 1.1.x**
  （分流配置不会迁移），并在 LSPosed 管理器中启用**新包名**条目（它会作为新模块出现）。代码命名空间不变
  （hook 与模块元数据完全一致）；唤醒广播、应用内 hook 判定与镜像目标全部改为从构建取值，不再硬编码包名。

**English**

- **The application id changed** to `io.github.linoleic.multiroute`. The module repository only accepts a
  package namespace its author can prove ownership of, and `com.multiroute` would require
  `multiroute.com`. Android treats the new id as a different app, so **uninstall 1.1.x before installing
  this one** (assignments are not carried over) and enable the new package in LSPosed Manager, where it
  appears as a new module entry. The code namespace is unchanged, so the hooks and the module metadata
  are identical; the wake-up broadcast, the in-app hook check and the repository mirror all derive the id
  from the build now instead of hard-coding it.

## v1.1.1

**中文**

- **下载体积大幅缩小**：release 构建启用 R8，dex 由约 42 MB 降到约 2.4 MB，APK 由约 43 MB 降到约 3.2 MB。
  R8 此前一直未启用，只是被 APK 压缩掩盖了 —— 直到 `minSdk` 升到 30、dex 不再压缩，下载体积才翻了 3.5 倍。
  `proguard-rules.pro` 已保留 hook 入口、Provider、数据模型与 LibXposed；压缩后重新上机验证：两族 hook 均正常
  安装，应用无类错误。
- **Wi-Fi 频段与信道显示**：每个 Wi-Fi 通道显示频段与信道（如 `5 GHz · ch 36`），由连接频率推导 —— 因为
  哪张网卡落在哪个频段会在运行中变化。
- **最低版本明确为 Android 11**：模块所 hook 的接口（含 `getMobileDataPreferredUids`）自 Android 11 起才存在，
  因此 `minSdk` 提到 30，不再安装到注定无法工作的旧版本上。真机验证的是 Android 16 与 17。
- README 补充了管理器会读取的模块元数据（作用域、LibXposed API 范围）、干净的卸载方式，以及"已验证 / 未验证"
  的明确边界。

**English**

- **Much smaller download.** The release build is minified with R8 now: the dex drops from ~42 MB to
  ~2.4 MB and the APK from ~43 MB to ~3.2 MB. R8 had always been off, which APK compression used to
  hide — until `minSdk` reached 30, where the dex is stored uncompressed and the download tripled. The
  hook entry point, the provider, the models and LibXposed are kept by `proguard-rules.pro`, and the
  module was re-verified on device after minifying (both hook families install, no class errors).
- **Wi-Fi band and channel** are shown for every Wi-Fi channel (for example `5 GHz · ch 36`), derived
  from the connection frequency — useful because which interface sits on which band changes while the
  device is running.
- **Android 11 is now the stated minimum.** The interfaces the module hooks, `getMobileDataPreferredUids`
  among them, only exist from Android 11 on, so `minSdk` is 30: the APK no longer installs on releases
  where it could only ever fail. Android 16 and 17 are the versions verified on device.
- The README now documents the module metadata the manager reads (scope, LibXposed API range), how to
  uninstall cleanly, and exactly what has and has not been verified.

## v1.1.0

**中文**

- **分应用 DNS 随通道归属**：Android 是按**默认网络**选择解析器的，与内核把报文路由到哪里无关，因此被指派
  的应用可能去查只在另一条链路上可达的解析器，或继续查 VPN 隧道内的解析器。现在会把被指派 UID 的
  **UDP/TCP 53** 改写到该通道自己的解析器（`nat` 表专用链 `MULTIROUTE_DNS`）；**853（DoT）刻意不改写**，
  DoH 本身是普通 HTTPS、天然跟随路由。开机脚本只清链，应用运行后再用当前网络的解析器补上，避免旧地址弄坏解析。
- **外观与语言设置**：主题（跟随系统 / 浅色 / 深色，带渐变过渡）、Material You 动态取色，以及按应用语言
  （跟随系统 / 简体中文 / English）。界面已全量英文化。
- **小米双 Wi-Fi hook 改为按需安装**：息屏保活开关关闭时不再安装 hook，模块完全不碰 ROM 的 Wi-Fi 代码；
  打开开关会**即时安装**（无需重启）。开关已注明仅小米可用。
- **蜂窝首选 UID 记账**：取消指派或清空规则时，只回收本应用自己加进平台列表的 UID。
- **诊断快照**包含规则生效对照（配置了什么 vs 内核里实际有什么）与开机恢复日志，一键复制即可转交。
- 内部清理：通道标签与出口结果改为语言中立的值（图标与成败判断不再依赖显示文本），41 个单元测试覆盖纯逻辑。

**English**

- **Per-app DNS now follows the assigned channel.** Android picks the resolver from the default network
  regardless of where the kernel routes the packets, so an assigned app could query a resolver that was
  only reachable over a different link — or keep querying one inside a VPN tunnel. Queries of assigned
  UIDs are redirected to their own channel's resolver; the boot script flushes that chain and the app
  re-applies it with the resolvers of the current network, so a stale address can never break resolution.
- **Appearance and language settings.** Theme (follow system / light / dark) with a crossfade instead of a
  restart, a Material You toggle, and language selection (follow system / 简体中文 / English) through the
  platform per-app locale. The UI itself is now fully translated.
- **Xiaomi dual-Wi-Fi hooks are installed on demand.** They are no longer installed at boot unless the
  screen-off keep-alive is on, so the module stays out of the ROM's Wi-Fi code while the feature is unused;
  switching it on installs them immediately, without a reboot. The switch is labelled as Xiaomi-only.
- **Cellular preferred-UID bookkeeping.** Unassigning an app, or clearing every rule, now takes back only
  the UIDs this app added to the platform's preferred-mobile-data list.
- **Rule effectiveness and the boot-recovery log** are part of the copyable diagnostic snapshot, so a
  report shows what is configured, what the kernel actually holds, and what the boot script did.
- Internal clean-ups: channel labels and egress results are language-neutral values now (icons and
  success/failure no longer depend on display text), with 41 unit tests covering the pure logic.

## v1.0.0

**中文**

- **分应用网络通道**：在内核层用策略路由（`ip rule`，IPv4 与 IPv6）落实，每个应用走被指派的链路。
- **分身独立配置**：厂商分身空间（如小米 XSpace，用户 999）与工作资料作为独立条目列出，分身与主安装可走不同通道。
- **接口动态发现**：不写死网卡名；主 Wi-Fi、副 Wi-Fi（双 WLAN）、蜂窝与以太网均支持。
- **按通道的局域网直连放行**：由各链路自身的直连前缀生成，被指派到其他通道的应用仍可访问内网设备。
- **开机自动恢复**：生成的 `service.d` 脚本、模块的唤醒广播、应用自身的网络回调三重互补；重启后设备仍处于
  锁屏状态时，root 脚本是唯一能恢复规则的路径。
- **`system_server` 内的 hook**：让应用可见的网络状态与指派保持一致，并在小米 ROM 上让副 WLAN 链路在息屏后保持在线。
- **精准模块状态**：明确区分「已激活 / hook 缺失 / `system_server` 是旧构建（需重启）/ 状态记录过期」。
- **出口诊断**：按通道做公网出口 IP 探测，内置 HTTPS 预设并支持自定义地址。
- 首次发布。

**English**

- **Per-app network channels**, enforced in the kernel with policy routing (`ip rule`, IPv4 and IPv6).
- **Independent configuration for cloned apps** — OEM clone spaces (e.g. Xiaomi XSpace, user 999) and work
  profiles are listed separately, so a clone and its primary install can use different channels.
- **Dynamic interface discovery** — no hard-coded interface names; primary Wi-Fi, secondary Wi-Fi
  (dual Wi-Fi), cellular and Ethernet are all supported.
- **Per-channel LAN bypass**, generated from each link's own on-link prefixes, so intranet devices stay
  reachable from apps assigned elsewhere.
- **Automatic boot recovery** — a generated `service.d` script, a wake-up broadcast from the module and the
  app's own network callback cover each other; the root script is what restores rules on a device that is
  still locked.
- **Hooks inside `system_server`** keep the app-visible network state consistent with the assignment, and
  keep the secondary Wi-Fi link alive across screen-off on Xiaomi ROMs.
- **Precise module status** — reports activated / hooks missing / outdated `system_server` build (needs a
  reboot) / stale record instead of a single vague flag.
- **Egress diagnostics** — per-channel public-IP probe with HTTPS presets or a custom endpoint.
- Initial release.

---

已验证：Android 16 与 Android 17（HyperOS）+ KernelSU + LSPosed v2.2.0。每条结论对应的命令与实测输出，
以及**尚未覆盖**的部分，见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

Verified on Android 16 and Android 17 (HyperOS) with KernelSU and LSPosed v2.2.0. The commands and
observations behind every claim, plus what is *not* covered yet, are in
[docs/VERIFICATION.md](docs/VERIFICATION.md).
