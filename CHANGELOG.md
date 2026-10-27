# Changelog

<!--
Release notes are generated from the matching `## vX.Y.Z` section, verbatim. Keep every section
bilingual, Chinese first: the release page is what most users read.
-->

## v1.5.0 - Unreleased

**中文**

> 状态：待发布。本节记录开发变更，尚未作为正式版本发布。

### 新增 (Added)
- **个人热点出口选择**：可选择蜂窝网络、副 Wi-Fi 或系统默认网络，并请求 Tethering 重新评估上游。转发、NAT、IPv6 与卸载仍由 Android 管理，独立热点客户端端到端行为尚未覆盖。
- **热点出口控制卡片**：网络通道页新增「热点出口与分流」卡片，展示热点工作频段、系统实际出口和上行 Hook 状态，支持切换与未开启热点时的预设。Hook 状态不代表硬件加速状态。
- **热点下行接口识别与隔离**：自动识别个人热点下行接口（如 `ap0`、`swlan0`、`wlan2` 等），标注为「个人热点」通道类型；详情面板展示频段、接口与目标出口；自动从应用分流与场景方案目标中排除，避免应用绑定下行接口导致通信异常。
- **VPN 虚拟接口识别与属性展示**：自动识别系统内 VPN 虚拟网络接口（如 `tun0`），标注为「虚拟专网 (VPN)」通道类型；详情面板展示 VPN 创建应用包名及标签、隧道内网 IP 地址及底层承载网络（Underlying Networks）。

### 变更 (Changed)
- **配置备份 v2**：明确区分应用分流、完整场景选择、网络策略、热点出口、双 Wi-Fi 偏好、外观和测速节点；支持导入预览、按类别恢复及旧版 v1 配置迁移。
- **LSPosed 模块作用域扩充**：为支持 NetworkStack APEX 内热点调度 Hook，模块作用域在原有系统框架（`system` / `android`）基础上，新增 NetworkStack / Tethering 相关包（`com.android.networkstack.tethering`、`com.google.android.networkstack.tethering`、`com.android.networkstack`、`com.google.android.networkstack`）。模块内 `scope.list` 与 `xposedscope` 清单元数据已同步更新。
- **双 Wi-Fi 状态概览**：重构「双 Wi-Fi 激活与管理」卡片，主界面直接展示主 Wi-Fi 与副 Wi-Fi 的连接 SSID 与工作频段。
- **应用分流列表过滤**：将热点下行接口从普通应用分流与场景方案的候选目标中排除；VPN 保留为可选目标，分应用选择器会限制 VPN 所属 UID 与识别出的常见 VPN 客户端选择 VPN 通道。

### 修复 (Fixed)
- **双 Wi-Fi 诊断状态**：物理链路摘要与实时连接卡片共用连接状态及参数，避免连接或断开后仍显示旧检测结果。
- **Pixel Mainline 网络选择**：兼容以 Map 保存 NetworkAgent 的系统服务；反射字段缺失时缓存空结果，避免 Hook 因向 ConcurrentHashMap 写入空值而异常。显式蜂窝接口仅精确匹配，通用蜂窝选择排除 IMS。
- **双 Wi-Fi 请求与配置保护**：AOSP 副网请求声明 INTERNET 能力，按频段和 BSSID 使用系统保存配置，不携带密码；日常连接与断开仅释放本模块登记的 shell 请求。小米方案复用已保存配置，断开时释放原生副 WLAN；不再删除保存网络或强制清除接口地址。激活后核对实际就绪状态。
- **小米原生请求重复下发**：连接与断开各使用一次同步 Root 桥接，避免同时广播和桥接产生重复请求，或迟到的断开干扰后续连接。
- **并发配置更新**：配置只更新受影响的键，回滚时保留并发运行态。
- **双 Wi-Fi 断开行为**：断开操作会使在途连接请求失效，不再重启服务并自动连回。
- **双 Wi-Fi 连接生命周期**：合并重叠的自动重连，取消或预检查失败后释放连接占位；前台服务与通知校验连接代次，忽略迟到消息；小米连接超时或取消后尝试释放已发起的副网连接。
- **迟到服务启动的退出**：前台服务先完成系统启动握手，再处理过期消息或退出，避免断开后的迟到启动触发前台服务超时崩溃。
- **AOSP 未保存网络提示**：立即提示先在系统 Wi-Fi 设置中保存网络，避免无效请求等待超时。
- **AOSP 连接入口**：依据系统保存配置识别可直连网络，已保存的加密网络不再索要明文密码；请求仅携带频段及接入点，未保存网络提供系统设置入口。手动选择的连接方案不再被厂商品牌覆盖，切换网络或方案会清除旧密码弹窗。
- **浅色主题状态栏**：主题变化时同步状态栏图标明暗，修复系统为深色而应用切换为浅色时图标仍为白色、对比不足的问题。
- **离线目标的开机恢复**：所有指派通道离线时也按最新配置更新恢复脚本，避免修改或导入规则后仍在下次启动重放旧出口；脚本部署失败会返回同步失败。
- **配置导出与恢复**：不再将副 Wi-Fi 密码等设置误当成应用分流导出；恢复手动模式与全部选中场景。清空分流和导入部分配置保留无关设置；写入前验证并备份，备份失败停止替换，写入失败尝试回滚并报告错误。
- **热点蜂窝候选筛选**：排除 IMS-only 网络，按 INTERNET 或运营商 DUN 要求选择候选；指定接口使用精确匹配，多候选选择保持稳定。
- **热点请求与恢复**：指定蜂窝期间复用原生请求，退出指定模式或停止共享时恢复原配置；在原生网络表更新后合并触发重选，处理目标网络恢复。
- **Pixel / Google Mainline 兼容**：支持重定位后的 `android.net.tethering` 类名；启动阶段从主线程下发的原生配置投递到 Tethering Handler，避免漏记需要恢复的原始配置。
- **应用内信标读取**：读取 Tethering 私有缓存时使用 Root 全局挂载命名空间，修复应用数据隔离导致应用内误报未加载、而 ADB 诊断正常的问题。
- **热点实际出口显示**：通道页在前台时定期刷新状态，修复异步切换完成后界面长期保留旧出口的问题；离开页面或进入后台后停止刷新。
- **热点状态与响应**：Root 操作移到 IO 并串行执行，失败后显示读取到的实际设置；使用当前进程身份和源码指纹验证 DE/CE 信标，不再将 dumpsys 结果视为 Hook 已加载或硬件加速证据。
- **VPN 通道详情占位符异常**：修复通道详情弹窗中「创建应用」与「隧道内网 IP」字段直接显示字面量 `%1$s` 的问题。
- **界面显示细节**：修复深色模式下卡片边框色彩显示问题；优化双 Wi-Fi 长 SSID 文本排版与截断。

**English**

> Status: Unreleased. This section records development changes; it is not a published release.

### Added
- **Personal Hotspot Upstream Selection:** Select Cellular, Secondary Wi-Fi or System Default and request Tethering to re-evaluate its upstream. Android manages forwarding, NAT, IPv6 and offload; independent hotspot-client end-to-end behavior has not been covered.
- **Hotspot Upstream Management Card:** Displays the hotspot band, actual system upstream and upstream Hook status, with switching and offline presets. Hook status does not indicate hardware offload status.
- **Hotspot Downlink Interface Detection & Isolation:** Automatically identifies hotspot AP downlink interfaces (`ap0`, `swlan0`, `wlan2`, etc.) and marks channel type as "Personal Hotspot". Details sheet displays operating band, downlink interface name, live status, and upstream target; excludes downlink interfaces from app routing and scenario targets to avoid unreachable states.
- **VPN Virtual Interface Detection & Diagnostics:** Identifies VPN virtual interfaces (e.g. `tun0`) and marks channel type as "VPN Tunnel". Detail sheet displays creator application package and label, tunnel virtual IP address, and underlying transport network.

### Changed
- **Configuration Backup v2:** Separate app routing, complete plan selections, network policies, hotspot upstream, dual Wi-Fi preferences, appearance and test endpoints. Preview and restore selected categories, with migration from v1 files.
- **LSPosed Module Scope Expansion:** Expanded module scope beyond the system framework (`system` / `android`) to include NetworkStack / Tethering packages (`com.android.networkstack.tethering`, `com.google.android.networkstack.tethering`, `com.android.networkstack`, `com.google.android.networkstack`) for `UpstreamNetworkMonitor` hook support. Updated `scope.list` and `xposedscope` manifest metadata accordingly.
- **Dual Wi-Fi Status Overview:** Refined the "Dual Wi-Fi Activation & Management" card to display an active SSID and frequency band summary directly on the main screen.
- **Routing Target Filtering:** Excluded hotspot downlinks from app routing and scenario targets. VPN networks remain selectable; app assignment pickers restrict VPN owner UIDs and recognized VPN clients from selecting VPN channels.

### Fixed
- **Dual Wi-Fi Diagnostic State:** Read link activity and parameters from the live connection state shared by the connection card, avoiding stale detection results after connecting or disconnecting.
- **Pixel Mainline Network Selection:** Support Map-based NetworkAgent registries and cache missing reflection fields without inserting null into ConcurrentHashMap. Match explicit cellular interfaces exactly and exclude IMS from the generic cellular selection.
- **Dual Wi-Fi Requests and Saved Networks:** Request INTERNET for AOSP secondary links by band and BSSID using saved system configurations, without passwords. Normal connection and disconnection release only shell requests recorded by this module. The Xiaomi scheme reuses saved configurations and releases the native secondary WLAN on disconnect. Preserve saved networks and framework-managed interface addresses; check actual readiness after activation.
- **Duplicate Xiaomi Native Requests:** Use one synchronous root bridge call for each connection or disconnect, avoiding duplicate requests and late disconnects caused by dispatching both a broadcast and a bridge command.
- **Concurrent Configuration Updates:** Update only affected keys and preserve concurrent runtime state during rollback.
- **Dual Wi-Fi Disconnect Behavior:** Disconnect invalidates in-flight connection requests instead of restarting the service and reconnecting automatically.
- **Dual Wi-Fi Connection Lifecycle:** Coalesce overlapping reconnect attempts and release reservations after cancellation or preflight failure. Validate connection generations for service starts and notification actions, and attempt to release Xiaomi secondary connections after timeout or cancellation.
- **Late Service Start Shutdown:** Complete the foreground launch handshake before rejecting obsolete commands or shutting down, preventing a foreground startup timeout after disconnect.
- **AOSP Unsaved Network Feedback:** Prompt immediately to save the network in system Wi-Fi settings instead of waiting for an ineffective request to time out.
- **AOSP Connection Entry:** Identify saved configurations independently of extracted passwords and request saved networks by band and access point. Provide a system settings action for unsaved networks, honor manual schemes on vendor devices, and clear stale password dialogs when the selection changes.
- **Light Theme Status Bar:** Update status bar icon appearance when the app theme changes, fixing white icons on a light background when the system uses dark mode.
- **Boot Recovery for Offline Targets:** Update the recovery script from current assignments even when every target is offline, preventing stale egress assignments from being replayed at the next boot. Script deployment failures now report a failed sync.
- **Configuration Export and Restore:** Exclude secondary Wi-Fi passwords and unrelated settings from routing exports; restore manual mode and every selected plan. Clearing routing or importing selected categories preserves other settings. Validate and back up before writing, abort if backup fails, and attempt rollback with an error on write failure.
- **Cellular Upstream Selection:** Exclude IMS-only networks, respect INTERNET/DUN requirements, match explicit interfaces exactly and keep candidate selection stable.
- **Hotspot Request Lifecycle and Recovery:** Use native cellular requests while selected, restore native configuration on exit/stop and coalesce reselection after native network updates.
- **Pixel / Google Mainline Compatibility:** Support relocated `android.net.tethering` classes and marshal boot-time native configuration from the service main thread onto the Tethering Handler.
- **In-App Beacon Reads:** Read Tethering private cache from the root global mount namespace so app data isolation does not report a missing Hook while ADB diagnostics succeed.
- **Actual Upstream Display:** Refresh Channels status while visible so asynchronous upstream changes do not leave a stale interface label; stop refreshing when the page is removed or the app is backgrounded.
- **Hotspot Status and Responsiveness:** Serialize root work on IO, read settings back on failure, and validate DE/CE beacons against process identity and a source fingerprint. Dumpsys output is no longer treated as Hook or hardware-offload evidence.
- **String Format Specifier Artifacts:** Fixed an issue where `%1$s` format specifiers were rendered literally in the VPN channel detail sheet for creator app and tunnel IP rows.
- **UI Details:** Fixed card border color in dark theme and adjusted text truncation for long SSIDs.

## v1.4.1

**中文**

- **热点与双 Wi-Fi 射频互斥与联动**：
  - 支持检测本机个人热点（SoftAP）状态与频段，避免副 Wi-Fi 与热点占用相同射频产生冲突。
  - 热点关闭后，若开启了自动重连，会自动尝试恢复连接副 Wi-Fi。
- **小米 / HyperOS 双 Wi-Fi 连接优化**：
  - 优化小米/HyperOS 机型连接副 Wi-Fi 的逻辑，协同系统 `SlaveWifiService`，避免发起连接时占用主 Wi-Fi 通道导致主连接断开或连接超时。
  - 修复小米设备上部分 Wi-Fi 扫描结果信号强度显示为 -99 dBm 的问题。
- **主副 Wi-Fi 防冲突与断开逻辑完善**：
  - 限制不能将当前已连的主 Wi-Fi 作为副 Wi-Fi 连接。
  - 优化副 Wi-Fi 断开流程，清理残留的网络请求与配置，避免偶发自动重连。
- **UI 与细节调整**：
  - 优化主界面 Wi-Fi 信号图标显示效果。
  - 修复热点提示卡片在深色模式下的边框颜色显示问题。

**English**

- **Personal Hotspot Compatibility:**
  - Detect active SoftAP hotspot state and frequency band to avoid concurrent radio conflicts on the secondary Wi-Fi.
  - Automatically reconnect secondary Wi-Fi after Hotspot is turned off (when auto-reconnect is enabled).
- **Xiaomi / HyperOS Compatibility:**
  - Improve secondary Wi-Fi connection flow on Xiaomi/HyperOS via `SlaveWifiService`, preventing primary Wi-Fi disconnections and connection timeouts.
  - Fix an issue where some Wi-Fi scan results showed -99 dBm on Xiaomi devices.
- **Connection & Teardown Improvements:**
  - Prevent connecting to the active primary Wi-Fi as secondary Wi-Fi.
  - Clean up residual requests and configurations when disconnecting secondary Wi-Fi to prevent unintended auto-reconnection.
- **UI Adjustments:**
  - Refine main screen Wi-Fi signal icon style.
  - Fix card border color in dark theme when hotspot is active.

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

已验证：Android 16 与 Android 17（HyperOS）+ KernelSU + LSPosed v2.2.0。详细命令、实测输出与未覆盖项保存在本地验证记录中，不纳入 Git。

Verified on Android 16 and Android 17 (HyperOS) with KernelSU and LSPosed v2.2.0. Detailed commands,
observations and coverage gaps are maintained in local verification notes excluded from Git.
