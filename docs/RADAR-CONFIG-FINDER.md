# RADAR VPN — Config Finder

## Scope
This module is isolated under `com.aras.client.radar.config` and used by
`RadarConfigActivity`. It does not change the VPN service, profile persistence,
connection manager, subscription updater, routing, or other application features.

## Pipeline
1. Discover public feeds from a curated repository catalog.
2. Read each repository default branch and bounded recursive Git tree.
3. Fetch a small, size-limited set of likely subscription/config files.
4. Extract direct links or decode Base64 subscription payloads.
5. Parse supported URI schemes and validate host/port syntax, including VMess JSON and bracketed IPv6.
6. Deduplicate by normalized endpoint identity, independent of provider remark.
7. Resolve DNS and run two bounded TCP samples against public addresses only.
8. Enrich country information through the app's existing GeoIP cache.
9. Rank reachable endpoints by measured TCP latency and expose protocol/country filters.
10. Import only on explicit user action; the existing app core test/connection flow remains authoritative.

## Important distinction
TCP reachability is only a preliminary network signal. It does not prove TLS,
Reality, authentication, transport negotiation, or successful proxy traffic.
The UI labels it TCP-only and hands candidates to the existing RADAR VPN server
list for the app's real profile/core test.

## Reliability controls
- One failed repository does not abort other sources.
- Per-source fetch/parse counts and errors are displayed.
- Repository/file counts, response sizes and network timeouts are bounded.
- Candidate probing is concurrent but bounded.
- Private, loopback, link-local, multicast and selected reserved IP ranges are not probed.
- Duplicate entries are collapsed across sources.
- Probe results use an in-memory cache (success 3 minutes, failure 45 seconds).
- Stopping the scan cancels the coroutine pipeline.
- Candidate URIs are not persisted by this module.

## Source catalog
The catalog includes repositories previously selected for RADAR VPN:
miladtahanian/V2RayScrapeByCountry, SoliSpirit/v2ray-configs,
Delta-Kronecker/V2ray-Config, Danialsamadi/v2go, morpheusadam/v2ray-config,
MatinGhanbari/v2ray-configs, 0xRadikal/Free-v2ray-Configs,
drmikecrypto/PulseConfigs, and rtwo2/FastNodes. Existing sources from the first
implementation remain as fallbacks. Repository contents and public endpoints
can change, so source diagnostics are part of the feature.

## Validation
Unit tests cover direct links, Base64 subscriptions, VMess JSON, IPv6 endpoint
syntax, invalid ports/schemes, and duplicate identity. The dedicated Android
debug workflow runs unit tests and assembles a debug APK for this branch.
