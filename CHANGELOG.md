# Changelog

## v1.0.0

Initial release.

- **Per-app network channels**, enforced in the kernel with policy routing (`ip rule`, IPv4 and IPv6).
- **Independent configuration for cloned apps** — OEM clone spaces (e.g. Xiaomi XSpace, user 999) and work
  profiles are listed separately, so a clone and its primary install can use different channels.
- **Dynamic interface discovery** — no hard-coded interface names; primary Wi-Fi, secondary Wi-Fi
  (dual Wi-Fi), cellular and Ethernet are all supported.
- **Per-channel LAN bypass**, generated from each link's own on-link prefixes, so intranet devices stay
  reachable from apps assigned elsewhere.
- **Automatic boot recovery** — a generated `service.d` script, a wake-up broadcast from the module and the
  app's own network callback cover each other; the root script is what restores rules on a device that is
  still locked.
- **Hooks inside `system_server`** keep the app-visible network state consistent with the assignment, and
  keep the secondary Wi-Fi link alive across screen-off on Xiaomi ROMs.
- **Precise module status** — reports activated / hooks missing / outdated `system_server` build (needs a
  reboot) / stale record instead of a single vague flag.
- **Egress diagnostics** — per-channel public-IP probe with HTTPS presets or a custom endpoint.

Verified on Android 16 and Android 17 (HyperOS) with KernelSU and LSPosed v2.2.0. The commands and
observations behind every claim, plus what is *not* covered yet, are in
[docs/VERIFICATION.md](docs/VERIFICATION.md).
