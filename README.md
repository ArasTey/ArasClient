
#   ArasClient

**A modern, fast and feature-rich VPN client for Android.**

[![Release](https://img.shields.io/github/v/release/ArasTey/ArasClient?style=for-the-badge&logo=github&color=0284c7&labelColor=101418)](https://github.com/ArasTey/ArasClient/releases)
[![License](https://img.shields.io/badge/GPL--3.0-licensed?style=for-the-badge&color=38bdf8&labelColor=101418)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-7.0%2B-3ddc84?style=for-the-badge&logo=android&labelColor=101418)]()
[![Telegram](https://img.shields.io/badge/Telegram-%40imArasTey-26a5e4?style=for-the-badge&logo=telegram&labelColor=101418)](https://t.me/imArasTey)

</div>

---

## 🧬 Based on PattNG

ArasClient is a **fork of [PattNG](https://github.com/patterniha/PattNG)**, which itself is a fork of [2dust/v2rayNG](https://github.com/2dust/v2rayNG).

> **ArasClient → PattNG → v2rayNG**

ArasClient keeps the foundation of PattNG while adding its own application features, configuration workflows, UI improvements, subscription tools and networking/core integrations.

### Upstream projects

- **PattNG:** https://github.com/patterniha/PattNG
- **Original v2rayNG:** https://github.com/2dust/v2rayNG

PattNG itself documents additions such as `cipherSuites`, unsafe fingerprint configuration/sharing, the **aether** core, and related Xray-core changes. ArasClient builds on that lineage rather than presenting itself as an independent implementation from scratch.

---

## ✨ Highlights

| | Feature |
|:---:|---|
| ⚡ | **Smart Connect** — test available servers and connect to a fast server automatically |
| 📶 | **Latency testing & global sorting** — test servers and reorder the list based on measured results |
| 🔄 | **Cross-subscription workflow** — manage and evaluate servers from multiple subscriptions |
| 💾 | **Persistent test information** — retain useful test/latency information with profiles |
| 🧹 | **Duplicate & invalid cleanup** — keep large server lists clean and manageable |
| 📥 | **Batch import** — import multiple configurations efficiently |
| 🔐 | **`.arasc` container** — ArasClient's own configuration/subscription export format |
| 🛡️ | **Protected configurations** — restrict access to sensitive configuration data while keeping profiles usable |
| 📊 | **Subscription information** — display traffic usage, total traffic and expiry information when available |
| 📢 | **Provider announcements** — show provider/creator messages inside the client |
| 📤 | **Flexible sharing** — share configurations through QR, TXT and `.arasc` workflows |
| 📱 | **Per-app proxy & routing** — Android application-level proxying and routing capabilities |
| 🌗 | **Light & dark themes** — a clean interface for both day and night |

---

## 🔌 Protocol Support

ArasClient supports the following configuration types exposed by the client:

`VLESS` · `VMess` · `Trojan` · `Shadowsocks` · `Hysteria2` · `WireGuard` · `SOCKS` · `HTTP`

It also supports proxy chains and policy groups where supported by the underlying configuration/core.

---

## ⚡ Smart Connect

ArasClient is built around a simple connection workflow:

```text
Test → Measure → Sort → Select → Connect
```

Features include:

- Test a single server
- Test all servers
- Run real connection tests
- Measure server latency
- Keep test results associated with profiles
- Sort servers using test results
- Keep untested servers after tested results
- Automatically connect after the test workflow
- Work across servers from multiple subscriptions

---

## 📡 Subscription Management

ArasClient provides an extended subscription workflow for managing large server collections:

- Update individual subscriptions
- Update all subscriptions
- Batch configuration import
- Subscription-aware profile management
- Traffic usage parsing
- Total traffic parsing
- Expiry-time parsing
- Subscription metadata display
- Cache-aware profile reconstruction
- GeoIP refresh workflow
- Provider announcements
- Free subscription synchronization and management

---

## 🧹 Configuration Management

Keep large configuration lists organized with:

- Multi-field search
- Search by remark
- Search by description
- Search by server
- Search by configuration type
- Duplicate identity detection
- Bulk duplicate cleanup
- Invalid-server cleanup
- Group-scoped cleanup
- Global cleanup
- Batch configuration import
- Latency information stored with profiles

---

## 🔐 Protected Configurations

Protected configurations are designed for controlled distribution of configuration profiles.

Depending on the protection mode, a protected profile can be used for connection/testing while sensitive configuration data is restricted from normal application workflows.

Protection workflows include:

- Connect to a protected profile
- Test/ping a protected profile
- Restrict raw URI extraction
- Restrict copying sensitive configuration data
- Restrict sharing
- Restrict exporting protected profiles
- Enforce protection at the application/data layer

---

## 📦 `.arasc` Configuration Container

ArasClient provides its own `.arasc` format for exporting and importing configurations and subscriptions.

### Normal export

- Package configurations inside an ArasClient container
- Keep exported configuration data out of plain-text config files
- Import the container back into ArasClient

### Protected export

- Optional password protection
- Intended for restricted configuration sharing
- Keeps protected profiles usable while restricting normal access to sensitive configuration data

### Subscription-aware export

The format can preserve information related to:

- Subscriptions
- Profiles
- Subscription/profile relationships
- Export metadata
- Export timestamp
- Optional notes

> The protected-container cryptographic implementation is intentionally not described here beyond its public behavior. Refer to the project implementation and distribution for the exact details.

---

## 🛠️ Advanced Networking & Core Integration

ArasClient also includes integrations beyond the Android UI layer, including:

- **AnyTLS** integration
- **AmneziaWG** support
- **AmneziaWG 3.1 parameters**
- **Jc / Jmin / Jmax** support for AWG
- **S1 / S2** support for AWG
- **H1 / H2 / H3 / H4** support for AWG
- **Standalone AmneziaWG tunnel** integration
- **AWG UAPI** integration
- **AWG device lifecycle** handling
- **Native TUN file-descriptor integration**
- **Process discovery / Process Finder** integration
- **Process-to-UID mapping**
- **Custom AndroidLibXrayLite integration**
- **Custom Xray-core integration**
- **TLS compatibility fixes**
- **Core/client integration for AWG and AnyTLS**

These components allow the Android client, native networking layer and proxy core to work together as one stack.

---

## 🧩 Architecture

```text
┌──────────────────────────────┐
│        ArasClient UI         │
├──────────────────────────────┤
│ Smart Connect                │
│ Subscription Management      │
│ Config Management            │
│ Protected Profiles           │
│ .arasc Import / Export       │
└──────────────┬───────────────┘
               │
┌──────────────▼───────────────┐
│     Android Networking       │
├──────────────────────────────┤
│ TUN Integration              │
│ Process / UID Mapping        │
│ Native Tunnel Support        │
└──────────────┬───────────────┘
               │
┌──────────────▼───────────────┐
│       Core Integration       │
├──────────────────────────────┤
│ Xray                         │
│ AnyTLS                       │
│ AmneziaWG                    │
└──────────────────────────────┘
```

---

## 📥 Installation

<div align="center">

**→ [Download the latest release](https://github.com/ArasTey/ArasClient/releases) ←**

</div>

| Build | Target |
|---|---|
| `arm64-v8a.apk` | Most modern Android devices — recommended |
| `universal.apk` | Universal device build |

### Quick start

1. Install ArasClient.
2. Add or import your configurations/subscriptions.
3. Run a server test if needed.
4. Use Smart Connect or select a profile manually.
5. Connect.

---

## 🌱 Credits & Upstream

ArasClient is a **PattNG fork** and ultimately descends from the v2rayNG project.

- **ArasClient:** https://github.com/ArasTey/ArasClient
- **PattNG:** https://github.com/patterniha/PattNG
- **v2rayNG:** https://github.com/2dust/v2rayNG

Additional components and ideas may originate from their respective upstream open-source projects. Please review the individual licenses and upstream repositories for component-specific attribution.

---

## 📄 License

ArasClient is released under the **GNU General Public License v3.0**.

See [LICENSE](LICENSE) for the full license text.

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
