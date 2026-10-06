# Changelog

## v1.2.0

- **The application id changed** to `io.github.linoleic.multiroute`. The module repository only accepts a
  package namespace its author can prove ownership of, and `com.multiroute` would require
  `multiroute.com`. Android treats the new id as a different app, so **uninstall 1.1.x before installing
  this one** (assignments are not carried over) and enable the new package in LSPosed Manager, where it
  appears as a new module entry. The code namespace is unchanged, so the hooks and the module metadata
  are identical; the wake-up broadcast, the in-app hook check and the repository mirror all derive the id
  from the build now instead of hard-coding it.

## v1.1.1

- **Much smaller download.** The release build is minified with R8 now: the dex drops from ~42 MB to
  ~2.4 MB and the APK from ~43 MB to ~3.2 MB. R8 had always been off, which APK compression used to
  hide — until `minSdk` reached 30, where the dex is stored uncompressed and the download tripled. The
  hook entry point, the provider, the models and LibXposed are kept by `proguard-rules.pro`, and the
  module was re-verified on device after minifying (both hook families install, no class errors).
- **Wi-Fi band and channel** are shown for every Wi-Fi channel (for example `5 GHz · ch 36`), derived
  from the connection frequency — useful because which interface sits on which band changes while the
  device is running.
- **Android 11 is now the stated minimum.** The interfaces the module hooks, `getMobileDataPreferredUids`
  among them, only exist from Android 11 on, so `minSdk` is 30: the APK no longer installs on releases
  where it could only ever fail. Android 16 and 17 are the versions verified on device.
- The README now documents the module metadata the manager reads (scope, LibXposed API range), how to
  uninstall cleanly, and exactly what has and has not been verified.

## v1.1.0

- **Per-app DNS now follows the assigned channel.** Android picks the resolver from the default network
  regardless of where the kernel routes the packets, so an assigned app could query a resolver that was
  only reachable over a different link - or keep querying one inside a VPN tunnel. Queries of assigned
  UIDs are redirected to their own channel's resolver; the boot script flushes that chain and the app
  re-applies it with the resolvers of the current network, so a stale address can never break resolution.
- **Appearance and language settings.** Theme (follow system / light / dark) with a crossfade instead of a
  restart, a Material You toggle, and language selection (follow system / 简体中文 / English) through the
  platform per-app locale. The UI itself is now fully translated.
- **Xiaomi dual-Wi-Fi hooks are installed on demand.** They are no longer installed at boot unless the
  screen-off keep-alive is on, so the module stays out of the ROM's Wi-Fi code while the feature is unused;
  switching it on installs them immediately, without a reboot. The switch is labelled as Xiaomi-only.
- **Cellular preferred-UID bookkeeping.** Unassigning an app, or clearing every rule, now takes back only
  the UIDs this app added to the platform's preferred-mobile-data list.
- **Rule effectiveness and the boot-recovery log** are part of the copyable diagnostic snapshot, so a
  report shows what is configured, what the kernel actually holds, and what the boot script did.
- Internal clean-ups: channel labels and egress results are language-neutral values now (icons and
  success/failure no longer depend on display text), with 41 unit tests covering the pure logic.
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
