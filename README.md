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
|   getDefaultNetworkForUid     |       | - pref 14500 lookup <table>   |
| - Return matching NetworkAgent|       | - Bind app UIDs to interface  |
| - Hook secondary Wi-Fi        |       | - Direct kernel packet egress |
|   screen-off keepalive        |       |                               |
+-------------------------------+       +-------------------------------+
                                  |
                                  v
+--------------------------------------------------------------------+
|   Concurrent Carriers: Primary Wi-Fi | Secondary Wi-Fi | Cellular  |
+--------------------------------------------------------------------+
```

1. **Kernel Policy Routing Layer**:
   - Maintains dedicated policy routing tables for each active network interface;
   - Directs egress traffic of designated apps at the kernel level via:
     `ip rule add uidrange <uid>-<uid> lookup <table_id> pref 14500`.
2. **System Framework Layer (Modern LibXposed Hook)**:
   - Built on the modern LibXposed API v102 specification, scoped strictly to `system_server`;
   - Intercepts `ConnectivityService.getDefaultNetworkForUid(int)` across both Mainline/APEX and standard framework implementations, returning the corresponding `Network` / `NetworkAgentInfo` to match application routing assignments;
   - Supports vendor-specific hooks (such as `SlaveWifiService` on Xiaomi HyperOS / MIUI) to prevent background services from tearing down secondary Wi-Fi connections upon screen-off.

---

## Key Features

- **Dynamic Interface Discovery**: Automatically discovers active network interfaces, displaying interface identifiers, local IP addresses, gateways, MAC addresses, and Wi-Fi SSIDs without hardcoded device assumptions.
- **Per-App Policy Routing**: Assigns applications to dedicated network channels (e.g. download tools via Secondary Wi-Fi, chat apps via Primary Wi-Fi, latency-sensitive services via Mobile Cellular).
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
  - *Note: A soft reboot (restart system_server or device) is recommended upon initial activation.*

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
   - Restart `system_server` or reboot the device.
2. **Secondary Wi-Fi interface not displayed?**
   - Verify that dual Wi-Fi acceleration is supported by the device hardware, enabled in system settings, and successfully connected to a secondary access point.
   - MultiRoute will detect and populate the secondary interface once online.
3. **Public IP probe times out?**
   - In Settings, switch the diagnostic server preset to a reachable endpoint, or configure a custom query URL.

---

## License

MultiRoute is licensed under the [GNU General Public License v3.0 (GPL-3.0)](LICENSE).
