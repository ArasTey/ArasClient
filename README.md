# ArasClient

A fast, modern VPN client for Android — powered by the Xray core.

ArasClient supports VLESS, VMess, Trojan, Shadowsocks, SOCKS, HTTP, Hysteria2,
WireGuard, proxy chains and policy groups, with a clean, smooth, fully reworked UI.

## Features

- **Smart Connect (⚡)** — pings all your servers at once and connects to the fastest one
- **Auto-sort by ping** after speed tests, live re-sorting while tests run, auto-scroll to top
- **Per-server test button** under each config card
- **Anchored animated menus** (+ and ⋮ open smoothly right under their buttons)
- **TXT & QR export** for single configs and whole subscriptions
- **First-run setup dialog** with quick toggles, plus "Reset settings" in Settings
- **Subscription management** with fast batch import and update
- **Routing, per-app proxy, backup/restore**, multiple protocols editors
- **Vazirmatn typography** and an Aras blue/cream theme with light & dark modes

## Download

Grab the latest APK from the [Releases](https://github.com/ArasTey/ArasClient/releases) page:

| File | Use |
|---|---|
| `ArasClient-x.y-arm64-v8a.apk` | Most phones (recommended) |
| `ArasClient-x.y-universal.apk` | Any device |

## Building

Requirements: JDK 17+, Android SDK (platform 37, build-tools 36.0.0).

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/<variant>/ArasClient_<version>_<abi>.apk`

## Contact

- GitHub: [ArasTey](https://github.com/ArasTey)
- Telegram: [@imArasTey](https://t.me/imArasTey)

## License

GPL-3.0 — see [LICENSE](LICENSE).
