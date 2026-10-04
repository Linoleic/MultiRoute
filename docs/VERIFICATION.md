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
- **VPN interaction** — the precedence over the platform's per-UID bindings is understood from rule
  priorities, not measured against an active always-on VPN.
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
