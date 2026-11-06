# SlipNet Tor and DNS Scanner Integration Plan

Target: `AMINOPY/G-ST-VPN` branch `not-SIGNAL/NOT-NET`.

This document records the source audit and the safe integration boundary for bringing SlipNet-inspired Tor and DNS Resolver Scanner capabilities into this application.

## Implementation status (branch snapshot)

- DNS Scanner: an initial native Kotlin scanner and Compose screen are now present. The screen is reachable from the + menu and probes A, AAAA, NS, TXT, EDNS, randomized subdomains, NXDOMAIN and a cautious wildcard/synthetic-answer heuristic. UDP is used first and truncated replies retry over TCP. Results include latency and an explicit action to apply a healthy resolver to the existing DNS profile.
- DNS Scanner update: the native target scanner now reports resolver-by-resolver progress, exposes cancellation, and uses a process-scoped coordinator so navigation or Activity recreation does not discard an in-flight scan. Cancellation is checked between DNS probes; an in-flight blocking socket operation can still take up to its configured timeout to return. The scan remains user-triggered and is not yet backed by a foreground service. It does not yet provide a persistent background ScanService, full result history/export, DNS-over-HTTPS/TLS probing, or a definitive DPI verdict. A wildcard/synthetic response is only a warning signal, not proof of DPI.
- Tor: no operational Tor runtime has been integrated into the target app yet. The Tor section below is the implementation contract, not a claim of working Tor support. Native Tor, Snowflake Go bindings, Lyrebird, process lifecycle, SOCKS data-plane wiring and device-level tests remain required.

## Repository architecture findings

The target app currently uses the Xray-based service stack and a `hev-socks5-tunnel` TUN-to-SOCKS5 layer (`TProxyService`). It is not built on SlipNet's Hilt/Repository/ViewModel architecture. Copying SlipNet classes verbatim would therefore introduce unresolved dependencies and lifecycle assumptions.

The target already has a DNS changer feature under `ui/dns`, but this is a DNS routing/profile feature, not the SlipNet resolver scanner. It also has a generic camera-based `ScannerActivity`; that is not a DNS scanner.

## Tor capability scope

The SlipNet implementation is useful as a behavioral reference for:

- Tor process lifecycle and bootstrap progress.
- Built-in Snowflake and Snowflake AMP transports.
- Custom bridges: obfs4, meek_lite and WebTunnel.
- Direct and Smart bridge modes.
- A local SOCKS5 bridge between the VPN data plane and Tor.
- Persisted bridge configuration and profile-level bridge selection.
- Chaining Tor behind an upstream SOCKS5 layer where the selected pluggable transport supports it.

Reference files (SlipNet):

- `app/src/full/java/app/slipnet/tunnel/SnowflakeBridge.kt`
- `app/src/main/java/app/slipnet/tunnel/TorSocksBridge.kt`
- `app/src/main/java/app/slipnet/service/SlipNetVpnService.kt`
- `app/src/main/java/app/slipnet/data/repository/VpnRepositoryImpl.kt`
- `app/src/main/java/app/slipnet/presentation/profiles/EditProfileScreen.kt`
- `app/src/main/java/app/slipnet/presentation/profiles/EditProfileViewModel.kt`
- `app/src/main/java/app/slipnet/domain/model/ServerProfile.kt`
- `app/src/main/java/app/slipnet/domain/model/ChainValidation.kt`

### Required target-side work before Tor can be advertised as operational

1. Select and package a compatible Android Tor native runtime for every supported ABI.
2. Package and verify the Snowflake and lyrebird/pluggable-transport binaries and their licenses.
3. Add a dedicated Tor runtime controller with deterministic start/stop, process-death cleanup, port allocation and bootstrap-state reporting.
4. Add a local SOCKS5 adapter compatible with the existing TUN/SOCKS data plane; do not assume SlipNet's adapter can be dropped into the current service unchanged.
5. Add persisted bridge settings and a Tor configuration screen without altering existing profile types.
6. Add chain validation and explicit behavior for unsupported upstream/transport combinations.
7. Add instrumentation tests for startup, bootstrap timeout, bridge failure, reconnect, stop and traffic forwarding.

Important transport caveat: SlipNet documents that its built-in Snowflake client does not honor the optional upstream SOCKS5 parameter. Chaining must therefore be restricted to transports that actually support the upstream proxy; the UI must not imply otherwise.

## DNS Scanner & Resolver scope

The SlipNet scanner is a separate feature from its DNS tunnel resolver prober. The target implementation should preserve that distinction.

Reference files (SlipNet, commit `2ed00fd0072ad0b558e30ec6b5e8b30948f98894`):

- `presentation/scanner/DnsScannerViewModel.kt`
- `data/repository/ResolverScannerRepositoryImpl.kt`
- `domain/repository/ResolverScannerRepository.kt`
- `presentation/scanner/DnsScannerScreen.kt`
- `presentation/scanner/ScanResultsScreen.kt`
- `tunnel/DnsResolverProber.kt`
- `tunnel/DnsPoolScanner.kt`
- `domain/model/ResolverScanResult.kt`
- `service/ScanService.kt`
- `service/ScanStateHolder.kt`
- `presentation/navigation/NavGraph.kt`
- `di/RepositoryModule.kt`

Target-side feature requirements:

- Resolver catalogue with custom resolver import and validation.
- UDP/TCP probe modes, configurable timeout and bounded concurrency.
- Per-resolver latency, response code, transport support and failure reason.
- Compatibility probes for A/AAAA, NS, TXT, EDNS and NXDOMAIN behavior.
- Explicitly separated DPI/filtered-network diagnostics; never label a simple DNS timeout as proof of DPI.
- Cancellable/resumable scan state and safe background execution with Android foreground-service restrictions respected.
- Results filtering/sorting and an explicit action to save selected healthy resolvers.
- Applying a selected resolver to the existing DNS profile must be an explicit user action and must not silently replace current DNS settings.
- Tests for malformed responses, timeout, cancellation, IPv6, TCP fallback and concurrency limits.

## Licensing and provenance

Use SlipNet as a technical reference and reimplement against the target app's architecture unless a compatible license review explicitly permits code reuse. Preserve upstream notices for any reused code or binary. Verify the current SlipNet repository license and the individual native dependency licenses before packaging any upstream artifacts.

## Acceptance criteria

Tor is complete only when the native runtime, bridges, lifecycle, settings, traffic forwarding and end-to-end tests all work on-device.

DNS Scanner is complete only when real resolver probes, cancellation, results, persistence and explicit profile application are implemented and tested. A UI mock or a parser recognizing the `tor` outbound name is not sufficient evidence of a working Tor engine.
