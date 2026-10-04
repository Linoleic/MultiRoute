# MultiRoute

[English](README.md) | [简体中文](README_CN.md)

[![build](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml/badge.svg)](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B%20(verified%20on%2017)-green.svg)](https://developer.android.com)
[![LibXposed](https://img.shields.io/badge/LibXposed-API%20102-orange.svg)](https://github.com/libxposed)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-purple.svg)](https://kotlinlang.org)

**Per-app network channels for Android.** Assign each app to the link it should use — primary Wi-Fi,
secondary Wi-Fi (dual Wi-Fi), cellular or Ethernet — and let them communicate **at the same time**.
Egress is enforced with kernel policy routing (`ip rule`), not through a userspace VPN.

> Requires **root** (KernelSU / Magisk / APatch) and **LSPosed**. The module is scoped to the system
> framework (`system_server`). This project is not affiliated with LSPosed.

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
        │  · Xiaomi dual Wi-Fi keep-   │   │  · pref 14400 LAN bypass     │
        │    alive (screen off)        │   │  · IPv4 + IPv6               │
        └──────────────────────────────┘   └──────────┬───────────────────┘
                                                      │
        ┌─────────────────────────────────────────────▼───────────────────┐
        │  Primary Wi-Fi  │  Secondary Wi-Fi  │  Cellular  │  Ethernet     │
        └─────────────────────────────────────────────────────────────────┘
```

---

## Features

- **Per-app channel assignment** — pick a channel for any app; its traffic egresses through that link
  while other apps keep using theirs.
- **Independent configuration for cloned apps** — app-clone spaces (e.g. Xiaomi XSpace, user 999) and
  work profiles are listed as separate entries (`WeChat (999)`), so a clone and its primary install can
  use *different* channels.
- **Dual-stack** — IPv4 and IPv6 rules are installed together.
- **LAN bypass** — on-link subnets are pinned to the interface that owns them, so intranet devices
  (NAS, printers, casting) stay reachable from apps assigned elsewhere.
- **Automatic boot recovery** — rules survive reboots: a `service.d` script, a wake-up broadcast from
  the module, and the app's own network callback cover each other.
- **Screen-off secondary Wi-Fi keep-alive** — optional; prevents the OEM power policy from tearing the
  secondary Wi-Fi link down when the screen turns off (Xiaomi dual Wi-Fi).
- **Egress diagnostics** — per-channel public-IP probe with presets or a custom endpoint.
- **Module status** — reports precisely whether the hooks are installed, whether `system_server` is
  still running an older build, or whether the status record is stale.
- **No VPN** — traffic is routed in the kernel; no userspace TCP/IP stack, so throughput and latency
  stay close to native.

---

## Requirements & Compatibility

| Requirement | Details |
| :-- | :-- |
| Root | KernelSU, Magisk or APatch (policy routing tables need root) |
| Xposed | LSPosed (or another framework implementing LibXposed API 102) |
| Module scope | **System framework only** (`system` / `system_server`) |
| Android | `minSdk` 24; the hook targets cover both APEX and legacy ConnectivityService layouts |

| Android version | Status |
| :-- | :-- |
| **Android 17 / HyperOS** | ✅ Verified on a Xiaomi HyperOS phone with KernelSU and LSPosed v2.2.0: dual Wi-Fi + cellular, IPv4/IPv6 rules, LAN bypass, clone routing, boot recovery, module status, screen-off keep-alive |
| **Android 16 / HyperOS** | ✅ Verified on a Xiaomi HyperOS tablet with KernelSU and LSPosed v2.2.0: dual Wi-Fi per-app routing (IPv4 + IPv6), clone-space separation, real-traffic egress, recovery after a soft reboot, module status. The tablet has no cellular interface, so cellular routing was not exercised there |
| Android 11 – 15 | ⚠️ Expected to work (same hook targets and rule layout), not yet verified |
| Android 7 – 10 | ⚠️ Builds (`minSdk` 24) but is untested; policy-routing behaviour differs |

> [!IMPORTANT]
> **Updating the module requires a reboot.** Because the module injects into the system framework,
> LSPosed cannot hot-reload it: after installing or updating the APK, reboot the device or restart
> `system_server` (`su -c 'setprop ctl.restart zygote'`). The UI reports this as
> *loaded older build — soft reboot required* instead of pretending everything is fine.

> [!NOTE]
> **Rule changes never need a reboot.** Adding, changing or removing app assignments applies
> immediately.

The on-device evidence behind the table above — the commands used and what they returned — is collected
in [docs/VERIFICATION.md](docs/VERIFICATION.md), including what has *not* been verified yet.

---

## Installation

1. Install `app-release.apk` (root and LSPosed required).
2. Open **LSPosed Manager → Modules → MultiRoute**, enable it and set the scope to **System Framework**
   (`system`). Embedded `META-INF/xposed/scope.list` metadata already declares this.
3. Reboot, or restart the system server: `su -c 'setprop ctl.restart zygote'`.
4. Open MultiRoute and grant root. The **Settings** tab should report *activated (hooks ready)*.
   If it reports *loaded older build*, restart the system server once more.
5. Enable **Mobile data always on** (developer options) or the device's own "keep cellular active"
   setting if you want to route apps over cellular while Wi-Fi is connected.

---

## Usage

| Tab | What you do |
| :-- | :-- |
| **App routing** | Tap an app and choose a channel; a long press enters multi-select for batch assignment. Cloned apps appear as separate entries with their numeric space id. |
| **Channels** | Inspect every active link (interface, IP, gateway, DNS, SSID) and run a per-channel public-IP probe. |
| **Settings** | Module status, root status, current kernel rules, and the keep-alive switches. |

---

## How it works

**Kernel layer (root).** Each network interface has its own routing table maintained by Android's
`netd`. MultiRoute installs:

- `ip rule add uidrange <uid>-<uid> lookup <iface> pref 14500` — one rule per assigned UID, for IPv4
  and IPv6;
- `ip rule add to <on-link prefix> lookup <iface> pref 14400` — LAN bypass, generated from each
  channel's own connected prefixes.

**Framework layer (LSPosed).** Hooks inside `system_server` keep the *app-visible* network state
consistent with the assignment (`ConnectivityService.getDefaultNetworkForUid`,
`getActiveNetworkForUidInternal`, `getMobileDataPreferredUids`), and on Xiaomi ROMs also keep the
secondary Wi-Fi link alive through screen-off.

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

- **Module shows "not activated"** — enable it in LSPosed Manager, scope it to the system framework,
  then reboot or restart `system_server`.
- **Secondary Wi-Fi channel is missing** — the hardware must support dual Wi-Fi and the ROM must have
  it enabled and connected to a second access point.
- **Rules disappeared after a reboot** — check `/data/adb/multiroute/last_boot_sync.log`. Also confirm
  KernelSU/Magisk/APatch executes `service.d` scripts on this ROM.
- **Public-IP probe times out** — switch the preset in Settings to a reachable endpoint or set a
  custom URL.
- **An assigned app lost connectivity** — the channel's interface may be down; assign it to another
  channel or set it back to *System default*.

---

## Privacy

MultiRoute collects nothing and sends nothing. There is no telemetry, no analytics, no crash reporting,
no advertising and no account of any kind — the dependency list is AndroidX/Compose plus the LibXposed
API, nothing else.

- **Everything stays on the device.** Assignments live in the app's private preferences and in the kernel
  rules, with a small UID→interface cache for the hooks. None of it is uploaded anywhere.
- **The only outbound request is one you start yourself.** The egress probe on the Channels screen queries
  a public-IP endpoint that you choose — a built-in preset (all HTTPS) or your own URL. As with visiting
  that endpoint in a browser, it necessarily sees the public IP you are probing *from*; it is not sent any
  device identifier, app list or rule contents. **If you never run the probe, the app makes no network
  requests at all.**
- **Root access is scoped to routing.** `su` is used to add and remove policy-routing rules and to write
  the boot-recovery script. Nothing is executed on behalf of anyone else and nothing is reported back.
- **The app list is read, never reported.** `QUERY_ALL_PACKAGES` exists so the app can list installed apps
  (and their clone-space instances) for assignment. That list never leaves the device.

---

## Known limitations

- **DNS is not managed.** Resolution still follows the platform resolver; only routing is redirected.
  With different DNS servers per link (or Private DNS/DoT), name resolution may not follow the
  assigned channel.
- **Rules outrank the platform's per-UID bindings** (`pref 14400/14500` vs the platform's `15040+`), so
  an assigned app can be pulled **outside an always-on VPN**. Do not assign apps that must stay inside
  a VPN tunnel.
- **Cloned-app lookup needs the root package listing.** The numeric space id is shown instead of the
  space name; only ROMs whose clone spaces are real Android users are covered.
- **Screen-off keep-alive targets Xiaomi's dual Wi-Fi classes** (`SlaveWifiService`, `DualStaImpl`). On
  other ROMs the switch is inert.
- **Cellular assignment** relies on the ROM honouring `mobile_data_preferred_uids`.
- **The UI is Chinese-only for now**; English resources are incomplete.
- **Cleartext HTTP is permitted for the egress probe.** Built-in presets use HTTPS, but a custom endpoint
  may be plain HTTP (for example a router page such as `http://192.168.1.1/ip`), which is why
  `usesCleartextTraffic` remains enabled.
- Verification so far covers a limited set of devices and ROM versions (see the compatibility
  table); other ROMs may differ.

---

## Building

```bash
# Debug / release APK
./gradlew assembleDebug
./gradlew assembleRelease

# Unit tests (36 tests)
./gradlew testDebugUnitTest
```

- JDK 17, Android SDK Platform 36.
- Signing is optional: copy `keystore.properties.example` to `keystore.properties` and fill it in.
  Without it, release builds fall back to debug signing (with a warning).
- Artifact: `app/build/outputs/apk/release/app-release.apk`.

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
