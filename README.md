<p align="center">
  <img src="app/src/main/play_store_512.png" width="128" alt="WhiteDNS logo">
</p>

# WhiteDNS

WhiteDNS is an Android application for running a local DNS tunneling client with proxy and VPN modes.

> **NOTICE:** WhiteDNS is source-available proprietary software. The code is published for transparency, review, and contribution to this official project only. You may not copy the app into a separate product, publish modified builds, repackage APKs, redistribute binaries, clone the branding, or reuse the WhiteDNS name, logo, icon, design, or visual identity.

> **APP STORE WARNING:** WhiteDNS does not have any publication on Google Play. Any WhiteDNS APK, listing, or package found on Google Play or another app marketplace is not an official release from this project and may be modified, outdated, or unsafe. Use only this repository and the official Telegram channel for project updates.

Official channel: [https://t.me/whitedns](https://t.me/whitedns)

## Credits

WhiteDNS is backed by the [CottenDns Client](https://github.com/masterking32/CottenDnsVPN) project and uses CottenDns, a fork from CottenDns, from [https://github.com/TaJirax/cottenDNS]

The Android VPN path also packages `tun2proxy`; see [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md) for third-party license details.

## Features

- Android client for WhiteDNS / CottenDns based DNS tunneling.
- Proxy mode with local SOCKS5 support and optional HTTP proxy bridge.
- VPN mode using Android `VpnService` and packaged `tun2proxy` native libraries.
- Built-in and custom server profile support.
- Native `CottenDns://` profiles plus `stormdns://` and `masterdns://` compatibility-profile import.
- Resolver profile management with validation and default resolver assets.
- Split tunnel options for VPN routing.
- Runtime connection logs, resolver state, progress, and traffic statistics.
- Foreground service notifications for long-running proxy and VPN sessions.
- Jetpack Compose UI with Material 3 components.


- [LICENSE](./LICENSE.MD)
- [CONTRIBUTING.md](./CONTRIBUTING.md)
- [CLA.md](./CLA.md)
- [TRADEMARK.MD](./TRADEMARK.MD)
