# MultiRoute probe

A deliberately tiny, dependency-free app (`:probe`) used while verifying MultiRoute on a device. It
reports two things from inside an ordinary app:

1. **What the app sees** — the active network and every network, with their transports (wifi / cell /
   vpn / eth), interface name and DNS servers. This is the part MultiRoute's `system_server` hooks
   influence, and a shell cannot observe it for another UID because `su <uid> -c` cannot impersonate an
   app's sockets.
2. **What actually happens to its traffic** — whether `www.example.com` resolves, and which public IP an
   HTTPS egress probe reports.

Assigning *this* app to a channel in MultiRoute and comparing the two halves is how "the app sees the
assigned channel" and "the traffic really leaves through it" are checked together — including while a VPN
is active, where the two can disagree.

It hooks nothing, ships in no release artifact, and is not part of the module. Build and install it with:

```bash
./gradlew :probe:assembleDebug
adb install -r tools/probe/build/outputs/apk/debug/probe-debug.apk
```

The latest report is also saved in the probe app's private `files/last-probe.txt`, so a root ADB session
can collect it while the activity is behind keyguard. It contains network details; keep collected
reports local. Uninstalling the probe removes its saved report.

For a separate IPv4 HTTPS check without changing the module or binding a Network, pass a test endpoint:

```bash
adb shell am start -n com.multiroute.probe/.ProbeActivity --ez ipv4_only true
```

This connects a regular unbound socket to an IPv4 address, then performs TLS with HTTPS hostname
verification. The default hostname probe remains useful for detecting failures when a network
advertises an unusable IPv6 path. An IPv4-only success does not establish working IPv6. A different
HTTPS test endpoint can also be supplied with `--es egress_url https://example.com/`.

Controlled comparisons also support `--ez ipv6_only true`, `--es bind_iface wlan1`,
`--es destination_ip <literal-IP>` and `--ez resolve_only true`. Socket binding occurs before connect;
pinned addresses keep TLS verification against the HTTPS endpoint's hostname. Reports identify the
binding, destination and local socket address and end with `PROBE_COMPLETE`. For a native-network
control, clear the probe's MultiRoute assignment first. A failed bind (for example EPERM on a cellular
network) is an access failure, not evidence that the network cannot carry IPv6.
