<div align="center">

<img src="art/banner.svg" alt="ArasClient" width="100%"/>

# ⚡ ArasClient

**The fastest way to connect.** A modern VPN client for Android — pick a server, tap once, done.

[![Release](https://img.shields.io/github/v/release/ArasTey/ArasClient?style=for-the-badge&logo=github&color=0284c7&labelColor=101418)](https://github.com/ArasTey/ArasClient/releases)
[![License](https://img.shields.io/badge/GPL--3.0-licensed?style=for-the-badge&color=38bdf8&labelColor=101418)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-7.0%2B-3ddc84?style=for-the-badge&logo=android&labelColor=101418)]()
[![Telegram](https://img.shields.io/badge/Telegram-%40imArasTey-26a5e4?style=for-the-badge&logo=telegram&labelColor=101418)](https://t.me/imArasTey)

</div>

---

## ✨ Why you'll like it

| | |
|:---:|---|
| ⚡ | **Smart Connect** — pings *all* your servers at once and connects to the fastest one automatically |
| 🔄 | **Global auto-sort** — after every test the whole list re-ranks across *all* subscriptions, fastest always on top |
| 🔐 | **`.arasc` encrypted sharing** — export & import configs in ArasClient's own secure container, with optional password protection |
| 📊 | **Subscription info** — traffic used/total and expiry time read straight from the subscription link (v2Box style) |
| 📢 | **Announcements** — provider announcements & creator messages, shown as a clean banner, hidden when empty |
| 📤 | **Share anything** — every config exports as QR, TXT **or** `.arasc`; whole subscriptions too |
| 🪶 | **Feather-light UI** — clean rounded cards, buttery scrolling, zero clutter |
| 🌗 | **Light & dark** — Aras blue on warm cream, or a calm charcoal night mode |

### Full protocol support
`VLESS` · `VMess` · `Trojan` · `Shadowsocks` · `Hysteria2` · `WireGuard` · `SOCKS` · `HTTP` · proxy chains & policy groups

---

## 🔐 The `.arasc` format

ArasClient's own encrypted container for configs and subscriptions.

| Mode | What it does |
|---|---|
| **Normal** | The file is unreadable in text editors — configs travel inside an encrypted envelope |
| **Protected** 🔒 | Add a password: the receiver can **connect and ping only** — raw URIs, server addresses and secrets can never be viewed, copied, shared or re-exported, enforced at the data layer |

- **Strong, standard encryption** with authenticated integrity checks — implementation details are intentionally closed-source to keep Protected exports hard to reverse
- **Export**: Menu → *Export* → pick subscriptions/configs → Normal or Protected → save `ArasClient-Configs.arasc`
- **Import**: tap **+** → *Import .arasc file* — or just tap the file in any file manager and pick ArasClient

---

## 📥 Get it

<div align="center">

**→ [Download from Releases](https://github.com/ArasTey/ArasClient/releases) ←**

</div>

| File | For |
|---|---|
| `arm64-v8a.apk` | ✅ Most phones — recommended |
| `universal.apk` | Any other device |

> Install → open → the first-run wizard sets up sorting & preferences for you. That's it.

---

## 🔋 And there's more

- 🚦 Per-app proxy & full routing rules
- 💾 Backup & restore of everything
- 📋 Subscription management with fast batch import
- 🛡️ Protected configs: NPV-style — only connect & ping, nothing to peek at
- ♻️ "Reset settings" if you ever want a fresh start

---

## 📬 Contact

<div align="center">

[![GitHub](https://img.shields.io/badge/GitHub-ArasTey-181717?style=for-the-badge&logo=github)](https://github.com/ArasTey)
[![Telegram](https://img.shields.io/badge/Telegram-imArasTey-26a5e4?style=for-the-badge&logo=telegram)](https://t.me/imArasTey)

</div>

---

<div align="center">
<img src="art/hero-dark.svg" width="260" alt=""/><br/>
<sub>Built with ⚡ by ArasTey</sub>
</div>
