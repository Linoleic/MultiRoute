# MultiRoute

[English](README.md) | [简体中文](README_CN.md)

[![build](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml/badge.svg)](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Linoleic/MultiRoute?label=release)](https://github.com/Linoleic/MultiRoute/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20%28%20verified%20on%2016%20%2F%2017%20%29-green.svg)](https://developer.android.com)
[![LSPosed](https://img.shields.io/badge/LSPosed-module%20page-orange.svg)](https://modules.lsposed.org/module/io.github.linoleic.multiroute)
[![LibXposed API](https://img.shields.io/badge/LibXposed-min%20101%20%C2%B7%20target%20102-orange.svg)](https://github.com/libxposed)

**An LSPosed module that gives every app its own network channel.** Assign each app to the link it
should use — primary Wi-Fi, secondary Wi-Fi (dual Wi-Fi), cellular or Ethernet — and let them
communicate **at the same time**. Egress is enforced with kernel policy routing (`ip rule`), not through
a userspace VPN.

> **Requires root** (KernelSU / Magisk / APatch) and **LSPosed**, scoped to the **system framework**
> (`system_server`).

---

## Overview

Android routes every application through one system-wide *default network*. Even with dual Wi-Fi and
mobile data connected at once, an app can only use the system's current default unless it calls
low-level binding APIs itself.

MultiRoute lifts that restriction per app:

```
        ┌──────────────────────── MultiRoute (UI) ────────────────────────┐
        │  per-app channel assignment · batch mode · egress diagnostics   │
        └───────────────┬─────────────────────────────┬───────────────────┘
                        │                             │
        ┌───────────────▼──────────────┐   ┌──────────▼───────────────────┐
        │  Framework layer (LSPosed)   │   │  Kernel layer (root)         │
        │  system_server hooks         │   │  policy routing tables       │
        │  · ConnectivityService       │   │  · pref 14500 per-UID egress │
        │  · Dual Wi-Fi & keepalive    │   │  · pref 14400 LAN bypass     │
        │                              │   │  · nat DNS redirect :53      │
        │                              │   │  · IPv4 + IPv6               │
        └──────────────────────────────┘   └──────────┬───────────────────┘
                                                      │
        ┌─────────────────────────────────────────────▼───────────────────┐
        │  Primary Wi-Fi  │  Secondary Wi-Fi  │  Cellular  │  Ethernet     │
        └─────────────────────────────────────────────────────────────────┘
```

---

## Module information

| Field | Value |
| :-- | :-- |
| Package name | `io.github.linoleic.multiroute` |
| Module name | MultiRoute |
| Listing | [modules.lsposed.org/module/io.github.linoleic.multiroute](https://modules.lsposed.org/module/io.github.linoleic.multiroute) |
| Xposed API | `minApiVersion 101`, `targetApiVersion 102` (LibXposed) |
| Scope | `system` — the system framework (`system_server`) **only** |
| `staticScope` | `false` (the scope list ships in the APK metadata) |
| `autoHotReload` | `true`, but the system framework cannot actually hot-reload → reboot, or restart `system_server` |
| Root | required: KernelSU, Magisk or APatch |

The APK carries `META-INF/xposed/{module.prop, scope.list, java_init.list}`, so LSPosed reads the scope and
entry point from the package itself.

---

## Requirements & Compatibility

| Requirement | Details |
| :-- | :-- |
| Root | KernelSU, Magisk or APatch (policy routing tables need root) |
| Xposed | LSPosed (or another framework implementing LibXposed API 101+) |
| Module scope | **System framework only** (`system` / `system_server`) |
| Android | Verified on **16** and **17** (HyperOS). The interfaces the module hooks — `getMobileDataPreferredUids` among them — only exist from Android 11 on, so that is the realistic floor; nothing below that is supported |

| Android version | Status |
| :-- | :-- |
| **Android 17 / HyperOS** | ✅ Verified on a Xiaomi HyperOS phone with KernelSU and LSPosed v2.2.0: dual Wi-Fi + cellular, IPv4/IPv6 rules, LAN bypass, clone routing, boot recovery, module status, screen-off keep-alive |
| **Android 16 / HyperOS** | ✅ Verified on a Xiaomi HyperOS tablet with KernelSU and LSPosed v2.2.0: dual Wi-Fi per-app routing (IPv4 + IPv6), clone-space separation, real-traffic egress, recovery after a soft reboot, DNS redirect, module status. The tablet has no cellular interface, so cellular routing was not exercised there |
| Android 11 – 15 | ⚠️ **Not verified.** The APIs it hooks exist from Android 11 on, so it is plausible, but nothing has been tested — treat it as unknown rather than supported |
| Android 7 – 10 | ❌ **Not supported.** The APK requires Android 11 (`minSdk` 30), and those releases lack the framework interfaces the module hooks |

Detailed command transcripts and verification notes are maintained locally and excluded from Git.
The table above summarizes verified coverage; other combinations remain unverified.

---

## Features

- **Per-app channel assignment** — pick a channel for any app; its traffic egresses through that link
  while other apps keep using theirs.
- **Independent configuration for cloned apps** — app-clone spaces (e.g. Xiaomi XSpace, user 999) and
  work profiles are listed as separate entries (`WeChat (999)`), so a clone and its primary install can
  use *different* channels.
- **Per-app DNS that follows the channel** — queries of an assigned app are redirected to that channel's
  resolver, so name resolution does not stay behind on the default network (or inside a VPN tunnel).
- **Scenario plans** — save the current assignment as a named plan with a condition (a Wi-Fi name picked
  from the networks connected right now, two Wi-Fi links, cellular only, always, or manual only). When a plan
  matches it overrides the apps it mentions and everything else keeps the default assignment; several plans
  can coexist with a priority order, one can be pinned by hand, and a plan can also put an app back on the
  system default. The routing screen shows the active plan, plans can be renamed, reordered and re-edited,
  and the module wakes the app when a link changes, so a plan also switches while the app itself is not
  running.
- **Dual-stack** — IPv4 and IPv6 rules are installed together.
- **LAN bypass** — on-link subnets are pinned to the interface that owns them, so intranet devices
  (NAS, printers, casting) stay reachable from apps assigned elsewhere.
- **Automatic boot recovery** — rules survive reboots: a `service.d` script, a wake-up broadcast from the
  module, and the app's own network callback cover each other; restored on locked devices before first unlock.
- **Automatic failover** — when an assigned link goes down, traffic automatically reroutes through the default network, and seamlessly migrates back once restored, with configurable hold-off debounce and lightweight connectivity probes.
- **Built-in Dual Wi-Fi Manager** — bypasses system restrictions on secondary WLAN with 4 adaptation schemes (Auto, Xiaomi Slave, AOSP Overlay, Root Direct `SYSTEM_UID`), multi-band SSID isolation, and BSSID penalty reset. On Xiaomi devices, keepalive daemons are automatically engaged on connection.
- **Channel details** — interface, addresses, gateway, DNS, MTU, metered state, SSID and **Wi-Fi band and
  channel**, plus a per-channel public-IP probe with presets or a custom endpoint.
- **Appearance and language** — theme (follow system / light / dark, animated), Material You colour, and
  per-app language (follow system / 简体中文 / English).
- **Module status** — reports precisely whether the hooks are installed, whether `system_server` is
  still running an older build, or whether the status record is stale.
- **Comprehensive system diagnostics** — diagnostic dialog supports one-tap copy, SAF file export, and system sharing, covering dual Wi-Fi status, route tables and gateways, upstream DNS and Private DNS (DoT), rule effectiveness, and filtered logcat output.
- **No VPN** — traffic is routed in the kernel; no userspace TCP/IP stack, so throughput and latency stay
  close to native.

---

## Dual Wi-Fi Management & Device Compatibility

In stock Android, the framework maintains only a single active default internet gateway. While many modern smartphones and tablets feature **DBS (Dual-Band Simultaneous)** dual-radio Wi-Fi hardware, OEM ROMs frequently lock secondary Wi-Fi interfaces behind vendor-restricted features or tear them down aggressively during screen-off sleep.

MultiRoute includes a built-in **Dual Wi-Fi Assistant (STA + STA Concurrency Manager)** that combines hardware chipset probing, tiered adaptation schemes, frequency conflict detection, and background keep-alive to enable simultaneous, independent dual-Wi-Fi connections across diverse device brands.

### 1. Tiered Adaptation Schemes

| Scheme | Mechanism | Target & Advantages |
| :--- | :--- | :--- |
| **Auto (Tiered Adaptation)**<br>`AUTO` | Automatically inspects manufacturer, ROM type, Wi-Fi chipset driver, and network interfaces to pick the optimal scheme. | **Default & Recommended**. Works out-of-the-box with graceful fallbacks. |
| **Xiaomi / HyperOS Dedicated**<br>`XIAOMI_SLAVE` | Coordinates with Xiaomi's proprietary `SlaveWifiService` / `DualStaImpl` driver and injects LSPosed hooks to prevent screen-off disconnection. | Xiaomi / Redmi / POCO stock ROMs with native dual-WLAN integration and screen-off keepalive. |
| **AOSP / Pixel Standard**<br>`AOSP_OVERLAY` | Leverages native Android 13+ AOSP Multi-Internet mode (RRO Overlay activating `config_wifiMultiStaMultiInternetConcurrencyEnabled` in Multi-AP mode 2). | AOSP custom ROMs (LineageOS, crDroid, PixelOS, etc.) or devices running clean AOSP frameworks. |
| **Generic Root Force Scheme**<br>`ROOT_DIRECT` | Uses Root (UID 0) and `SYSTEM_UID` privileges to register requests directly into the Android Wi-Fi stack, bypassing Framework Overlay requirements and system prompt dialogs. | **Universal penetration**. Works across OPPO/OnePlus (ColorOS), vivo/iQOO (OriginOS), Samsung (One UI), and other heavily modified OEM ROMs. |

### 2. Hardware & Device Requirements

* **System Requirements**:
  * **Android 13 (API 33) or higher** (AOSP 13+ introduced native Multi-Internet and multi-ClientModeManager architecture).
  * **Root Access** (KernelSU / APatch / Magisk).
* **Hardware Radio Requirements (DBS / Dual STA Supported)**:
  * **Qualcomm Snapdragon (FastConnect 6700 / 6800 / 6900 / 7800 / 7900)**:
    * Flagship: Snapdragon 888 / 8 Gen 1 / 8+ Gen 1 / 8 Gen 2 / 8 Gen 3 / 8 Elite;
    * Upper-Midrange: Snapdragon 7+ Gen 2 / 7+ Gen 3 / 8s Gen 3 / 7 Gen 3, etc.
  * **MediaTek Dimensity (Filogic)**:
    * Dimensity 8100 / 8200 / 8300 / 9000 / 9200 / 9300 / 9400 and related chipsets with dual-Wi-Fi acceleration.
  * **Google Tensor**:
    * Pixel 6 / 7 / 8 / 9 series (Broadcom BCM4389 / BCM4398 DBS chipsets).
  * **Samsung Exynos**:
    * Exynos 2100 / 2200 / 2400 flagship series.
* **Unsupported Devices**:
  * Budget or legacy chipsets equipped with only a single physical Wi-Fi radio/antenna (e.g. entry-level Snapdragon 4/6 series, low-end Helio/Unisoc);
  * Android 12 and below (lacks the AOSP multi-internet routing framework).

### 3. Usage Notes & Troubleshooting

1. **Radio Band Complementarity**:
   * DBS chipsets require the two connected Wi-Fi networks to operate on **complementary non-overlapping frequency bands** (e.g. 2.4GHz + 5GHz, or 5GHz Low 5.2GHz + 5GHz High 5.8GHz);
   * Attempting concurrency on the exact same physical radio channel causes radio contention failure. The Wi-Fi list automatically tags candidates as "Recommended" or "Band Conflict".
2. **Same-SSID Multi-Band Disambiguation**:
   * For dual-band unified routers broadcasting identical SSIDs across 2.4GHz and 5GHz, MultiRoute disambiguates APs by their BSSID and frequency band, keeping primary and secondary connection states isolated.
3. **Resetting Temporary Network Penalties**:
   * Android may temporarily place an access point on an internal disable/blocklist after password mismatches or failed handshakes. Use the "Reset Network Temporary Restrictions" card button to clear the penalty blocklist instantly.

---

## Installation

1. Install the APK from the [releases page](https://github.com/Linoleic/MultiRoute/releases) — the asset
   is named `MultiRoute-<VersionCode>-<VersionName>.apk` — or install it straight from the LSPosed
   manager's module list, since the module is in the
   [official repository](https://modules.lsposed.org/module/io.github.linoleic.multiroute). Either way the
   manager can offer updates in place, from the manifest the module declares.
2. Open **LSPosed Manager → Modules → MultiRoute**, enable it and set the scope to **System Framework**
   (`system`). The APK already declares this in `META-INF/xposed/scope.list`.
3. Reboot, or restart the system server: `su -c 'setprop ctl.restart zygote'`.
4. Open MultiRoute and grant root. The **Settings** tab should report *activated (hooks ready)*.
   If it reports *loaded older build*, restart the system server once more.
5. For routing apps over cellular while Wi-Fi is connected, enable **Mobile data always on**
   (developer options) or the ROM's own "keep cellular active" setting.

### Upgrading from 1.1.x

The application id changed from `com.multiroute` to `io.github.linoleic.multiroute`, because the module
repository only accepts a package namespace the author can prove ownership of. Android treats that as a
different app:

1. Uninstall the old version first — assignments are **not** carried over.
2. Install the new APK, then enable **the new package** in LSPosed Manager and set its scope to the system
   framework. It appears as a new module entry; the old, now-uninstalled one can be removed.

`tools/probe` (the measurement helper app) is intentionally left as `com.multiroute.probe`.

### Uninstalling / cleaning up

1. In the app: **App routing → Clear all app routing rules**. This removes the kernel rules, the
   generated boot script and the rule cache in one go.
2. Disable the module in LSPosed Manager and reboot.
3. If the app could not run before you removed it (for example the device was wiped), the leftovers can
   be removed manually:

```bash
su -c 'rm -f /data/adb/service.d/00-multiroute-restore.sh /data/system/multiroute_rules_cache'
su -c 'while ip rule del pref 14500 2>/dev/null; do :; done; while ip rule del pref 14400 2>/dev/null; do :; done'
su -c 'iptables -t nat -D OUTPUT -j MULTIROUTE_DNS 2>/dev/null; iptables -t nat -F MULTIROUTE_DNS 2>/dev/null; iptables -t nat -X MULTIROUTE_DNS 2>/dev/null'
```

---

## Usage

| Tab | What you do |
| :-- | :-- |
| **App routing** | Tap an app and choose a channel; a long press enters multi-select for batch assignment; clone apps listed independently; scenario profiles for automated switching based on network conditions. |
| **Channels** | Inspect every active link (interface, IP, gateway, DNS, MTU, SSID, Wi-Fi band and channel); built-in **Dual Wi-Fi Manager** (4 adaptation schemes, multi-band isolation, BSSID blocklist reset); per-channel egress probes. |
| **Settings** | Module and root status, active kernel rules, cellular keepalive, DNS redirection, auto failover hold-off, config backup/restore, diagnostic log export/share/copy, and rule purge maintenance. |

---

## How it works

**Kernel layer (root).** Each network interface has its own routing table maintained by Android's
`netd`. MultiRoute installs:

- `ip rule add uidrange <uid>-<uid> lookup <iface> pref 14500` — one rule per assigned UID, for IPv4
  and IPv6;
- `ip rule add to <on-link prefix> lookup <iface> pref 14400` — LAN bypass, generated from each
  channel's own connected prefixes.

**DNS.** Android chooses the resolver from the *default* network regardless of where the kernel routes
the packets, so an assigned app could query a resolver that is only reachable over another link — or keep
querying one inside a VPN tunnel. MultiRoute therefore redirects **UDP/TCP port 53** of assigned UIDs to
their own channel's resolver, through a dedicated `MULTIROUTE_DNS` chain in the `nat` table. Port 853
(DoT) is deliberately left alone, and DoH is ordinary HTTPS that already follows the route. The boot
script flushes that chain and the app re-applies it with the resolvers of the current network, so a stale
address can never break resolution.

**Framework layer (LSPosed).** Hooks inside `system_server` keep the *app-visible* network state
consistent with the assignment (`ConnectivityService.getDefaultNetworkForUid`,
`getActiveNetworkForUidInternal`, `getMobileDataPreferredUids`); when connecting via Xiaomi Dedicated scheme, it coordinates hooks to keep the secondary Wi-Fi link alive while the screen is off.

**Boot recovery.** Rules live in the kernel, so they must be restored after a reboot. Three mechanisms
cover each other: a generated `service.d` script (applied as soon as the interfaces are ready), a
wake-up broadcast sent by the module to the app, and the app's own network callback. On a device that is
still locked after a reboot the app cannot run yet — its credential-encrypted storage is unavailable and
broadcasts are not delivered before the first unlock — so the root `service.d` script is then the only
path that restores rules. That is why it is treated as the primary mechanism rather than a fallback.

**Rule cache.** Until the app has been started, LSPosed's remote preferences cannot be read. The app
therefore publishes a UID→interface cache so the hooks know the rules from the first second of a boot.

---

## Module status

The Settings tab shows one of:

| Status | Meaning |
| :-- | :-- |
| Activated (hooks ready) | Hooks installed, loaded build matches the installed APK |
| Loaded, hooks missing | Injection worked but `ConnectivityService` hooks were not installed |
| Loaded older build | `system_server` still runs a previous build — restart required |
| Loaded (legacy marker) | Module is present but reports no hook detail (older module build) |
| Status record expired | The recorded owner process is no longer `system_server` |
| Not activated | Module disabled, not scoped to the system framework, or not injected |

---

## Troubleshooting

- **Module shows "not activated"** — enable it in LSPosed Manager, scope it to the **system framework**,
  then reboot or restart `system_server`.
- **Module shows "loaded older build" after an update** — expected: the framework cannot hot-reload.
  Reboot, or `su -c 'setprop ctl.restart zygote'`.
- **An assignment has no effect** — check **Settings → View diagnostic log** (supports clipboard copy, file export, or system share): section *rule effectiveness* shows every configured UID with *in effect* or *not in effect*. A channel whose interface has no default route at that moment is skipped, and the log says so.
- **Rules disappeared after a reboot** — check the boot-recovery log in the same snapshot
  (section 3, `/data/adb/multiroute/last_boot_sync.log`). Also confirm KernelSU/Magisk/APatch executes
  `service.d` scripts on this ROM.
- **Secondary Wi-Fi channel is missing** — ensure the hardware supports dual Wi-Fi (DBS architecture) and runs Android 13+. In MultiRoute, open **Channels → Dual Wi-Fi Manager** to check capabilities and pick the appropriate adaptation scheme (Generic Root Force Scheme for OEM ROMs).
- **DNS does not behave as expected** — only port 53 is redirected. If you use **Private DNS (DoT)** or
  DoH, that traffic is not rewritten; on a VPN it can therefore still resolve through the tunnel.
- **Assignment seems to do nothing while a VPN is active** — see the VPN note under *Known limitations*.
- **Wi-Fi band is not shown** — the device did not report a frequency for that link; both the platform
  and the `dumpsys` fallback returned nothing.
- **Public-IP probe times out** — switch the preset in Settings to a reachable endpoint or set a custom
  URL.

---

## Privacy

MultiRoute collects nothing and sends nothing: no telemetry, no analytics, no crash reporting, no
advertising and no account of any kind. **No IP address, device identifier, app list or rule set is ever
transmitted anywhere.** The dependency list is AndroidX/Compose plus the LibXposed API, nothing else.

- **Everything stays on the device.** Assignments live in the app's private preferences and in the kernel
  rules, with a small UID→interface cache for the hooks.
- **Outbound requests are strictly transparent and user-controlled:**
  - **The egress probe on the Channels screen** only queries when manually started by you;
  - **The "Internet connectivity validation" under Auto-Failover** (configurable in Settings): when a channel connects or recovers, it optionally sends a single lightweight HTTP 204 request (a few hundred bytes, 0-byte body) through that channel to verify real Internet access, preventing routing stalls on captive portals, ISP dropouts, or local-only networks. It never polls continuously in the background and carries no device identifiers, app lists, or rule contents;
  - If you never run the egress probe and keep Internet validation turned off, the app makes **no network requests at all**.
- **Root access is scoped to routing.** `su` is used to add and remove policy-routing rules, to write the
  boot-recovery script and to read the resolver/boot-log details shown in diagnostics. Nothing is executed
  on behalf of anyone else and nothing is reported back.
- **The app list is read, never reported.** `QUERY_ALL_PACKAGES` exists so the app can list installed apps
  (and their clone-space instances) for assignment.

---

## Known limitations

- **Only port 53 is redirected.** Classic DNS follows the assigned channel; Private DNS (DoT, 853) and
  DoH are not rewritten. Measured with a full-tunnel VPN active: name resolution could still stay inside
  the tunnel while the app's data traffic left it.
- **VPN behaviour depends on the VPN's mode.** Measured on device with a full-tunnel client: its
  per-UID capture rules land at `24000`, i.e. *below* MultiRoute's `14400`/`14500`, so an assigned app
  leaves the tunnel (verified: its connections then use the assigned link's address). An always-on VPN
  with *block connections without VPN* uses Android's `13000`/`14000` rules instead, which are *above*
  MultiRoute's — there the VPN keeps precedence and the assignment does nothing. Assigning an app while a
  VPN is active therefore takes that app **out of the tunnel**.
- **The platform's per-UID network selection is below MultiRoute** (`15040`-range), so a channel
  assignment does override that.
- **Cloned-app lookup needs the root package listing.** The numeric space id is shown instead of the
  space name; only ROMs whose clone spaces are real Android users are covered.
- **Secondary Wi-Fi screen-off keepalive and device compatibility**: the legacy keepalive toggle has been consolidated into the **Dual Wi-Fi Assistant**. When connecting to secondary Wi-Fi using the "Xiaomi / HyperOS Dedicated" scheme, the module automatically activates the keepalive hooks targeting Xiaomi's private classes (`SlaveWifiService`, `DualStaImpl`) in the background, without requiring a manual toggle in Settings. On other platforms (AOSP, ColorOS, OriginOS, One UI, etc.), the assistant relies on native multi-network architecture (`AOSP_OVERLAY`) or Root direct connection (`ROOT_DIRECT`) combined with a foreground service to maintain concurrency.
- **Cellular assignment** relies on the ROM honouring `mobile_data_preferred_uids`.
- **Which interface sits on which band can change while the app is running** — that is exactly what the
  Wi-Fi band row is for.
- **Cleartext HTTP is permitted for the egress probe.** Built-in presets use HTTPS, but a custom endpoint
  may be plain HTTP (for example a router page such as `http://192.168.1.1/ip`), which is why
  `usesCleartextTraffic` remains enabled.
- **Android 7 – 10 are not supported.** The APK requires Android 11 (`minSdk` 30) and those releases do
  not have the framework interfaces the module hooks.
- Verification so far covers a limited set of devices and ROM versions (see the compatibility table);
  other ROMs may differ.

---

## Building

```bash
# Debug / release APK
./gradlew assembleDebug
./gradlew assembleRelease

# Unit tests (78 tests, pure logic only)
./gradlew testDebugUnitTest
```

- JDK 17, Android SDK Platform 36; `minSdk` 30 (Android 11).
- Signing is optional: copy `keystore.properties.example` to `keystore.properties` and fill it in.
  Without it, release builds fall back to debug signing (with a warning).
- Artifact: `app/build/outputs/apk/release/app-release.apk`.
- `tools/probe` is a separate tiny app used to measure per-UID egress on a device; it is not part of the
  module.

### Versioning and releases

`versionName` comes from the latest git tag and `versionCode` is the commit count of the upstream branch
plus a fixed offset (`10000`). Counting `origin/master` rather than the local HEAD means CI, a fresh clone
and a fork with extra commits all produce the same version for the same upstream state. A working tree
with uncommitted changes is named `-local`.

Releases are published by pushing a tag named **`<versionCode>-<versionName>`** — the format the Xposed
module repository indexes. The
[release workflow](.github/workflows/release.yml) then builds, signs, verifies that the APK matches
`xposed_update.json`, and publishes the release with the APK renamed to
`MultiRoute-<VersionCode>-<VersionName>.apk`. A tag that does not match the built version fails the run
with the exact retag command.

Override both values for your own numbering:

```bash
./gradlew assembleRelease -PmultiRouteVersionName=1.1.0-fork -PmultiRouteVersionCode=19999
```

A self-built APK is signed with your own key rather than the project's, so Android refuses to install it
on top of a published release — uninstall the released version first, or sign with the same key.

---

## Disclaimer

This is a **root** networking module: it modifies kernel routing rules and injects into the system
framework. Use it at your own risk. Sending traffic over cellular may incur **carrier charges**, and
routing apps differently may conflict with your carrier's terms — you are responsible for how you use
it. Keep a way to recover (recovery boot / disable the module) before experimenting.

---

## License

[GNU General Public License v3.0](LICENSE).

## Acknowledgements

- [LibXposed API](https://github.com/libxposed) and [LSPosed](https://github.com/LSPosed/LSPosed) for the
  framework interfaces this module builds on.
- Jetpack Compose and Material 3 for the UI.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).
