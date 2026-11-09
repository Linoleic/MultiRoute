# MultiRoute — Architecture & Technical Reference

[English](ARCHITECTURE.md) | [简体中文](ARCHITECTURE_CN.md) | [← Back to README](README.md)

This document describes MultiRoute's kernel routing rules, LSPosed hooks, Personal Hotspot upstream steering, Dual Wi-Fi drivers, module status states, and privacy boundaries.

---

## System Architecture

MultiRoute pairs **Android framework hooks (LSPosed)** with **Linux kernel policy routing and NAT rules (root)**:

```
        ┌──────────────────────── MultiRoute (UI) ────────────────────────┐
        │ App Routing · Channels & Live Traffic · Tools · Settings        │
        └───────────────┬─────────────────────────────┬───────────────────┘
                        │                             │
        ┌───────────────▼──────────────┐   ┌──────────▼───────────────────┐
        │  Framework layer (LSPosed)   │   │  Kernel layer (root)         │
        │  system_server & NetworkStack│   │  Policy routing & NAT chains │
        │  · ConnectivityService hook  │   │  · pref 14500 per-UID egress │
        │  · Dual Wi-Fi & keepalive    │   │  · pref 14400 LAN bypass     │
        │  · Hotspot upstream steering │   │  · IPv4 nat UDP/TCP 53 DNS   │
        │  · Scenario wake & failover  │   │  · IPv4 + IPv6 dual-stack    │
        └──────────────────────────────┘   │  · /proc/net/dev rate probe  │
                                           └──────────┬───────────────────┘
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

---

## Module Metadata & Packaging

| Field | Value |
| :--- | :--- |
| Package name | `io.github.linoleic.multiroute` |
| Module name | MultiRoute |
| Listing | [modules.lsposed.org/module/io.github.linoleic.multiroute](https://modules.lsposed.org/module/io.github.linoleic.multiroute) |
| Xposed API | `minApiVersion 101`, `targetApiVersion 102` (LibXposed) |
| Scope | `system` (System Framework / `system_server`), `com.android.networkstack.tethering`, `com.google.android.networkstack.tethering`, `com.android.networkstack`, `com.google.android.networkstack` |
| `staticScope` | `false` (scope list ships inside APK metadata) |
| `autoHotReload` | `true` in manifest, though `system_server` cannot hot-reload in practice → reboot or restart `system_server` |
| Update manifest | [`xposed_update.json`](xposed_update.json) |

The APK includes `META-INF/xposed/{module.prop, scope.list, java_init.list}` (`com.multiroute.hook.MultiRouteModule`), so LSPosed managers read the scope and entry point from the package itself.

---

## Kernel Layer (Root)

### 1. Policy Routing Rules (`ip rule`)

Each network interface has its own routing table maintained by Android's `netd`. MultiRoute installs rules in the kernel without userspace VPN tunneling:

- `ip rule add uidrange <uid>-<uid> lookup <iface> pref 14500` — installed per assigned UID for IPv4 and IPv6. Because the platform's per-UID network selection rules sit in the `15040` range, `pref 14500` takes precedence over them.
- `ip rule add to <on-link prefix> lookup <iface> pref 14400` — LAN bypass rules generated from each channel's connected prefixes so intranet devices remain reachable from apps routed elsewhere.

### 2. Per-App DNS Redirection (`iptables`)

MultiRoute installs a `MULTIROUTE_DNS` chain in the IPv4 `nat` table (linked from `OUTPUT`) to redirect assigned UIDs' **IPv4 UDP/TCP port 53** packets to the selected channel's resolver.

- IPv6 DNS, **Private DNS (DoT, port 853)**, and **DNS-over-HTTPS (DoH)** are not rewritten.
- When a VPN is active, DNS and application traffic paths depend on the VPN's mode and routing policy.

### 3. Pooled Root Execution & Interface Metrics

- **Persistent shell pooling (`RootShellExecutor`)**: Commands run in isolated subshells over pooled persistent root sessions (`su` and global mount-namespace `su -M`) rather than spawning a new `su` process per query. Timed-out or broken sessions are closed and replaced on demand.
- **Channel traffic sampling (`ChannelTrafficMonitor`)**: While viewing the Channels tab, the app periodically reads `/proc/net/dev` via the pooled root shell to compute per-interface RX/TX speeds, a 30-sample rolling sparkline, window peaks, and cumulative bytes since boot.
- **System-estimated throughput vs. Wi-Fi link rate**:
  - **System-estimated throughput**: Bandwidth estimates reported by Android's `NetworkCapabilities` (`downlinkBps` / `uplinkBps`), which reflect platform or carrier estimates rather than measured speed.
  - **Wi-Fi link rate**: Negotiated physical-layer RX/TX link speeds (`WifiInfo` / `dumpsys wifi`) in Mbps.

### 4. Boot Recovery & Rule Cache

- **Boot script (`/data/adb/service.d/00-multiroute-restore.sh`)**: Generated when rule recovery is active and root managers run `service.d`. On boot, it polls for target interfaces for up to 36 intervals of 5 seconds, applies IPv4/IPv6 routing rules once interfaces are ready, logs diagnostics to `/data/adb/multiroute/last_boot_sync.log`, and exits. It restores routing rules only and does not establish secondary Wi-Fi links.
- **Rule cache (`/data/system/multiroute_rules_cache`)**: Holds UID→interface mappings read by framework hooks.

### 5. Manual Cleanup Commands

Under normal circumstances, reset Hotspot Upstream to **System Default**, disconnect managed Secondary Wi-Fi, and run **App routing → menu → Clear all app routing rules** inside the app before uninstalling. If the app can no longer run:

> Note: These commands remove all IPv4/IPv6 rules at priorities `14400` and `14500`, delete the `MULTIROUTE_DNS` NAT chain, and remove MultiRoute's boot scripts and rule cache. Check for priority conflicts with other routing tools first, and revert any vendor-specific Dual Wi-Fi settings/overlays separately if used.

```bash
su -c 'rm -f /data/adb/service.d/00-multiroute-restore.sh /data/adb/service.d/01-multiroute-dualwifi.sh /data/system/multiroute_rules_cache'
su -c 'while ip rule del pref 14500 2>/dev/null; do :; done; while ip rule del pref 14400 2>/dev/null; do :; done'
su -c 'while ip -6 rule del pref 14500 2>/dev/null; do :; done; while ip -6 rule del pref 14400 2>/dev/null; do :; done'
su -c 'iptables -t nat -D OUTPUT -j MULTIROUTE_DNS 2>/dev/null; iptables -t nat -F MULTIROUTE_DNS 2>/dev/null; iptables -t nat -X MULTIROUTE_DNS 2>/dev/null'
```

---

## Framework Layer (LSPosed Hooks)

### 1. System Framework (`system_server`)

Kernel `ip rule` steers socket packets, while apps also query `ConnectivityManager` to inspect active networks. Hooks inside `system_server` keep app-visible network state consistent with the configured assignment:

- `ConnectivityService.getDefaultNetworkForUid`
- `ConnectivityService.getActiveNetworkForUidInternal`
- `ConnectivityService.getMobileDataPreferredUids` (used for SIM-based cellular routing on ROMs that honor it)
- Link-change wakeup triggers so Scenario Plans re-evaluate when networks change even when the UI is closed.

### 2. Personal Hotspot Upstream Steering (`NetworkStack / Tethering APEX`)

MultiRoute hooks `UpstreamNetworkMonitor.getCurrentPreferredUpstream` inside the NetworkStack / Tethering APEX runtime, observing `Settings.Global` (`multiroute_hotspot_upstream`) and broadcast triggers to ask Android Tethering to re-evaluate SoftAP upstream selection asynchronously:

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
          │   Default   │  │  Cellular   │  │  Secondary  │
          │   Network   │  │   (Mobile)  │  │    Wi-Fi    │
          └─────────────┘  └─────────────┘  └─────────────┘
```

Packet forwarding, NAT, IPv6 prefix delegation, and offload remain managed by Android Tethering.

### 3. Secondary Wi-Fi Drivers & Schemes

| Scheme | Code | Mechanism & Notes |
| :--- | :--- | :--- |
| **Auto** | `AUTO` | Selects a scheme based on manufacturer, ROM, and detected capabilities (default). |
| **Xiaomi / HyperOS Dedicated** | `XIAOMI_SLAVE` | Coordinates with Xiaomi's `SlaveWifiService` / `DualStaImpl` and uses LSPosed hooks intended to preserve the link during screen-off; effectiveness varies by device and ROM. Uses `/data/adb/service.d/01-multiroute-dualwifi.sh` when needed. |
| **AOSP Multi-Internet** | `AOSP_OVERLAY` | Requires **Android 13 (API 33)+**. Applies an overlay forcing `config_wifiMultiStaMultiInternetConcurrencyEnabled` and requests Multi-Internet mode 2. Overlay behavior varies by ROM; not broadly verified. |
| **Root Direct** | `ROOT_DIRECT` | Invokes `cmd wifi set-multi-internet-mode 2` via root. Does not add hardware/firmware concurrency support; not broadly verified. |

> **"Reset Network Temporary Restrictions"** clears user-disabled network markers, resets root request approval, and removes all requests added through `cmd wifi add-request` (including requests from other shell tools). It does not guarantee clearing firmware-level BSSID penalties.

---

## Module Status Reference

The toolbar status icon checks framework beacons together with `BuildConfig.VERSION_CODE` and `HOOK_BUILD_ID` inside `system_server`:

| Status | Meaning |
| :--- | :--- |
| **Activated (hooks ready)** | Hooks are installed and the code loaded inside `system_server` matches the installed APK. |
| **Loaded, hooks missing** | The module loaded into `system_server`, but `ConnectivityService` hooks were not installed. |
| **Loaded older build** | The APK was updated while `system_server` still runs an earlier build. Reboot or restart `zygote`. |
| **Loaded (legacy marker)** | Status marker present without structured hook detail (older build). |
| **Status record expired** | Recorded owner PID no longer matches the running `system_server` process. |
| **Not activated** | Module disabled, scope unchecked, or injection did not occur. |

---

## Technical Boundaries & Privacy

### Privacy & Network Activity

MultiRoute has no telemetry, analytics, crash-reporting, or advertising backend and does not require an account.

- **Local storage & backups**: Rules live in app-private storage, active rules in the kernel, and UID mappings in `/data/system/multiroute_rules_cache`. SIM targets use opaque device-scoped IDs rather than raw ICCIDs or phone numbers. v3 JSON backups cover app routing, scenarios, policy toggles, hotspot upstream preferences, non-secret Dual Wi-Fi settings, appearance, and probe URLs—excluding Wi-Fi passwords, UID caches, logs, and boot ADB recovery settings.
- **Outbound requests**:
  - **Public-IP egress probe** on the Channels screen executes only when triggered manually by the user. `usesCleartextTraffic` remains enabled so custom LAN endpoints such as `http://192.168.1.1/ip` work; built-in presets use HTTPS.
  - **Auto-Failover connectivity validation** (toggleable in Settings) sends a single HTTP 204 request (0-byte body, no device IDs, app lists, or rules) only when a channel connects or recovers, to help detect captive portals or offline uplinks. When disabled, no probe requests are sent.
- **Permissions**:
  - `su`: Routing rules, `iptables`, boot recovery scripts, Dual Wi-Fi / Hotspot / ADB control, `/proc/net/dev` counters, and diagnostic snapshots.
  - `QUERY_ALL_PACKAGES`: Lists installed apps (and clone users via root package queries) locally for assignment.

### Technical Limitations

- **Android 7 – 10 (`< API 30`)**: Unsupported; required framework interfaces do not exist below Android 11.
- **DNS & VPN**: Only IPv4 UDP/TCP port 53 is redirected; DoT (`853`), DoH, and IPv6 DNS are not rewritten. With a VPN active (`tun0`), DNS and app paths depend on the VPN's policy; app pickers restrict VPN owner UIDs and recognized VPN clients to reduce loops, though imported configs are still subject to VPN rules.
- **Cloned-app enumeration**: Requires root package queries to list secondary Android users, displays numeric IDs (`(999)`), and applies to ROMs implementing clones as real Android users.
- **Secondary cellular controls**: Requires two active SIMs, manages only the non-default data SIM without changing the system default SIM or auto-starting at boot, and relies on device/carrier dual-data support plus ROM handling of `mobile_data_preferred_uids`.
- **Dual Wi-Fi screen-off retention**: Outside supported Xiaomi hooks, an optional foreground service monitors link status and attempts recovery, which cannot override firmware or hardware Doze behavior.
