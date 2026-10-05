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
