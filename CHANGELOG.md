# Changelog

<!--
Release notes are generated from the matching `## vX.Y.Z` section, verbatim. Keep every section
bilingual, Chinese first: the release page is what most users read.
-->

## Unreleased

**中文**

- **场景方案在应用未运行时也生效**：模块在 `system_server` 侧监听链路变化，仅在存在方案时唤醒应用重新评估
  （没有方案就保持休眠）；应用进程被杀时同样会切换。
- 方案支持**编辑覆盖应用**（编辑态下的指派写入方案而不是默认方案）、**改名与触发条件**、**调整顺序**
  （顺序即判定优先级）。

**English**

- **Scenario plans now apply while the app is not running.** The module watches link changes inside
  `system_server` and wakes the app to re-evaluate, but only while a plan exists - without one the app stays
  asleep. The app's own callback is no longer the only trigger.
- Plans can be **re-edited** (in edit mode assignments go into the plan instead of the default plan),
  **renamed**, have their **condition changed**, and be **reordered** - the stored order is the priority order.

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
