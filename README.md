# MultiRoute

[English](README.md) | [简体中文](README_CN.md)

> This document describes the current development branch, including unreleased v1.5.0 changes. Published builds are listed on the [releases page](https://github.com/Linoleic/MultiRoute/releases).

[![build](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml/badge.svg)](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Linoleic/MultiRoute?label=release)](https://github.com/Linoleic/MultiRoute/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20%28%20verified%20on%2016%20%2F%2017%20%29-green.svg)](https://developer.android.com)
[![LSPosed](https://img.shields.io/badge/LSPosed-module%20page-orange.svg)](https://modules.lsposed.org/module/io.github.linoleic.multiroute)
[![LibXposed API](https://img.shields.io/badge/LibXposed-min%20101%20%C2%B7%20target%20102-orange.svg)](https://github.com/libxposed)

MultiRoute is an Android LSPosed module with three functions: per-app policy routing, secondary Wi-Fi connection management, and personal hotspot upstream selection. It uses Linux policy-routing rules and hooks in Android framework and Tethering components. Device and ROM support varies by function.

> **Requires root** (KernelSU / Magisk / APatch) and **LSPosed**, scoped to the **system framework**
> (`system_server`) and the **NetworkStack tethering packages**.

---

## Overview

Android can maintain several network links, while many applications use the system default network unless they bind explicitly. Secondary Wi-Fi and tethering behavior also varies by Android version, ROM and device configuration.

MultiRoute combines Android framework hooks with Linux policy-routing rules:

```
        ┌──────────────────────── MultiRoute (UI) ────────────────────────┐
        │ App Routing · Dual Wi-Fi Manager · Hotspot Steering · Diagnostics │
        └───────────────┬─────────────────────────────┬───────────────────┘
                        │                             │
        ┌───────────────▼──────────────┐   ┌──────────▼───────────────────┐
        │  Framework layer (LSPosed)   │   │  Kernel layer (root)         │
        │  system_server & NetworkStack│   │  Policy routing & NAT chains │
        │  · ConnectivityService hook  │   │  · pref 14500 per-UID egress │
        │  · Dual Wi-Fi & keepalive    │   │  · pref 14400 LAN bypass     │
        │  · Hotspot upstream steering │   │  · IPv4 nat UDP/TCP 53 DNS   │
        │  · Automated failover rules  │   │  · IPv4 + IPv6 dual-stack    │
        └──────────────────────────────┘   └──────────┬───────────────────┘
                                                      │
        ┌─────────────────────────────────────────────▼───────────────────┐
        │  Primary Wi-Fi  │ Secondary Wi-Fi (DBS) │  Cellular  │  Ethernet │
        └───────┬───────────────┬───────────────────┬─────────────┬───────┘
                │               │                   │             │
                │ Per-App Route │ Per-App / Hotspot │ App/Hotspot │ LAN Bypass
                ▼               ▼                   ▼             ▼
        ┌───────────────┐┌───────────────┐ ┌─────────────────┐ ┌───────────┐
        │ Browser/Video ││ Local/Casting │ │ Hotspot Clients │ │ NAS/Print │
        └───────────────┘└───────────────┘ └─────────────────┘ └───────────┘
```

### Three Core Capabilities

1. **Per-App Policy Routing**:
   - Assign any app to a dedicated channel (Primary Wi-Fi, Secondary Wi-Fi, Cellular, Ethernet), with independent assignment support for dual/cloned app spaces.
   - Enforced via Linux kernel policy routing (`ip rule`) without userspace VPN overhead; includes IPv4 UDP/TCP port 53 DNS rewriting, on-link LAN bypass, and automatic failover fallback on connection loss.

2. **Dual Wi-Fi Activation & Management (STA + STA Concurrency)**:
   - Provides adaptation schemes for secondary Wi-Fi connection management. Dual-link operation depends on the device's supported radio combinations, firmware and ROM.
   - Provides connection methods for secondary Wi-Fi, with SSID/BSSID handling and Xiaomi-specific keepalive hooks. Results depend on the device, ROM and Wi-Fi hardware.

3. **Personal Hotspot Upstream Steering**:
   - Requests that Android Tethering select Cellular, Secondary Wi-Fi, or System Default as the hotspot upstream.
   - Upstream changes are re-evaluated asynchronously. Forwarding, NAT, IPv6 and offload remain managed by Android; behavior depends on the system.

---

## Module information

| Field | Value |
| :-- | :-- |
| Package name | `io.github.linoleic.multiroute` |
| Module name | MultiRoute |
| Listing | [modules.lsposed.org/module/io.github.linoleic.multiroute](https://modules.lsposed.org/module/io.github.linoleic.multiroute) |
| Xposed API | `minApiVersion 101`, `targetApiVersion 102` (LibXposed) |
| Scope | System framework (`system` / `android`) and NetworkStack packages (`com.android.networkstack.tethering`, etc.) |
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
| Module scope | **System framework** (`system` / `system_server`) and **NetworkStack** (`com.android.networkstack.tethering`, etc. for hotspot upstream steering) |
| Android | Android **11** or later; verified devices and features are listed below |

| Android version | Status |
| :-- | :-- |
| **Android 17 / HyperOS (Xiaomi 15)** | Verified dual Wi-Fi, concurrent cellular connectivity, app and clone routing, IPv4/IPv6 routing and rule recovery |
| **Android 16 / HyperOS (Xiaomi tablet)** | Verified dual Wi-Fi, app and clone routing, IPv4/IPv6 routing, DNS redirection and rule recovery; no cellular hardware |
| **Android 16 / AOSP (Pixel 7 Pro)** | Verified connection, disconnection and IPv4 Internet access through saved secondary Wi-Fi networks; other features are not comprehensively verified |
| Android 11 – 15 | ⚠️ **Not verified.** |
| Android 7 – 10 | ❌ The APK requires Android 11 (`minSdk` 30) |

Dual Wi-Fi and hotspot support also depend on hardware, firmware and ROM; Android version alone does not establish compatibility.

---

## Features

- **Per-app channel assignment** — pick a channel for any app; its traffic egresses through that link
  while other apps keep using theirs.
- **Independent configuration for cloned apps** — app-clone spaces (e.g. Xiaomi XSpace, user 999) and
  work profiles are listed as separate entries (`WeChat (999)`), so a clone and its primary install can
  use *different* channels.
- **Per-app DNS redirection** — assigned apps' IPv4 DNS traffic on UDP/TCP port 53 is redirected to
  the selected channel's resolver. IPv6 DNS, Private DNS (DoT) and DNS-over-HTTPS (DoH) are not rewritten; VPN
  behavior depends on its mode (see Known limitations).
- **Scenario plans** — save the current assignment as a named plan with a condition (a Wi-Fi name picked
  from the networks connected right now, two Wi-Fi links, cellular only, always, or manual only). When a plan
  matches it overrides the apps it mentions and everything else keeps the base assignment. Matching plans
  are layered in priority order; a later plan overrides an earlier plan for the same app. Manual mode can
  select several plans and ignores their conditions. A plan can also put an app back on the system default.
  The routing screen shows the active plans; plans can be renamed, reordered and re-edited,
  and the module wakes the app when a link changes, so a plan also switches while the app itself is not
  running.
- **Dual-stack routing** — IPv4 and IPv6 policy-routing rules are installed. DNS redirection currently applies to IPv4 UDP/TCP port 53 only.
- **LAN bypass** — on-link subnets are pinned to the interface that owns them, so intranet devices
  (NAS, printers, casting) stay reachable from apps assigned elsewhere.
- **Boot rule recovery** — a `service.d` script waits for target interfaces and applies routing rules once; app and module callbacks handle later changes. This restores routing rules, not secondary Wi-Fi connections, and timing depends on interface availability and ROM behavior.
- **Automatic failover** — when an assigned link becomes unavailable, the module can route through the default network and reapply the assignment when the link returns. Timing depends on link detection and the configured hold-off.
- **Personal Hotspot Upstream Selection** — asks Android Tethering to re-evaluate Cellular, Secondary Wi-Fi, or System Default asynchronously. Forwarding, NAT, IPv6 and offload are system-managed.
- **Hotspot and VPN channel details** — identifies hotspot downlinks and VPN virtual interfaces and displays related properties. Hotspot downlinks are excluded from app-routing and scenario targets. VPN networks remain selectable; app assignment pickers restrict VPN owner UIDs and recognized VPN clients from selecting VPN channels. The actual traffic path depends on the VPN's routing policy.
- **Built-in Dual Wi-Fi Manager** — manages secondary WLAN connections through available adaptation schemes, with multi-band SSID handling and a temporary network-restriction reset. On supported Xiaomi ROMs it activates hooks intended to maintain the link; results vary by device and ROM.
- **Channel details** — interface, addresses, gateway, DNS, MTU, metered state, SSID and **Wi-Fi band and
  channel**, plus a per-channel public-IP probe with presets or a custom endpoint.
- **Appearance and language** — theme (follow system / light / dark, animated), Material You colour, and
  per-app language (follow system / 简体中文 / English).
- **Configuration backup and restore** — v2 JSON covers routing, complete plan selections, network policies, hotspot upstream preferences, non-secret dual Wi-Fi information, appearance and test endpoints. Preview and restore selected categories, including older v1 files. Network names and test URLs are included; Wi-Fi passwords, UID caches and logs are excluded. Import does not start the hotspot or connect secondary Wi-Fi, and device-specific schemes are checked against the destination manufacturer.
- **Module status** — reports whether hooks are installed, whether `system_server` is
  still running an older build, or whether the status record is stale.
- **System diagnostics** — diagnostic dialog supports one-tap copy, SAF file export, and system sharing, covering dual Wi-Fi status, route tables and gateways, upstream DNS and Private DNS (DoT), rule status, and filtered logcat output.
- **No VPN** — traffic is routed in the kernel; no userspace TCP/IP stack overhead.

---

## Dual Wi-Fi Management & Device Compatibility

Android can maintain multiple links, but many apps use the active default network unless they bind explicitly. Secondary Wi-Fi availability and screen-off behavior vary by Wi-Fi hardware, firmware and ROM.

MultiRoute includes a built-in **Dual Wi-Fi Assistant (STA + STA Concurrency Manager)** that combines hardware concurrency detection, tiered adaptation schemes, and keep-alive coordination.

### 1. Tiered Adaptation Schemes

| Scheme | Mechanism | Target & Notes |
| :--- | :--- | :--- |
| **Auto (Tiered Adaptation)**<br>`AUTO` | Automatically inspects manufacturer, ROM type, Wi-Fi chipset driver, and network interfaces to pick an applicable scheme. | **Default option**. Adjusts based on device environment. |
| **Xiaomi / HyperOS Dedicated**<br>`XIAOMI_SLAVE` | Coordinates with Xiaomi's `SlaveWifiService` / `DualStaImpl` and uses LSPosed hooks to attempt to keep the secondary link active. | Xiaomi / Redmi / POCO ROMs; screen-off behavior depends on the device and ROM. |
| **AOSP Multi-Internet**<br>`AOSP_OVERLAY` | Applies an overlay that forces Google's `config_wifiMultiStaMultiInternetConcurrencyEnabled` resource and requests Multi-Internet mode 2. | Candidate for AOSP-based systems; overlay resource names and behavior vary by ROM. Not broadly verified. |
| **Root direct**<br>`ROOT_DIRECT` | Uses the current `set-multi-internet-mode 2` activation path. It does not bypass Wi-Fi hardware or firmware concurrency limits. | Candidate for ROMs where this system command supports the requested mode; not broadly verified. |

AOSP and generic Multi-Internet schemes connect only to networks already saved by the system. Save a new access point in system Wi-Fi settings before connecting. Saved networks use the system configuration; MultiRoute does not send credentials in its connection request. Unsaved networks offer an action to open system Wi-Fi settings.

### 2. Hardware & Device Requirements

* **AOSP Multi-Internet scheme**: Android 13 (API 33) or later is required for the AOSP mechanism described above. This is not a general minimum for every MultiRoute function or connection scheme.
* **Wi-Fi concurrency**: behavior depends on the device's Wi-Fi hardware, firmware, system configuration and ROM. Root access alone cannot add unsupported radio concurrency.
* **Device compatibility**: ROM families in the scheme table are examples, not a guarantee for every model. See the compatibility table for verified devices.

### 3. Usage Notes & Troubleshooting

1. **Radio combinations**:
   * Supported band and channel combinations vary by Wi-Fi hardware, firmware and ROM. Complementary bands may be supported, but are not guaranteed on every device;
   * Treat the Wi-Fi list's "Recommended" and "Band Conflict" labels as guidance. Actual concurrency depends on the device's supported combinations.
2. **Same-SSID Multi-Band Disambiguation**:
   * For dual-band unified routers broadcasting identical SSIDs across 2.4GHz and 5GHz, MultiRoute disambiguates APs by their BSSID and frequency band, keeping primary and secondary connection states isolated.
3. **Resetting Temporary Network Penalties**:
   * The "Reset Network Temporary Restrictions" action clears user-disabled network markers, resets root request approval and removes all requests created through `cmd wifi add-request`, including those made by other shell tools. It does not guarantee removal of every BSSID or firmware penalty; use it only when this broader reset is intended.

---

## Personal Hotspot Upstream Steering

Android Tethering manages hotspot forwarding and upstream selection. MultiRoute adds a control that asks Tethering to re-evaluate the selected upstream when supported networks or preferences change.

MultiRoute hooks `UpstreamNetworkMonitor.getCurrentPreferredUpstream` in the NetworkStack / Tethering APEX runtime and requests that Android Tethering select the configured upstream for a personal hotspot (SoftAP):

```
                      ┌───────────────────────┐
                      │  Laptops / Tablets    │
                      └───────────┬───────────┘
                                  │ (Wi-Fi client)
                      ┌───────────▼───────────┐
                      │ Personal Hotspot (AP) │
                      │  (ap0 / swlan0 / ...) │
                      └───────────┬───────────┘
                                  │
                 ┌────────────────┼────────────────┐
                 │                │                │
          ┌──────▼──────┐  ┌──────▼──────┐  ┌──────▼──────┐
          │   Default   │  │   Cellular   │  │  Secondary  │
          │   Network   │  │ (Cellular)   │  │    Wi-Fi    │
          └─────────────┘  └─────────────┘  └─────────────┘
```

### 1. Key Capabilities
- **Upstream selection request**: Choose **Cellular**, **Secondary Wi-Fi**, or **System Default** and ask Android Tethering to re-evaluate its upstream.
- **Practical Use Cases**:
  - **Separate network use**: Keep the host device on an intranet Wi-Fi while requesting cellular as the hotspot upstream, subject to Android Tethering and carrier policy.
  - **Dual Wi-Fi Relay**: Where the system permits it, connect primary Wi-Fi to a local network, secondary Wi-Fi to a remote AP, and request the secondary link as the hotspot upstream.
  - **Dedicated Mobile Data Tethering**: Request cellular as the hotspot upstream while the host phone uses Wi-Fi, subject to system and carrier policy.
- **Asynchronous Re-evaluation**: Target changes request a new Tethering upstream selection; timing and client connectivity depend on Android and the device.
- **Fallback and Reselection**: The module requests reselection when target networks change. Actual fallback behavior depends on Tethering's available networks and policy.
- **System-managed Forwarding**: Android manages forwarding, NAT, IPv6 and offload. A loaded Hook does not prove hardware acceleration is active.
- **Offline Pre-configuration**: Upstream preferences can be configured before turning on the hotspot; Tethering evaluates them when the AP starts.

---

## Installation

1. Install the APK from the [releases page](https://github.com/Linoleic/MultiRoute/releases) — the asset
   is named `MultiRoute-<VersionCode>-<VersionName>.apk` — or install it straight from the LSPosed
   manager's module list, since the module is in the
   [official repository](https://modules.lsposed.org/module/io.github.linoleic.multiroute). Either way the
   manager can offer updates in place, from the manifest the module declares.
2. Open **LSPosed Manager → Modules → MultiRoute**, enable it and verify the scopes (ensure **System Framework** and the **NetworkStack Tethering** packages such as `com.android.networkstack.tethering` are selected). The APK ships with `META-INF/xposed/scope.list` and package metadata so supported managers will select these automatically.
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
2. Install the new APK, then enable **the new package** in LSPosed Manager and configure its scope.
   It appears as a new module entry; the old, now-uninstalled one can be removed.

`tools/probe` (the measurement helper app) is intentionally left as `com.multiroute.probe`.

### Uninstalling / cleaning up

1. In the app, return Hotspot Upstream to **System Default** and disconnect the managed secondary Wi-Fi
   if used. Then select **App routing → Clear all app routing rules**. This removes the kernel routing rules,
   the generated routing boot script and the rule cache.
2. If dual Wi-Fi was activated, restore the settings and overlays that were in place before using MultiRoute
   and remove its `/data/adb/service.d/01-multiroute-dualwifi.sh` script. Then disable the module in LSPosed Manager
   and reboot. Clearing app routing rules does not restore these device-specific settings.
3. If the app could not run before you removed it (for example the device was wiped), the leftovers can
   be removed manually. The commands below remove this module's routing rules; they do not restore device-specific
   dual-Wi-Fi settings. Priorities `14400` and `14500` are removed in full; check for conflicts with other routing tools before using these commands.

```bash
su -c 'rm -f /data/adb/service.d/00-multiroute-restore.sh /data/adb/service.d/01-multiroute-dualwifi.sh /data/system/multiroute_rules_cache'
su -c 'while ip rule del pref 14500 2>/dev/null; do :; done; while ip rule del pref 14400 2>/dev/null; do :; done'
su -c 'while ip -6 rule del pref 14500 2>/dev/null; do :; done; while ip -6 rule del pref 14400 2>/dev/null; do :; done'
su -c 'iptables -t nat -D OUTPUT -j MULTIROUTE_DNS 2>/dev/null; iptables -t nat -F MULTIROUTE_DNS 2>/dev/null; iptables -t nat -X MULTIROUTE_DNS 2>/dev/null'
```

---

## Usage

| Tab | What you do |
| :-- | :-- |
| **App routing** | Tap an app and choose a channel; a long press enters multi-select for batch assignment; clone apps listed independently; scenario profiles for automated switching based on network conditions. |
| **Channels** | Inspect active links (interface, IP, gateway, DNS, MTU, SSID, Wi-Fi band and channel); built-in **Dual Wi-Fi Management** and **Hotspot Upstream** cards; hotspot downlink and VPN tunnel identification; per-channel egress probes. |
| **Settings** | Module and root status, active kernel rules, cellular keepalive, DNS redirection, auto failover hold-off, config backup/restore, diagnostic log export/share/copy, and rule purge maintenance. |

---

## How it works

**Kernel layer (root).** Each network interface has its own routing table maintained by Android's
`netd`. MultiRoute installs:

- `ip rule add uidrange <uid>-<uid> lookup <iface> pref 14500` — one rule per assigned UID, for IPv4
  and IPv6;
- `ip rule add to <on-link prefix> lookup <iface> pref 14400` — LAN bypass, generated from each
  channel's own connected prefixes.

**DNS.** MultiRoute redirects assigned UIDs' **IPv4 UDP/TCP port 53** traffic to the selected channel's
resolver through a dedicated `MULTIROUTE_DNS` chain in the IPv4 `nat` table. IPv6 DNS, Private DNS
(DoT, port 853) and DoH are not rewritten. With a VPN active, DNS and application traffic behavior depends on the VPN mode
and policy; see Known limitations.

**Framework layer (LSPosed).** Hooks inside `system_server` keep the *app-visible* network state
consistent with the assignment (`ConnectivityService.getDefaultNetworkForUid`,
`getActiveNetworkForUidInternal`, `getMobileDataPreferredUids`); the Xiaomi Dedicated scheme activates hooks that attempt to keep the secondary Wi-Fi link active while the screen is off.

**Boot recovery.** A generated `service.d` script waits for interfaces for up to 36 intervals of five
seconds. When target channels are available, it applies routing rules once and exits. It restores routing
rules; it does not establish secondary Wi-Fi connections. Later network changes are handled by app and
module callbacks. On a locked device the script may run before the app, but recovery timing depends on
interface availability and ROM behavior; it is not guaranteed to complete immediately after boot.

**Rule cache.** The module maintains a UID→interface cache for framework hooks. The cache does not
establish network links or guarantee that routing rules are restored at a fixed point in boot.

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
| Not activated | Module disabled, scope not configured, or not injected |

---

## Troubleshooting

- **Module shows "not activated"** — enable it in LSPosed Manager and ensure module scope is checked,
  then reboot or restart system server.
- **Module shows "loaded older build" after an update** — expected: the framework cannot hot-reload.
  Reboot, or `su -c 'setprop ctl.restart zygote'`.
- **An assignment has no effect** — check **Settings → View diagnostic log** (supports clipboard copy, file export, or system share): section *rule effectiveness* shows every configured UID with *in effect* or *not in effect*. A channel whose interface has no default route at that moment is skipped, and the log says so.
- **Rules disappeared after a reboot** — check the boot-recovery log in the same snapshot
  (section 3, `/data/adb/multiroute/last_boot_sync.log`). Also confirm KernelSU/Magisk/APatch executes
  `service.d` scripts on this ROM.
- **Secondary Wi-Fi channel is missing** — check the reported capabilities in **Channels → Dual Wi-Fi Manager** and select an available scheme. Android 13+ is required for the AOSP Multi-Internet scheme; requirements for other schemes vary by ROM and device.
- **DNS does not behave as expected** — only IPv4 UDP/TCP port 53 is redirected. IPv6 DNS, **Private DNS (DoT)**,
  and DoH are not rewritten; VPN behavior also depends on its mode.
- **Assignment seems to do nothing while a VPN is active** — see the VPN note under *Known limitations*.
- **Wi-Fi band is not shown** — the device did not report a frequency for that link; both the platform
  and the `dumpsys` fallback returned nothing.
- **Public-IP probe times out** — switch the preset in Settings to a reachable endpoint or set a custom
  URL.

---

## Privacy

MultiRoute has no telemetry, analytics, crash-reporting or advertising backend and does not require an account. User-triggered egress probes and optional connectivity checks contact their configured endpoint; that server can observe the source IP address. Diagnostic logs and exported configuration can contain network names and test URLs, so store or share those files with care. Dependencies include AndroidX/Compose and the LibXposed API.

- **Local storage.** Configuration lives in app-private storage, with running rules in the kernel and a UID→interface cache for hooks. The Xiaomi scheme may read or store Wi-Fi credentials locally; configuration exports omit Wi-Fi passwords. Android system backup or device migration may copy app data depending on system settings.
- **Outbound requests are strictly transparent and user-controlled:**
  - **The egress probe on the Channels screen** only queries when manually started by you;
  - **The "Internet connectivity validation" under Auto-Failover** (configurable in Settings): when a channel connects or recovers, it optionally sends a single lightweight HTTP 204 request (a few hundred bytes, 0-byte body) through that channel to verify real Internet access, preventing routing stalls on captive portals, ISP dropouts, or local-only networks. It never polls continuously in the background and carries no device identifiers, app lists, or rule contents;
  - If you do not run the egress probe and keep Internet validation turned off, these optional checks do not make requests.
- **Root access supports system operations.** `su` is used for routing rules and boot scripts, dual-Wi-Fi and hotspot configuration, reading saved Wi-Fi configuration on supported schemes, releasing this module's connection requests, and collecting system diagnostics.
- **The app list is read, never reported.** `QUERY_ALL_PACKAGES` exists so the app can list installed apps
  (and their clone-space instances) for assignment.

---

## Known limitations

- **DNS and VPN behavior**: MultiRoute redirects only IPv4 UDP/TCP port 53; IPv6 DNS, DoT and DoH are not rewritten. Depending on VPN mode and UID rules, DNS or app traffic may use the selected link or remain in the tunnel. Check the VPN's own behavior before relying on a particular path.
- **The platform's per-UID network selection is below MultiRoute** (`15040`-range), so a channel
  assignment does override that.
- **Hotspot and VPN targets**: Hotspot AP interfaces (e.g. `ap0`, `swlan0`) are excluded from App Routing and Scenario target pickers. VPN virtual interfaces (e.g. `tun0`) remain selectable, with restrictions in app assignment pickers for VPN owner UIDs and recognized VPN clients. These picker checks do not guarantee loop prevention for every imported configuration or scenario; results depend on the VPN's policy.
- **Cloned-app lookup needs the root package listing.** The numeric space id is shown instead of the
  space name; only ROMs whose clone spaces are real Android users are covered.
- **Secondary Wi-Fi and screen-off behavior**: Xiaomi-specific hooks attempt to keep the secondary link active on supported Xiaomi ROMs. On other platforms the foreground service monitors the connection and attempts recovery; it cannot guarantee that hardware or firmware keeps the link up during screen-off or Doze.
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

# Unit tests (pure logic)
./gradlew testDebugUnitTest
```

- JDK 17, Android SDK Platform 36; `minSdk` 30 (Android 11).
- Signing is optional: copy `keystore.properties.example` to `keystore.properties` and fill it in.
  Without it, release builds fall back to debug signing (with a warning).
- Artifact: `app/build/outputs/apk/release/app-release.apk`.
- `tools/probe` is a separate tiny app used to measure per-UID egress on a device; it is not part of the
  module.

### Versioning and releases

`versionName` comes from the most recent reachable git tag; a dirty working tree adds `-local`. A tag is a source-control marker and does not by itself establish that a version was published. For example, the v1.5.0 changelog section is currently marked unreleased.

`versionCode` is `10000` plus the greatest usable commit count from `origin/master` and `HEAD`; if neither count is available, the count defaults to `1`. Local commits can therefore affect it, and forks are not guaranteed to match the upstream value.

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
