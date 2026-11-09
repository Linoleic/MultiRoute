# MultiRoute

[English](README.md) | [简体中文](README_CN.md)<br>
[Architecture](ARCHITECTURE.md) · [Building](BUILDING.md) · [Changelog](CHANGELOG.md)

> Download installation packages from [GitHub Releases](https://github.com/Linoleic/MultiRoute/releases) or install via the [LSPosed Module Repository](https://modules.lsposed.org/module/io.github.linoleic.multiroute).

[![build](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml/badge.svg)](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Linoleic/MultiRoute?label=release)](https://github.com/Linoleic/MultiRoute/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20%28verified%20on%2016%20%2F%2017%29-green.svg)](https://developer.android.com)
[![LSPosed](https://img.shields.io/badge/LSPosed-module%20page-orange.svg)](https://modules.lsposed.org/module/io.github.linoleic.multiroute)
[![LibXposed API](https://img.shields.io/badge/LibXposed-min%20101%20%C2%B7%20target%20102-orange.svg)](https://github.com/libxposed)

MultiRoute is an Android LSPosed module for per-app network routing (including secondary SIM channel controls and SIM-based assignments), secondary Wi-Fi management, personal hotspot upstream selection, per-channel traffic monitoring, and wireless debugging controls. Device and ROM behavior varies by function.

> **Prerequisites**: Requires **Root** (`KernelSU` / `Magisk` / `APatch`) and **LSPosed** (LibXposed API 101+), scoped to **System Framework** (`system`) and the **NetworkStack Tethering** packages.

---

## Features

- **Per-app policy routing**: Assign individual apps to **Primary Wi-Fi**, **Secondary Wi-Fi**, **Cellular** (including secondary SIM selection on dual-SIM devices), **Ethernet**, or **System Default** via Linux kernel policy routing without a userspace VPN. Connected LAN subnets stay routed on their own interfaces so local devices (NAS, printers, casting) remain reachable.
- **Cloned apps & scenario plans**: Cloned spaces (e.g., Xiaomi XSpace `(999)`) and work profiles are listed as separate targets. Save named **Scenario Plans** with conditions (*Wi-Fi SSID*, *Dual Wi-Fi active*, *Cellular only*, *Always*, or *Manual*). Matching plans stack in priority order and re-evaluate on network changes even when the app UI is closed.
- **Dual Wi-Fi management (`STA + STA`)**: Connect and switch a secondary Wi-Fi network alongside primary Wi-Fi, distinguish access points sharing an SSID by BSSID and band, and show band compatibility indicators.
- **Personal hotspot upstream selection**: Request Android Tethering to select **Cellular**, **Secondary Wi-Fi**, or **System Default** as the hotspot uplink (for example, keeping the device on an intranet Wi-Fi while sharing cellular data over hotspot).
- **Channel monitoring & egress probes**: Inspect per-interface download/upload rates, 30-second traffic history graphs, cumulative usage since boot, Wi-Fi link rates, and per-channel public-IP probes.
- **Wireless debugging helper**: Manage Android 11+ Native TLS wireless debugging and Legacy TCP mode (configurable port `1024..65535`), detect connected workstation IPs, copy `adb connect` commands, and optionally attempt post-boot recovery within ten minutes after first unlock.

---

## Requirements & Compatibility

| Requirement | Details |
| :--- | :--- |
| **Root** | KernelSU / Magisk / APatch (policy routing and root shell queries require root) |
| **Xposed Framework** | LSPosed or another framework implementing LibXposed API 101+ |
| **Module Scope** | **System Framework** (`system` / `system_server`) and **NetworkStack** (`com.android.networkstack.tethering`, etc.) |
| **Android Version** | Android **11** (`minSdk 30`) or later |

| Verified Environments | Status |
| :--- | :--- |
| **Android 17 / HyperOS (Phone)** | Verified Dual Wi-Fi, secondary SIM controls and SIM-based routing, app and clone routing, IPv4/IPv6 routing, and rule recovery |
| **Android 16 / HyperOS (Tablet)** | Verified Dual Wi-Fi, app and clone routing, IPv4/IPv6 routing, DNS redirection, and rule recovery (no cellular hardware) |
| **Android 16 / AOSP** | Verified connecting, disconnecting, and IPv4 Internet access via saved secondary Wi-Fi networks; other features not broadly verified |
| **Android 11 – 15** | ⚠️ Not verified |
| **Android 7 – 10** | ❌ Not supported (required framework interfaces were added in Android 11) |

> Hardware, firmware, and ROM behavior vary. Dual Wi-Fi radio combinations, concurrent dual-SIM data, and hotspot upstream policies cannot be determined by Android version alone.

---

## Installation & Getting Started

1. **Install the APK**: Download `MultiRoute-<VersionCode>-<VersionName>.apk` from [Releases](https://github.com/Linoleic/MultiRoute/releases), or install from LSPosed Manager's module repository (`io.github.linoleic.multiroute`). Both methods support update checks via [`xposed_update.json`](xposed_update.json).
2. **Enable in LSPosed**: Open **LSPosed Manager → Modules → MultiRoute**, enable the module, and check its scope (`system` and NetworkStack Tethering packages). Supported managers pre-select this list from the embedded `scope.list` metadata.
3. **Reboot**: Reboot the device, or restart the system server (`su -c 'setprop ctl.restart zygote'`).
4. **Verify status**: Open MultiRoute, grant Root permission, and tap the **module status icon** on the toolbar. It should report **Activated (hooks ready)**.
   - To route apps over Cellular while Wi-Fi is connected, enable **Mobile data always on** in Developer Options (or your ROM's equivalent setting).

<details>
<summary>Upgrading from 1.1.x (Package ID change)</summary>

From v1.2 onward, the package ID changed from `com.multiroute` to `io.github.linoleic.multiroute` to match the Xposed Module Repository ownership namespace:
1. Uninstall `1.1.x` first (assignments do not migrate automatically).
2. Install the current APK, enable the new entry in LSPosed Manager, configure its scope, and remove the old uninstalled entry.

</details>

<details>
<summary>Uninstalling / Cleaning Up</summary>

1. Inside the app: set Hotspot Upstream back to **System Default**, disconnect any managed Secondary Wi-Fi, and run **App routing → menu → Clear all app routing rules**.
2. If Dual Wi-Fi was used, revert any device/overlay changes that were applied before using MultiRoute and remove `/data/adb/service.d/01-multiroute-dualwifi.sh` if present.
3. Disable MultiRoute in LSPosed Manager, uninstall the APK, and reboot.
- If the app cannot be opened before removal, see [manual cleanup commands](ARCHITECTURE.md#5-manual-cleanup-commands).

</details>

---

## Usage

MultiRoute provides four main destinations (**App routing**, **Channels**, **Tools**, and **Settings**), using bottom navigation on narrow screens and a navigation rail with list-detail panes on wide screens:

### 1. App Routing
- **Single-app assignment**: Tap an app (or its `(999)` clone / work profile entry) and select a target channel.
- **Batch assignment**: Long-press an item to enter multi-select mode and assign or reset multiple apps together.
- **Scenario plans**: Save assignments as named plans with trigger conditions (*Wi-Fi network*, *Dual Wi-Fi online*, *Cellular only*, *Always*, or *Manual*). Matching plans override mentioned apps in priority order and re-evaluate when links change, even while MultiRoute is not in the foreground.
- **Rule actions**: Use the toolbar menu to reapply rules or clear all app routing rules.

### 2. Channels
- **Channel list & traffic meter**: View active links (`Primary Wi-Fi`, `Secondary Wi-Fi`, `Cellular`, `VPN`, `Hotspot` downlinks, `Ethernet`) along with current RX/TX rates, a 30-second rate history graph, window peak speeds, and cumulative traffic since boot.
- **Channel details**: Tap a channel card to inspect interface name, IP addresses, gateway, DNS servers, MTU, metered status, **System-estimated throughput**, and (for WLAN links) **Wi-Fi link rate** (negotiated RX/TX Mbps) plus band and channel.
- **Public-IP probe**: Run an on-demand test through a specific channel using HTTPS presets or a custom endpoint.
- **Secondary SIM & inactive links**: Inactive or establishing links (such as the non-default data SIM on supported dual-SIM devices) expose management actions directly in the Channels tab or Tools.

### 3. Tools
- **Dual Wi-Fi Manager**:
  - Choose a connection scheme (**Auto** is the default; Xiaomi/HyperOS devices use `SlaveWifiService` coordination and keepalive hooks, while Android 13+ AOSP/other ROMs provide Overlay and Root options).
  - System Multi-Internet schemes only connect to networks already saved in Android Wi-Fi settings; MultiRoute does not store or send Wi-Fi passwords in those requests. Access points with the same SSID are listed separately by BSSID and band.
- **Personal Hotspot Upstream**: Select **Cellular**, **Secondary Wi-Fi**, or **System Default** (including before turning on Hotspot) and view the reported active upstream on the status card. Forwarding, NAT, IPv6, and offload remain managed by Android Tethering.
- **Wireless Debugging Helper**: Switch between **Native TLS** (Android 11+ dynamic port and pairing) and **Legacy TCP** (`1024..65535`, default `5555`), check connected workstation IPs, copy the `adb connect` command, and optionally enable post-unlock boot recovery on a chosen primary Wi-Fi.
- **Runtime logs & module status**: Open the toolbar status icon to verify hook readiness and build parity, or open **Tools → Runtime logs** to check per-UID rule status, boot logs, and exportable diagnostic snapshots.

### 4. Settings & Backup
- Configure **Auto-Failover** (fall back to the default network when an assigned link drops, with optional HTTP 204 connectivity checks), **Per-App DNS Redirection**, failover hold-off timers, **Theme / Material You**, and **App Language** (`Follow system` / `简体中文` / `English`).
- **Backup & Restore (v3 JSON)**: Preview and restore selected categories (app routing, SIM targets, scenario plans, network policies, hotspot preference, non-secret Dual Wi-Fi settings, appearance, and probe servers). Compatible with v1/v2 backups; Wi-Fi passwords, UID caches, logs, and boot ADB recovery settings are excluded.

---

## FAQ & Troubleshooting

<details>
<summary><strong>Module shows "Not activated" or "Loaded older build"</strong></summary>

- **Not activated**: Confirm MultiRoute is enabled in LSPosed Manager with **System Framework (`system`)** and **NetworkStack Tethering** scopes selected, then reboot.
- **Loaded older build**: Android's `system_server` cannot hot-reload framework code after an app update. Reboot, or run `su -c 'setprop ctl.restart zygote'`, then check the status icon again.

</details>

<details>
<summary><strong>An assignment has no effect</strong></summary>

1. Open **Tools → Runtime logs** and check the **Rule effectiveness** section to see whether the UID shows **in effect** or was skipped (for example, if the target interface currently lacks a default route).
2. When routing apps over Cellular while Wi-Fi is connected, enable **Mobile data always on** in Developer Options.
3. If a **VPN** is active, check the DNS & VPN notes below; VPN routing policy can override or capture traffic depending on its configuration.

</details>

<details>
<summary><strong>Rules disappear after reboot</strong></summary>

Check section 3 (`/data/adb/multiroute/last_boot_sync.log`) in **Tools → Runtime logs**, and confirm your root manager executes `/data/adb/service.d/` scripts on this ROM. Boot recovery restores routing rules when interfaces become available; it does not connect Secondary Wi-Fi.

</details>

<details>
<summary><strong>Secondary Wi-Fi is missing or fails to connect</strong></summary>

1. Check detected hardware capability in **Tools → Dual Wi-Fi Manager** and keep Connection Scheme set to **Auto** unless testing a specific method (the AOSP overlay option requires Android 13+). Root access cannot add dual-STA concurrency if hardware or firmware lacks support.
2. Save the target Wi-Fi network in system Wi-Fi settings before selecting it as Secondary Wi-Fi.
3. If two access points share the same band on a hardware configuration that rejects same-band pairing, try an access point on a complementary band (e.g., 2.4 GHz + 5 GHz).

</details>

<details>
<summary><strong>DNS behavior or VPN interaction differs from expectations</strong></summary>

MultiRoute redirects **IPv4 UDP/TCP port 53** to the selected channel's DNS server. **IPv6 DNS**, **Private DNS (DoT, port 853)**, and **DoH** are not rewritten. With an active VPN (`tun0`), DNS and traffic handling depend on the VPN's own mode and policy. App assignment pickers restrict VPN owner UIDs and recognized VPN clients from selecting VPN channels to reduce routing loops.

</details>

<details>
<summary><strong>Wi-Fi band is not shown, or Public-IP probe times out</strong></summary>

- **Band not shown**: Neither Android's platform API nor the `dumpsys` fallback reported frequency data for that interface.
- **Probe timeout**: The channel may lack Internet reachability or access to the selected endpoint; switch presets or configure another reachable URL in **Settings**.

</details>

<details>
<summary><strong>Known limitations</strong></summary>

- Routing rules install both IPv4 and IPv6 kernel tables without a userspace VPN, while DNS redirection applies to IPv4 port 53 only.
- Cloned-app lookup uses root package listing, displays numeric space IDs (e.g., `(999)`), and covers ROMs where clones run as real Android users.
- Secondary cellular controls require two active SIMs, manage only the non-default data SIM without changing the default data SIM or auto-starting at boot, and depend on device/carrier dual-data support plus ROM handling of `mobile_data_preferred_uids`.
- Outside supported Xiaomi hooks, secondary Wi-Fi screen-off recovery relies on a foreground service monitor and cannot override hardware or firmware Doze behavior.

</details>

---

## Related Documentation

- [Architecture & Technical Reference](ARCHITECTURE.md) ([简体中文](ARCHITECTURE_CN.md))
- [Building & Releasing](BUILDING.md) ([简体中文](BUILDING_CN.md))
- [Changelog](CHANGELOG.md)

## Disclaimer

This is a **root** networking module that modifies kernel routing rules and injects into the system framework. Use it at your own risk. Sending traffic over cellular or sharing mobile data over hotspot may incur carrier charges and may conflict with carrier terms. Keep a way to recover (recovery boot or disabling the module) before experimenting.

## Privacy

- **No telemetry**: MultiRoute has no analytics, crash-reporting, telemetry, or advertising backend and does not require an account.
- **Local storage & app list**: Configuration is stored in app-private storage (backups exclude Wi-Fi passwords, logs, and boot ADB recovery settings); `QUERY_ALL_PACKAGES` is used only to list installed apps and clone spaces locally.
- **User-controlled outbound requests**: Channels public-IP probes run only when initiated manually. Optional connectivity validation under Auto-Failover sends a single HTTP 204 request only when a channel connects or recovers (with no continuous background polling and no device IDs, app lists, or rules) and makes no requests when turned off.
- **Root usage**: `su` is used for routing/DNS rules, boot scripts, Dual Wi-Fi / Hotspot / ADB operations, interface traffic counters, and diagnostic snapshots (see [Architecture](ARCHITECTURE.md#privacy--network-activity) for details).

## License & Acknowledgements

Licensed under [GNU General Public License v3.0](LICENSE). Built with [LibXposed API](https://github.com/libxposed), [LSPosed](https://github.com/LSPosed/LSPosed), Jetpack Compose, and Material 3.
