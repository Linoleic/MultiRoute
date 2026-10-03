# MultiRoute

[English](README.md) | [简体中文](README_CN.md)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-10%20--%2017-green.svg)](https://developer.android.com)
[![LibXposed](https://img.shields.io/badge/LibXposed-API%20v102-orange.svg)](https://github.com/libxposed)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org)

MultiRoute is a multi-network concurrency and per-app policy routing management framework for Android. Combining Linux kernel policy routing (`ip rule` / `ip route`) with modern LibXposed system service interception (`ConnectivityService`), MultiRoute bypasses Android's default single-network restriction, enabling concurrent data transmission and per-app routing across Primary Wi-Fi, Secondary Wi-Fi (Dual Wi-Fi), Mobile Cellular, and Ethernet.

---

## Architecture and Principles

Android's default network stack operates on an exclusive "Default Network" model: even when multiple physical network links (such as dual Wi-Fi connections and mobile data) are simultaneously active, applications that do not explicitly invoke low-level network binding APIs are restricted to routing traffic through a single system-wide default network.

MultiRoute resolves this limitation using a two-layer collaborative architecture:

```
+--------------------------------------------------------------------+
|                         MultiRoute UI                              |
|   (Material 3 / Dynamic Channels / Batch Assignment / IP Tester)   |
+--------------------------------------------------------------------+
                                  |
            +---------------------+---------------------+
            v                                           v
+-------------------------------+       +-------------------------------+
|     System Layer (LSPosed)    |       |     Kernel Layer (Linux SU)   |
|     Scope: system_server      |       |       ip rule / ip route      |
+-------------------------------+       +-------------------------------+
| Hook ConnectivityService:     |       | Maintain isolated route       |
| - Intercept                   |       | tables for each interface:    |
|   getDefaultNetworkForUid     |       | - pref 14400 LAN direct bypass|
| - Return matching NetworkAgent|       | - pref 14500 lookup <table>   |
| - Hook secondary Wi-Fi        |       | - Bind app & clone (999) UIDs |
|   screen-off keepalive        |       | - Auto boot recovery via      |
| - System broadcast watchdog   |       |   service.d & boot receivers  |
+-------------------------------+       +-------------------------------+
                                  |
                                  v
+--------------------------------------------------------------------+
|   Concurrent Carriers: Primary Wi-Fi | Secondary Wi-Fi | Cellular  |
+--------------------------------------------------------------------+
```

1. **Kernel Policy Routing Layer**:
   - Maintains dedicated policy routing tables for each active network interface;
   - Dynamically bypasses direct on-link LAN subnets (both IPv4 and IPv6) at `pref 14400` directly via their respective interface tables;
   - Directs egress traffic of designated apps (including primary and dual/clone apps under User 999) at the kernel level via:
     `ip rule add uidrange <uid>-<uid> lookup <table_id> pref 14500` (dual-stack IPv4 & IPv6);
   - Deploys an automated boot recovery script to `/data/adb/service.d/00-multiroute-restore.sh` ensuring rules restore seamlessly across system reboots under KernelSU/Magisk/APatch.
2. **System Framework Layer (Modern LibXposed Hook)**:
   - Built on the modern LibXposed API v102 specification, scoped strictly to `system_server`;
   - Intercepts `ConnectivityService.getDefaultNetworkForUid(int)` across both Mainline/APEX and standard framework implementations, returning the corresponding `Network` / `NetworkAgentInfo` to match application routing assignments;
   - Hook vendor dual Wi-Fi management (`SlaveWifiService` on Xiaomi HyperOS / MIUI) to prevent background services from tearing down secondary Wi-Fi connections upon screen-off;
   - Dispatches system-level boot recovery intents with background privileges to bypass OEM battery saver broadcast blocks.

---

## Key Features

- **Dynamic Interface Discovery**: Automatically discovers active network interfaces, displaying interface identifiers, local IP addresses, gateways, MAC addresses, and Wi-Fi SSIDs without hardcoded device assumptions.
- **Per-App Policy Routing**: Assigns applications to dedicated network channels (e.g. download tools via Secondary Wi-Fi, chat apps via Primary Wi-Fi, latency-sensitive services via Mobile Cellular).
- **Dual App & Work Profile Support**: Automatically identifies clone apps (User 999) and synchronizes routing rules for both main and cloned app instances.
- **Dynamic LAN Subnet Bypass**: Detects connected on-link IPv4 and IPv6 subnets, routing LAN traffic directly to the corresponding adapter to prevent intranet disconnection.
- **Batch Multi-Select Mode**: Long-press any application to enter multi-selection mode and migrate routing channels in bulk.
- **Egress IP and Routing Diagnostics**: Built-in public IP query and connectivity tester supporting multiple presets and custom probe endpoints to verify egress paths.
- **Secondary Wi-Fi Keep-Alive**: Prevents system power management from disconnecting secondary Wi-Fi links when the screen is turned off.
- **Clean Architecture**: Injects only into `system_server` without modifying target application processes; configuration is distributed locally via a read-only ContentProvider.

---

## Requirements

- **Operating System**: Android 10 - 17+ (Tested and verified on Android 17 / HyperOS 2, compatible with both APEX-based and legacy ConnectivityService architectures)
- **Root Access**: KernelSU, APatch, or Magisk (Root privileges required to manage policy routing tables)
- **Xposed Framework**: LSPosed (v1.9.3+ or any framework supporting the modern LibXposed API)
  - **Module Scope**: Only select the System Framework (`system` / `system_server`).
  - Includes embedded static scope metadata (`META-INF/xposed/scope.list`) automatically detected by modern LSPosed.

> [!IMPORTANT]
> **LSPosed Module Updates & Reboot Requirement**:
> Because MultiRoute injects into the System Framework (`system_server`), **whenever the MultiRoute APK is installed, updated, or reloaded in LSPosed, a device reboot or soft reboot of `system_server` (`su -c 'setprop ctl.restart zygote'`) is required** for framework hook changes to take effect. System framework modules cannot be hot-reloaded due to LSPosed architecture.
> **Daily Rule Changes Do NOT Require Reboot**: Adding, modifying, or removing routing rules in the MultiRoute UI takes effect immediately in real time without any reboot.

> [!NOTE]
> **Routing Priority Note**:
> MultiRoute rules operate at kernel priority `pref 14500` (and `pref 14400` for LAN bypass), which takes precedence over standard Android per-UID rules (`pref 15040`). If an app is assigned to a specific channel in MultiRoute, its egress traffic will follow MultiRoute policy over system-level default bindings or always-on VPN routing.

---

## Build Instructions

MultiRoute is built with Gradle Kotlin DSL and supports keyless builds out of the box:

### 1. Prerequisites
- JDK 17
- Android SDK Platform 36
- Android Build Tools 34.0.0+

### 2. Signing Configuration (Optional)
MultiRoute features automatic keystore detection with graceful fallback:
- To sign release builds with your own keystore:
  ```bash
  cp keystore.properties.example keystore.properties
  ```
  Configure your keystore path, alias, and credentials in `keystore.properties`.
- **Keyless Fallback**: If `keystore.properties` is absent, Gradle logs a warning and automatically falls back to `debug` signing, ensuring seamless local, CI, and fork builds.

### 3. Build Command
```bash
# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```
Output artifact: `app/build/outputs/apk/release/app-release.apk`.

---

## Troubleshooting

1. **LSPosed indicates "Module Not Activated"?**
   - Ensure MultiRoute is enabled in LSPosed Manager and scoped to `system`.
   - Perform a soft reboot (`su -c 'setprop ctl.restart zygote'`) or reboot the device.
2. **Secondary Wi-Fi interface not displayed?**
   - Verify that dual Wi-Fi acceleration is supported by the device hardware, enabled in system settings, and successfully connected to a secondary access point.
   - MultiRoute will detect and populate the secondary interface once online.
3. **Public IP probe times out?**
   - In Settings, switch the diagnostic server preset to a reachable endpoint, or configure a custom query URL.

---

## License

MultiRoute is licensed under the [GNU General Public License v3.0 (GPL-3.0)](LICENSE).
