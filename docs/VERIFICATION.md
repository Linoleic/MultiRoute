# Verification log

What has actually been exercised on real devices, together with the commands behind each claim.
Everything below was observed on a device; nothing is inferred from the source alone.

## Platforms

| Platform | Root | Xposed framework | Links present |
| :-- | :-- | :-- | :-- |
| Android 17 / HyperOS (phone) | KernelSU | official LSPosed v2.2.0 (build 7906) | primary Wi-Fi + secondary Wi-Fi + cellular |
| Android 16 / HyperOS (tablet) | KernelSU | official LSPosed v2.2.0 (build 7854) | primary Wi-Fi + secondary Wi-Fi (no modem) |

Both devices run the same official LSPosed v2.2.0, differing only in build number, so the module's
LibXposed API 102 usage is exercised against one consistent implementation on two Android versions. Both
scope the module to `system_server` only.

## Results

| Capability | How it was checked | Result |
| :-- | :-- | :-- |
| Per-app egress (IPv4) | `ip rule show \| grep 14500`, `ip route get 8.8.8.8 uid <uid>` | ✅ rule exists and the lookup resolves to the assigned interface |
| Per-app egress (IPv6) | `ip -6 rule show \| grep 14500` | ✅ installed alongside IPv4 |
| **Real traffic follows the assignment** | assigned a browser to the non-default Wi-Fi, killed it, reopened it, then compared `ss -tnp` source addresses and `/sys/class/net/*/statistics/tx_bytes` | ✅ every fresh connection used the assigned interface's address; its TX counters grew (~70 KB during the test) |
| **Clone space configured independently** | `com.tencent.mm` exists as uid `10315` and as XSpace uid `99910315`; assigned the two to different interfaces | ✅ `14500: uidrange 10315 … wlan1` and `14500: uidrange 99910315 … wlan0`, both stacks, and `ip route get` confirmed distinct egress |
| LAN bypass rules | `ip rule show \| grep 14400` | ✅ generated per channel from that channel's own connected prefixes (rules observed pointing at the secondary link's subnet while it owned it) |
| Boot recovery after a **hard** reboot | `su -c 'cat /data/adb/multiroute/last_boot_sync.log'`, `ip rule show` | ✅ `service.d` applied rules before unlock (`all channels ready (mask 1); applying rules`, `rc=0`) |
| Recovery after a **soft** reboot | module log + rule polling | ✅ after the wake-up ordering fix; the module now waits for `sys.boot_completed` and retries at +15s/+60s/+180s |
| Module status detection | Settings screen and the published beacon | ✅ reports activated / hooks installed, and flags an outdated `system_server` build as “needs a soft reboot” |
| Screen-off secondary Wi-Fi keep-alive | enabled OEM auto-teardown, cleared the transient property, ensured the app was **not** running (so preferences were unreadable), turned the screen off for 45s | ✅ teardown suppressed twice, secondary link stayed up; with the transient property and preferences both unavailable, the persistent flag is what made it work |
| Offline channel handling | pointed a rule at a non-existent interface and synced | ✅ the offline channel is skipped, its stale rules are removed, the boot script matches |
| Concurrent sync | fired two restore broadcasts back to back | ✅ the resulting rule set was complete (a full script is applied under a mutex, with a 30s budget) |
| **VPN precedence (synthetic)** | added synthetic per-UID rules at the priorities netd uses for VPNs (`12000` output-to-local, `13000` secure VPN, `14000` prohibit non-VPN — from `system/netd/server/RouteController.h`) alongside MultiRoute's `14500`, then queried `ip route get 8.8.8.8 uid 10130` | ✅ rules at `12000`/`13000`/`14000` win over MultiRoute; a rule at the platform's per-UID selection priority (`15040`) loses. A block implemented as `unreachable default` is **not** leaked (`No route to host`), while an empty table falls through by construction |
| **VPN precedence (real client, full tunnel)** | client running on a Wi-Fi link (`tun0`, default network), then assigned an app to the other Wi-Fi with the same rule MultiRoute installs | ✅ the client's per-UID capture rules sit at **`24000`** — *below* MultiRoute — so the assignment wins: `ip route get` and the app's real connections moved from the tunnel to the assigned link's address. Android's own `12000` rule appeared as expected; `13000`/`14000` exist only for an always-on VPN with *block connections without VPN* |
| **Hook-visible state, and its agreement with routing, while a VPN is active** | built `tools/probe` (`:probe`), read what it reports about itself, then assigned it to the other Wi-Fi through the app and relaunched it | ✅ before: `active: 131 [wifi+vpn] iface=tun0`; after: **`active: 130 [wifi] iface=wlan1`** while the VPN network stayed in `getAllNetworks()`. Its real connections then sourced from the assigned link's address (`ss -tnp`), so the hooks and the kernel route agree |
| **DNS of an assigned app while a VPN is active** | probe's own resolution plus the routing of the resolver address it is handed | ✅ resolution kept working: the VPN's resolver (`172.19.0.2`) is an on-link address of the tunnel, so it is reached through the tunnel even for an assigned UID (queries stay inside the VPN while the data leaves it). A resolver that is *not* on-link would follow the assignment instead |

| **Scenario plans: triggers, overrides, pinning and priority** | stored plans written directly into the app's preferences, then compared `ip rule show pref 14500` and the app's own log line for each case | ✅ an SSID match applies the override (`14500: from all uidrange 10301-10301 lookup wlan1` plus `Scenario 'SSID scenario' active (matched automatically): overrides=1 … ssid=H3C_CA202C`); a non-matching SSID falls back to the base (zero rules); a manual pin applies regardless of the trigger (`(pinned by hand) … ssid=-`); a plan forcing `default` removed a base rule and reported `forcedDefault=1`, leaving zero rules |
| **Scenario plans react to a real network change** | started the app, turned Wi-Fi off and back on, then polled the kernel | ✅ the app re-synced on its own (13 log entries during the transition) and the rule was back on the assigned interface once the device reconnected to the same SSID |

| **A plan switches even while the app is not running** | stored a plan, killed the app (verified with `pidof`), toggled Wi-Fi off and on, then polled the module log, the process list and the kernel | ✅ the module logged `[Scenario] Link changed and plans exist; waking the app to re-evaluate`, the app process came back on its own (`pidof` returned a new pid) and re-applied the rule (`Boot route rules sync completed. Result: true`). With the plan deleted the same toggle woke nothing: zero such log lines and the app stayed dead |
| **Editing a plan's overrides and its order** | injected two plans, opened the plan sheet on device, entered "edit apps", and reordered them | ✅ the sheet listed both plans with their conditions and status; "edit apps" opened the selection with the plan's override preselected and the header reading `Editing: <plan>`; reordering renumbers the stored priorities so the stored order is the evaluation order |

## Findings that changed the implementation

1. **Boot restore had to be reordered.** After a *soft* reboot neither `service.d` nor `BOOT_COMPLETED` runs
   again, and a broadcast sent before boot completion is dropped outright
   (`Cannot broadcast before boot completed`). The module now waits for `sys.boot_completed` and retries.
2. **A locked device can only be restored by `service.d`.** With the screen still locked after a reboot the
   app cannot run at all: its credential-encrypted storage is unavailable and broadcasts are not delivered
   before the first unlock. Only the root `service.d` script restored the rules, which is why it is treated
   as the primary mechanism.
3. **Rule scripts need a real budget.** A full script spawns 20–30 `ip` processes; the default 5s timeout
   was exceeded on a busy device and the shell was killed mid-way, leaving part of the rules applied and
   the sync reporting failure.
4. **Stale rules hide an inactive assignment.** A rule whose table has no default route never matches (the
   kernel falls through to the next rule), so the app silently keeps using the default network. Rules are
   now rewritten from the channels that are usable at that moment.

## Not verified end-to-end

- **Cellular channel assignment** — `mobile_data_preferred_uids` is written, but no app was routed over
  cellular and its traffic observed (the available devices did not allow an isolated cellular-only test).
- **LAN reachability from an assigned app** — the bypass rules are generated and installed, but no
  intranet round trip was measured.
- **DNS behaviour** — routing is redirected; name resolution is not managed (see “Known limitations”).
  Measured with a VPN: an assigned app's queries to the tunnel's own resolver stayed inside the tunnel
  because that address is on-link there, while a resolver that is not on-link would follow the assignment.
- **Scenario plan evaluation needs the module loaded** — the module watches link changes only once it is
  running in `system_server`, so after installing a new module build a soft reboot (or reboot) is required
  before a plan keeps switching while the app is dead. Nothing else about the plan evaluation is unverified.
- **One ROM family** — both devices are Xiaomi HyperOS.

## Investigated, not a MultiRoute defect

An occasional “most Wi-Fi networks are missing from the system Wi-Fi list, then it recovers” report was
traced to the vendor Wi-Fi HAL, not to this module:

```
E WifiHAL : Received fatal event, sending alert
I MiuiWifiHalHandler: doSupplicantCommand is:DRIVER SetScanExtFlag 1
```

It reproduced deterministically by toggling Wi-Fi off and on, **with the module installed but inert** —
zero intercepted calls, no rules configured, no app preferences, and no MultiRoute frame anywhere in the
Wi-Fi path (the ROM's own `SlaveWifiService: MLOEXECUTE` lines show the calls passing straight through).
The framework resets its scan handling after the HAL reports the fatal event, which matches the empty list
followed by a slow recovery.
