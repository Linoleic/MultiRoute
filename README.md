# MultiRoute

[English](README.md) | [简体中文](README_CN.md)

[![build](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml/badge.svg)](https://github.com/Linoleic/MultiRoute/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Linoleic/MultiRoute?label=release)](https://github.com/Linoleic/MultiRoute/releases)
[![Downloads](https://img.shields.io/github/downloads/Linoleic/MultiRoute/total)](https://github.com/Linoleic/MultiRoute/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7%2B%20(minSdk%2024)-green.svg)](https://developer.android.com)
[![LibXposed API](https://img.shields.io/badge/LibXposed-min%20101%20%C2%B7%20target%20102-orange.svg)](https://github.com/libxposed)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-purple.svg)](https://kotlinlang.org)

**Per-app network channels for Android.** Assign each app to the link it should use — primary Wi-Fi,
secondary Wi-Fi (dual Wi-Fi), cellular or Ethernet — and let them communicate **at the same time**.
Egress is enforced with kernel policy routing (`ip rule`), not through a userspace VPN.

> **Requires root** (KernelSU / Magisk / APatch) and **LSPosed**. The module is scoped to the **system
> framework** (`system_server`) only. Not affiliated with LSPosed.

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
        │    alive (screen off)        │   │  · nat DNS redirect:53      │
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
| Package name | `com.multiroute` |
| Module name | MultiRoute |
| Xposed API | `minApiVersion 101`, `targetApiVersion 102` (LibXposed) |
| Scope | `system` — the system framework (`system_server`) **only** |
| `staticScope` | `false` (the scope list ships in the APK metadata) |
| `autoHotReload` | `true`, but the system framework cannot actually hot-reload → reboot, or restart `system_server` |
| Update manifest | [`xposed_update.json`](xposed_update.json) — the manager can offer in-place updates |
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
| Android | `minSdk` 24; hook targets cover both APEX and legacy `ConnectivityService` layouts |

| Android version | Status |
| :-- | :-- |
| **Android 17 / HyperOS** | ✅ Verified on a Xiaomi HyperOS phone with KernelSU and LSPosed v2.2.0: dual Wi-Fi + cellular, IPv4/IPv6 rules, LAN bypass, clone routing, boot recovery, module status, screen-off keep-alive |
| **Android 16 / HyperOS** | ✅ Verified on a Xiaomi HyperOS tablet with KernelSU and LSPosed v2.2.0: dual Wi-Fi per-app routing (IPv4 + IPv6), clone-space separation, real-traffic egress, recovery after a soft reboot, DNS redirect, module status. The tablet has no cellular interface, so cellular routing was not exercised there |
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

The on-device evidence behind this table — the commands used and what they returned — is collected in
[docs/VERIFICATION.md](docs/VERIFICATION.md), including what has *not* been verified yet.

---

## Features

- **Per-app channel assignment** — pick a channel for any app; its traffic egresses through that link
  while other apps keep using theirs.
- **Independent configuration for cloned apps** — app-clone spaces (e.g. Xiaomi XSpace, user 999) and
  work profiles are listed as separate entries (`WeChat (999)`), so a clone and its primary install can
  use *different* channels.
- **Per-app DNS that follows the channel** — queries of an assigned app are redirected to that channel's
  resolver, so name resolution does not stay behind on the default network (or inside a VPN tunnel).
- **Dual-stack** — IPv4 and IPv6 rules are installed together.
- **LAN bypass** — on-link subnets are pinned to the interface that owns them, so intranet devices
  (NAS, printers, casting) stay reachable from apps assigned elsewhere.
- **Automatic boot recovery** — rules survive reboots: a `service.d` script, a wake-up broadcast from the
  module, and the app's own network callback cover each other.
- **Screen-off secondary Wi-Fi keep-alive** — optional; prevents the OEM power policy from tearing the
  secondary Wi-Fi link down when the screen turns off (Xiaomi dual Wi-Fi).
- **Channel details** — interface, addresses, gateway, DNS, MTU, metered state, SSID and **Wi-Fi band and
  channel**, plus a per-channel public-IP probe with presets or a custom endpoint.
- **Appearance and language** — theme (follow system / light / dark, animated), Material You colour, and
  per-app language (follow system / 简体中文 / English).
- **Module status** — reports precisely whether the hooks are installed, whether `system_server` is
  still running an older build, or whether the status record is stale.
- **Diagnostics you can hand over** — one copyable snapshot with root and module state, Wi-Fi mapping,
  rule effectiveness (configured vs. actually in the kernel), the boot-recovery log and recent kernel rules.
- **No VPN** — traffic is routed in the kernel; no userspace TCP/IP stack, so throughput and latency stay
  close to native.

---

## Installation

1. Install the APK from the [releases page](https://github.com/Linoleic/MultiRoute/releases) — the asset
   is named `MultiRoute-<VersionCode>-<VersionName>.apk`. The module declares an update manifest, so the
   manager can offer updates in place.
2. Open **LSPosed Manager → Modules → MultiRoute**, enable it and set the scope to **System Framework**
   (`system`). The APK already declares this in `META-INF/xposed/scope.list`.
3. Reboot, or restart the system server: `su -c 'setprop ctl.restart zygote'`.
4. Open MultiRoute and grant root. The **Settings** tab should report *activated (hooks ready)*.
   If it reports *loaded older build*, restart the system server once more.
5. For routing apps over cellular while Wi-Fi is connected, enable **Mobile data always on**
   (developer options) or the ROM's own "keep cellular active" setting.

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
| **App routing** | Tap an app and choose a channel; a long press enters multi-select for batch assignment. Cloned apps appear as separate entries with their numeric space id. |
| **Channels** | Inspect every active link (interface, IP, gateway, DNS, MTU, SSID, Wi-Fi band and channel) and run a per-channel public-IP probe. |
| **Settings** | Module status, root status, current kernel rules, appearance and language, and the keep-alive switches. **Copy diagnostic log** puts the full snapshot on the clipboard. |

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

- **Module shows "not activated"** — enable it in LSPosed Manager, scope it to the **system framework**,
  then reboot or restart `system_server`.
- **Module shows "loaded older build" after an update** — expected: the framework cannot hot-reload.
  Reboot, or `su -c 'setprop ctl.restart zygote'`.
- **An assignment has no effect** — check **Settings → Copy diagnostic log**: section *rule
  effectiveness* shows every configured UID with *in effect* or *not in effect*. A channel whose
  interface has no default route at that moment is skipped, and the log says so.
- **Rules disappeared after a reboot** — check the boot-recovery log in the same snapshot
  (section 3, `/data/adb/multiroute/last_boot_sync.log`). Also confirm KernelSU/Magisk/APatch executes
  `service.d` scripts on this ROM.
- **Secondary Wi-Fi channel is missing** — the hardware must support dual Wi-Fi and the ROM must have
  it enabled and connected to a second access point.
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
- **The only outbound request is one you start yourself.** The egress probe on the Channels screen queries
  a public-IP endpoint that you choose — a built-in preset (all HTTPS) or your own URL. As with visiting
  that endpoint in a browser, it necessarily sees the public IP you are probing *from*; it is not sent any
  device identifier, app list or rule contents. **If you never run the probe, the app makes no network
  requests at all.**
- **Root access is scoped to routing.** `su` is used to add and remove policy-routing rules, to write the
  boot-recovery script and to read the resolver/boot-log details shown in diagnostics. Nothing is executed
  on behalf of anyone else and nothing is reported back.
- **The app list is read, never reported.** `QUERY_ALL_PACKAGES` exists so the app can list installed apps
  (and their clone-space instances) for assignment.

---

## Known limitations

- **Only port 53 is redirected.** Classic DNS follows the assigned channel; Private DNS (DoT, 853) and
  DoH are not rewritten. Measured with a full-tunnel VPN active: name resolution could still stay inside
  the tunnel while the app's data traffic left it — see
  [docs/VERIFICATION.md](docs/VERIFICATION.md) for the exact observations.
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
- **Screen-off keep-alive targets Xiaomi's dual Wi-Fi classes** (`SlaveWifiService`, `DualStaImpl`), and
  the hooks are only installed while that switch is on. On other ROMs the switch is inert.
- **Cellular assignment** relies on the ROM honouring `mobile_data_preferred_uids`.
- **Which interface sits on which band can change while the app is running** — that is exactly what the
  Wi-Fi band row is for.
- **Cleartext HTTP is permitted for the egress probe.** Built-in presets use HTTPS, but a custom endpoint
  may be plain HTTP (for example a router page such as `http://192.168.1.1/ip`), which is why
  `usesCleartextTraffic` remains enabled.
- Verification so far covers a limited set of devices and ROM versions (see the compatibility table);
  other ROMs may differ.

---

## Building

```bash
# Debug / release APK
./gradlew assembleDebug
./gradlew assembleRelease

# Unit tests (43 tests, pure logic only)
./gradlew testDebugUnitTest
```

- JDK 17, Android SDK Platform 36.
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
