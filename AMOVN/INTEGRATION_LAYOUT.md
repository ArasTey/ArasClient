# Android integration layout and ownership map

This document defines where selected ZX VPN and SlipNet capabilities belong when they are adapted into G-ST-VPN. It is an implementation map, not a claim that the features are already integrated.

## Integration rules

- Keep the production app Android-only: Kotlin + Jetpack Compose in the existing `app` module.
- Keep `AMOVN/ZX-source/` as the canonical upstream source mirror. Files under `AMOVN/` are reference material, not compiled app source.
- Do not add ZX's Kotlin Multiplatform `shared` module, desktop sources, Compose Multiplatform resource generator, Hilt graph, or unrelated build plugins wholesale.
- Adapt behavior to G-ST-VPN's existing services, persistence, navigation, theme picker and profile model. Do not create parallel app shells or duplicate VPN services.
- Preserve the existing app's features and screens outside the named integration scope.
- Keep all work on `not-SIGNAL/NOT-NET`; do not modify `main`.

## Destination map

| Capability | Runtime / domain destination | UI destination | Integration point |
|---|---|---|---|
| DNS scanner and resolver tests | `app/src/main/java/com/aras/client/core/dns/` for DNS packet/probe logic; `dto/dns/` for result models | Existing `ui/dns/DnsScannerActivity.kt` and new scanner UI state/view-model in `ui/dns/` | Existing `DnsChangerStore` and DNS profile flow; only apply a healthy resolver after an explicit user action |
| Resolver scan lifecycle | Coroutine-based foreground/user-triggered orchestration in `ui/dns/`; add a dedicated service only if a verified long-running background requirement exists | Existing DNS Scanner screen and results section | Do not transplant SlipNet's Hilt/DataStore/WorkManager/service stack or tunnel-specific end-to-end scanner wholesale |
| Tor engine and bridge runtime | `core/tor/`; bridge/config models in `dto/tor/` or the existing profile/config model | Existing server/profile editor for bridge selection; Settings only for global Tor defaults | Existing `CoreVpnService` lifecycle and socket-protection path; do not add a second VPN service |
| Psiphon engine | `core/psiphon/`; profile/config models in existing profile/config packages | Existing server/profile editor; country and mode controls belong to that editor | Existing `CoreVpnService` and native-library packaging; reuse current Psiphon assets/libraries only after ABI and license checks |
| Sing-box engine | `core/singbox/` for Libbox lifecycle, TUN callbacks, network monitor and statistics; JSON preparation in `core/singbox/config/` | Existing server/profile UI and connection status surfaces | Integrate through the existing VPN service orchestration; verify the exact AAR/native ABI and avoid parallel TUN ownership |
| OpenVPN import | `core/config/openvpn/` for parsing/validation/conversion | Existing server import/custom-config flow under `ui/server/` | Use existing profile persistence and import result handling; do not create a separate server list |
| Live Monitor | Keep device sampling and metric collection out of Composables, under `core/monitor/` or `service/`; typed samples in `dto/monitor/` | Existing `ui/monitor/LiveMonitorActivity.kt` | Extend the existing monitor screen; retain 1-second sampling and bounded history, with API-level guards for thermal metrics |
| Connection Map | Geo lookup and payload parsing under `core/map/` or `util/`; map data/assets under Android assets | Existing `ui/map/ConnectionMapActivity.kt` | Replace/adapt the map renderer inside the existing destination; do not add a second map route or duplicate IP lookup |
| Settings and appearance | Existing `handler/SettingsManager.kt` / `SettingsViewModel.kt` persistence | Existing `ui/settings/SettingsActivity.kt` and `ui/compose/` theme components | Port only relevant setting controls and theme tokens; keep the current app's settings navigation and theme picker |
| Theme, Liquid surfaces, motion and transitions | Reusable Android Compose components under `ui/compose/` | Existing screens and theme picker | Translate ZX theme values into the current app theme; do not import the KMP theme/resource stack unchanged |
| Icons, strings and other resources | Android `res/drawable*`, `res/values*`, and `assets/` | Referenced by the existing Android Compose screens | Convert Compose Multiplatform resources to Android resources and keep namespaced assets organized |
| Navigation and manifest | Existing Android activity/service declarations and intent routing | Existing `MainActivity` and Settings navigation | Add only required declarations and permissions; do not duplicate existing destinations |

## SlipNet DNS scanner adaptation boundary

SlipNet's scanner repository includes more than generic DNS resolver checks: its large repository implementation also references SlipNet-specific tunnel types, SSH, DNSTT/VayDNS/Slipstream bridges, profile entities, Hilt and end-to-end tunnel tests. Those dependencies are not drop-in components for G-ST-VPN.

The reusable behavior should be isolated and adapted to the existing DNS scanner: resolver pool parsing and validation, bounded concurrency, UDP/TCP DNS probing, A/AAAA/NS/TXT and EDNS checks, randomized-name/NXDOMAIN checks where meaningful, latency and per-check results, cancellation/progress, and an explicit action to use a healthy resolver. Do not report a definitive DPI verdict from a timeout or a synthetic DNS response. The connect-time `DnsResolverProber` is a separate tunnel-parameter calibration feature and must not be presented as the resolver compatibility scanner.

A background `ScanService` is not required for the current user-triggered scan flow. Add one only if product behavior requires scanning to continue after the screen is closed, and then use Android's appropriate foreground-service rules and a G-ST-VPN-owned state contract.

## Upstream source and licensing

- ZX VPN source mirror: `AMOVN/ZX-source/`, upstream ref `config-finder-liquid-integration`.
- SlipNet DNS references: upstream commit `2ed00fd0072ad0b558e30ec6b5e8b30948f98894`.
- The inspected ZX and SlipNet license files are AGPL-3.0. Preserve copyright/license notices and third-party notices for every adapted source or binary. Verify each dependency's own license independently; repository-level licensing does not automatically cover bundled native libraries.
- Any combined/derived distribution must be reviewed for AGPL-3.0 compliance, including source availability and notices. This note is not legal advice.

## Verification gates

A feature is not considered integrated merely because its source is copied. Before calling an item complete:

1. It is placed in the destination above and connected to the existing app entry point.
2. Its dependencies, Android manifest declarations, permissions and ABI assets are accounted for.
3. It compiles in the Android app module.
4. Unit tests cover parsing/state/probe behavior where practical.
5. Device tests cover VPN lifecycle, network changes, DNS behavior and API-level limitations as applicable.
6. Existing unrelated features remain unchanged.
