# AMOVN Integration Status

Target repository: `AMINOPY/G-ST-VPN`  
Target branch: `not-SIGNAL/NOT-NET`  
Upstream ZX source ref: `AMINOPY/ZX-VPN-@config-finder-liquid-integration` (`41796b68fc171a351fa668d7991499cca395c0d0`)  
Upstream SlipNet source ref: `anonvector/SlipNet@main` (`2ed00fd0072ad0b558e30ec6b5e8b30948f98894`)

This file distinguishes code already adapted into the Android app from source that has only been audited. Pinned ZX and SlipNet source files are now mirrored under `AMOVN/ZX-source/` and `AMOVN/SlipNet-source/`; these are reference snapshots, not Android source sets. A source mirror or UI entry is not evidence that an engine is operational.

## Adapted into the target app

| Area | Current target implementation | Status |
|---|---|---|
| DNS Scanner | Existing native DNS wire probes for A, AAAA, NS, TXT, EDNS, randomized names, NXDOMAIN, UDP with TCP fallback | Extended |
| DNS scan lifecycle | Process-scoped `DnsScanCoordinator`, live per-resolver progress, cancellation, state survives Activity recreation/navigation | Implemented in app |
| Resolver selection | Explicit action applies a healthy resolver to the existing DNS profile | Preserved |
| Connection Map | ZX-derived Compose Canvas renderer, adapted `WorldMap` parser, offline `app/src/main/assets/map/world.bin`; current IP lookup and saved origin/exit are retained | Adapted |
| Live Monitor | Target already samples CPU/RAM/battery/thermal/process metrics every second and keeps up to 120 CPU/RAM samples; thermal headroom is sampled periodically | Existing target implementation retained |
| Settings / themes / ZX UI | Target already has `SettingsActivity`, `ThemePicker`, `ThemeManager`, and a selectable `ZxUiActivity` wired to the existing `MainViewModel` | Existing target implementation retained |

### DNS Scanner boundary

SlipNet's `DnsResolverProber` is a tunnel-parameter calibrator, not the general resolver compatibility scanner. Its QNAME/rate probing is tied to SlipNet tunnel protocols and is not copied into the target DNS changer. SlipNet's E2E resolver-pool phase also depends on SlipNet-specific DNSTT/NoizDNS/VayDNS/Slipstream clients; that code is not interchangeable with G-ST-VPN's Xray/HEV data plane.

The target scanner remains foreground and user-triggered. It does not yet have a persistent foreground `ScanService`, process-death resume, saved scan history/export, or DoH/DoT probing. A wildcard/synthetic DNS answer is a diagnostic clue, not proof of DPI.

## Audited but not yet operationally integrated

| Capability | Source found | Blocking integration requirement |
|---|---|---|
| Sing-box | ZX `SingBoxEngine`, `SingBoxConfigs`, `SingBoxJson`, `ZedVpnService` | ZX Gradle references `app/libs/zedcore.aar`, but that AAR is not present in the tracked ZX branch tree. Target has its own `aras-core.aar`, not a verified Libbox binding. Protocol-name parsing is not runtime support. |
| Tor | ZX `TorController` / bridge settings; SlipNet `SnowflakeBridge` and `TorSocksBridge` | ZX controller launches `libtor.so`; no verified Tor binary is present in the tracked ZX tree or target ABI folders. SlipNet's Snowflake Go binding/runtime is also not present in the target. Existing `liblyrebird.so` alone is not a Tor engine. |
| Psiphon | Target already runs Psiphon through the native Aether helper: `AetherCoreManager` supplies `--psiphon*` options, `AETHER_PSIPHON_BIN`, configuration/work-directory environment, and readiness detection; `libpsiphon-tunnel-core.so` is packaged for arm64-v8a, armeabi-v7a and x86_64. | The ZX Java `PsiphonTunnel` controller is not wired into `CoreVpnService`, and should not be added blindly because the target already has a native Psiphon execution path. Validate Aether Psiphon connect/stop, profile chaining and region/CDN modes on device; x86 has no Psiphon/Aether binary. |
| OpenVPN import | ZX `OvpnConfig`, `ConfigRepository.addOvpn`, `ServersScreen` | The importer converts .ovpn to a Sing-box OpenVPN outbound. It cannot be advertised as connectable until a verified Sing-box runtime is packaged and wired to the target VPN service. |
| Full SlipNet Tor chain | SlipNet full-flavor bridge, service, profile and chain code | SlipNet's service, Hilt graph, database, tunnel clients and native build are app-specific. Port generic pieces only; do not copy a second `VpnService` or take ownership of a second TUN. |

## Architecture rules

- Keep the target Android app as the single host application and keep `CoreVpnService` as the existing VPN/TUN owner unless a reviewed migration explicitly replaces it.
- Keep native engines and their original implementation languages/bindings where supplied. Do not translate a native Go/Rust/C component into Kotlin or silently substitute a different engine.
- Do not add ZX's KMP `shared` module, desktop source set, Compose Multiplatform resource generator, Hilt graph, or SlipNet database wholesale to the Android-only target.
- Adapt UI and persistence into the target's existing MMKV/profile/settings/navigation patterns.
- Verify each AAR, .so, Go/Rust artifact and transitive dependency by ABI, version and license before packaging.
- Preserve AGPL-3.0 notices and any additional upstream notices.

## Validation state

- The DNS and native-map changes are committed to `not-SIGNAL/NOT-NET`.
- GitHub Actions validation was triggered by the branch pushes; the latest run must complete before build status can be claimed.
- Aether's Tor/Psiphon command-line integration is present in the target source, but no device-level Tor bootstrap or Psiphon handshake is claimed. No Sing-box TUN, Snowflake bridge, IPv6 leak, or OpenVPN end-to-end test is claimed here.
- `main` is not modified by these commits.
