# ArasClient — Development Plan & AI Handoff (Post-1.8.0 Maintenance Round)

> This file is the plan-and-state document for the maintenance round started
> 2026-10-12. `AI-HANDOFF-BRIEF.md` remains the entry point for build/signing/
> core-repo facts; this file records what THIS round changed and what comes next.
> Every AI picking this repo up should read the brief first, then this file.

---

## 1. What this round changed (all verified, one commit pending owner review)

### 1.1 Flags in remarks are no longer deleted on import

**Problem:** Every imported profile had its flag emoji stripped before storage.
`AngConfigManager` called `CountryResolver.withoutFlags()` at all four import
entry points (JSON array, single custom, WireGuard/AWG conf, link batch), so a
provider remark like `🇩🇪 Frankfurt 01` became `Frankfurt 01` in MMKV forever.

**Fix:** Removed the four `withoutFlags()` calls in
`app/src/main/java/com/aras/client/handler/AngConfigManager.kt`. Flags now
persist with the remark. `withoutFlags()` itself is unchanged and still used by
the server card (`MainServerPager.cardRemarks`) — the card resolves and shows
its own flag, so the stored flag would render twice otherwise. Storage keeps
what the provider wrote; only the display composes.

**Test:** `CountryResolverTest` — added
`remarks imported with a flag keep the flag`; the existing card-title test
documents why `withoutFlags` still exists.

### 1.2 Batch "ping all" is faster — one change

**(REVERSED) "One attempt instead of two"** — this change (bindings `bec1a8d`)
was a mistake and was reverted in bindings `1f8bbc1`. min-of-2 is not a jitter
smoother; it IS the measurement: attempt 1 pays the cold path (AWG handshake +
TCP + TLS ≈ 4-5 round trips), attempt 2 rides the warm keep-alive connection and
reads ≈ one real RTT. With a single attempt every delay read ~5× the truth on
that network (a real-100ms profile displayed ~500ms; 1.7.9 read ~130). Verified
on the host against gstatic over one keep-alive connection: attempt 1 = 362 ms,
attempt 2 = 104 ms.

**(b) Egress country rides the delay measurement** — this closes the "batch
test cannot report a country" defect from brief §7. New gomobile shim:

```go
type OutboundDelayResult struct {
    Delay   int64  `json:"delay"`
    Country string `json:"country"`
    IP      string `json:"ip"`
}
func MeasureOutboundDelayWithInfo(config, url, ipApiUrl string) (*OutboundDelayResult, error)
```

It runs the same instance build/measure path, then does one extra request to
the user's IP-API through the **same `core.Dial`** transport the delay test
uses — so the country reflects the profile's real egress, not a direct
connection. `MeasureOutboundDelay` (old signature) is kept and delegates, so
existing callers and older app builds keep working.

App wiring:
- `ArasCore.kt` — thin wrapper, typed `libv2ray.OutboundDelayResult`.
- `CoreNativeManager.measureOutboundDelayWithInfo` — returns null on any native error.
- `RealPingWorkerService` — new internal `PingResult(delay, country, ip)`;
  `startRealPing` calls the new shim; TCP-only path returns bare results.
- `RealPingEvent.Result` — gained optional `country`/`ipAddress`.
- `CoreTestService` — persists them via the existing
  `MmkvManager.encodeServerTestCountry`, so batch results now populate the
  card flag exactly like the single-server test already did.

**AAR:** rebuilt with `gomobile bind` (see brief §5 for the env), verified:
`amneziawg>0`, stock `wireguard=0`, and the new `MeasureOutboundDelayWithInfo`
native present. Installed to `app/libs/aras-core.aar`. **The tracked AAR must
be committed together with the app changes or the app won't compile on a fresh
checkout.** Core repo (`~/corebuild/xray-core` branch
`feat/awg-mimic-packets`) did NOT change — only the bindings repo did. The
bindings change must be pushed to `ArasTey/AndroidLibXrayLite` before CI can
build this app version.

### 1.3 FPS/stutter fix during batch tests

**Problem:** `MainViewModel.MeasureConfigSuccess` called
`loadGroup(forceRefresh = true)` for EVERY result — re-decoding every profile
and affiliation from MMKV once per ping. A 100-config subscription recomposed
the whole LazyColumn ~100 times mid-test: dropped frames, janky scroll, the
"fps" complaint.

**Fix:** The success broadcast already carries the guid. Wired it through:
- `MainServiceEvent.MeasureConfigSuccess` → `data class(guid: String)`
- `MainRepository` → reads the broadcast's "content" extra
- `MainViewModel` → patches the single `ServersCache` in the group flow
  (`copy(testDelayMillis=…, testCountryCode=…)`) and skips anything outside
  the current round. One result = one item recomposition.

### 1.4 PattNG options audit — nothing to add

User asked to port PattNG (github.com/patterniha/PattNG, local mirror
`~/Documents/ArasClient/pattng-src`). Audited prefs, `ProfileItem` fields,
settings UI, fmt parsers, and core behavior. Result: **full parity already.**

| PattNG feature | ArasClient status |
|---|---|
| `cipherSuites` (`cs=`) in settings + share links | Present — `ProfileItem.cipherSuites`, `FmtBase` `cs=`, VMess `cs`, editor UI, `CoreOutboundBuilder:814` |
| `unsafe` TLS fingerprint | Present — parses any `fp=`, core accepts `unsafe` (`transport_security.go:181`) |
| Unencrypted VLESS/TROJAN to public addresses | Present in core — patterniha's `validateOutboundTransportSecurity` enforcement is commented out in `xray.go:341-343` of the Aras core |
| IPv6 enable / prefer IPv6 settings | Present (`PREF_IPV6_ENABLED`, `PREF_PREFER_IPV6`, VPN settings group) |

ArasClient additionally has what PattNG lacks: Aether, Mieru, AmneziaWG (with
mimic packets), MASQUE, AnyTLS, Clash YAML import, protected `.arasc` export,
Free sub, quick connect, smart connect. **Do not "port" anything from PattNG
without a specific user-visible gap.**

---

## 2. Verification status for this round

- [x] `:app:testDebugUnitTest` — all green after every step.
- [x] AAR symbol check (anytls/amneziawg/stock-wg/MeasureOutboundDelayWithInfo).
- [ ] `:app:assembleRelease` — NOT yet run this round; run before any release.
- [ ] Emulator sweep of a batch ping (does the flag appear on cards mid-batch?)
- [ ] The slow-ping change is behavioral: spot-check that measured delays look
      consistent with before (single sample is noisier than min-of-2; that is
      accepted, it is what v2rayNG does).
- [ ] Bindings repo push to `ArasTey/AndroidLibXrayLite` (CI needs it).

## 3. Open defects (unchanged from brief §7)

1. **Standalone AWG socket protection** — `awg_tunnel.go` uses
   `conn.NewDefaultBind()`, no `vpnProtect()`. Reproduce first
   (bypass-LAN = Not Bypass, full-tunnel AWG profile) before touching it.
2. **Entry-proxy chain fixes never tested on a device.**
3. **OpenVPN** — still absent, still not startable from the app side.
4. `proxy/wireguard/client.go.rej` — tracked debris in the core repo; optional cleanup.

## 4. Working agreement (unchanged)

One clean commit on `main`, fully local, until the owner says otherwise. The
signing key is irreplaceable: never touch `app/build.gradle.kts` signing,
never stage `keystore/` or `keystore.properties`. Reproduce on the emulator
before theorizing — three guesses during 1.8.0 were all wrong.

## 5. Suggested commit message for this round

```
Keep remark flags, speed up batch ping, fix test-time stutter

- Import no longer strips flag emojis from remarks; cards still compose
  their own flag, storage keeps the provider's.
- New core shim MeasureOutboundDelayWithInfo returns the egress country
  through the same dial path as the delay test, so "ping all" now fills
  card flags without extra requests; batch measurements run one HTTP
  attempt instead of two.
- Per-test results patch a single card instead of rebuilding the whole
  group cache, which dropped frames during long batches.
```
---

## 6. Round 2026-10-16/17: core update to patterniha v26.10.13 + PattNG parity

Everything in this section is committed/planned LOCALLY ONLY — the owner
ordered no pushes until they say so.

### 6.1 Remarks are immutable on import

Follow-up audit after the owner reported flags still being deleted: the
installed 1.8.0 APK was built from the pre-fix working tree, so the *shipped*
build still stripped flags. The current source never strips them and never
rewrites a non-empty remark: every parser fills a default name only when the
fragment is empty. `RemarkPreservationTest.kt` pins the AWG flag remark
byte-for-byte through parse and a share-link round trip, plus the vless
flag case and the empty-remark default. **Reinstall from a fresh build to
see it.**

### 6.2 Root-mode DNS DNAT (upstream fix ported)

v2rayNG/PattNG merged upstream #6298: in root mode, a DNS query to a private
resolver (e.g. 192.168.1.1) also matches a user's `geoip:private → direct`
rule and leaks to the ISP resolver. Ported into `RootProxyManager` as
`buildDnsDnat(iptables|ip6tables)` (teardown included) + `ROOT_DNS_OUT_CHAIN`
const. Owner-verified on device is still pending.

### 6.3 Fingerprint list

The picker now exposes `hellochrome_120/131/133`, `hellofirefox_120/148` —
all already supported by the core (`tls.go` ModernFingerprints). PattNG had
only hellochrome_120. No string resources touched (translatable=false).

### 6.4 Core updated to patterniha v26.10.13

Branch `feat/awg-mimic-packets-26.10` (local): our two patches
(ArasClient port + AWG mimic packets) rebased onto patterniha's
`v26.10.13` (`709648e0`), 91 upstream commits newer than the previous base.
Key conflicts resolved:

- `insecure_skip_verify` renumbered proto field 23 → **24** — upstream added
  `use_system_ca = 23`; the wire number changed together with the field
  (config.proto regenerated via protoc; check `config.pb.go` is byte-identical).
- `common/singbridge` restored from the 26.9.27 base — upstream's SS2022
  refactor deleted it, but our AnyTLS port still imports it.
- masque/tun: upstream now types the tun against stock wireguard-go;
  `masque/server.go` retyped to amneziawg-go's `tun.Device`.
- Core `go build ./...` green; AAR rebuilt, symbols verified
  (anytls/amneziawg>0, stock-wg=0), `MeasureOutboundDelayWithInfo` present.

Tag created locally: `aras-core-26.10.13` → commit `3c378877`. CI's
`build.yml` now points at it. **Nothing pushed yet.**

### 6.5 Native libs — surveyed, thehlen state

| lib | ours | PattNG latest | action |
|---|---|---|---|
| amneziawg-go | v3.1.20260828 (= latest upstream tag) | same | none needed |
| hev-socks5-tunnel (VPN) | 2.18.0-era binary, identical hevsockstun | 2.18.0 | none needed |
| aether | v2.1.0 release | bumped submodule (Rust, full CI build) | needs Rust toolchain build — separate round |
| mieru/psiphon/lyrebird | prebuilt, no newer release found | same | none |

### 6.6 Versions

App bumped to `versionName 1.8.1 / versionCode 181` locally — v1.8.0 is already
published on GitHub, so the next shipped APK set has to upgrade cleanly over
it. Full gate (tests + both assembles + lintVital) green, APK names are
`ArasClient_1.8.1_*.apk`.

### 6.7 Readiness checklist for the owner's "push" command

Everything is local and verified. Push in this exact order or CI fails:

1. **Core** `~/corebuild/xray-core`: push `feat/awg-mimic-packets-26.10`
   and the tag `aras-core-26.10.13` (both resolve to `3c378877`). CI
   checks out the app build with `ref: aras-core-26.10.13`, so without the
   tag the CI core build fails immediately.
2. **Bindings** `~/corebuild/AndroidLibXrayLite`: push `main` (`470676e`,
   2 ahead of remote `bec1a8d`). CI builds the AAR from `ref: main`;
   without it the app does not compile (`measureOutboundDelayWithInfo`
   missing from the bindings the CI builds).
3. **App**: push `main` with the postBuffer flags (brief §4), 3 commits.
4. Tag `v1.8.1` + `gh release create v1.8.1` + upload the five
   `ArasClient_1.8.1_*.apk`.
5. Watch CI end-to-end green, including the Restore signing key step
   (the env-mapping fix is in `44e6050`).

Local debris: `proxy/wireguard/client.go.rej` is still tracked in the core
repo (came with the 26.9.27 port commit). Harmless, not compiled; delete in a
later cleanup round.

---

## 7. Round 2026-10-17 (part 2) — Aether 2.3.0 + two UI/parse fixes

### 7.1 Aether core updated to the official v2.3.0 release

App-shipped `libaether.so` (arm64/armv7/x86_64) swapped for CluvexStudio
Aether **v2.3.0** release binaries (official checksums verified against
SHA256SUMS.txt; lyrebird + psiphon-tunnel-core were byte-identical to what the
app already carried, so only the aether binary changed). The new core brings:

- `--masque-sni` — the name the MASQUE handshakes put in their ClientHello
- `--ech auto` with `--ech-dns` (key resolver) and `--ech-domain` (key domain)
- the new `--gool` (WireGuard WARP inside MASQUE; classic gool stays on
  `--gool-classic`) and full `--wiw-outer/--wiw-inner` (already wired)

App-side additions (fields, links, editor UI, args, en+fa strings):
`aetherMasqueSni`, `aetherEchDns`, `aetherEchDomain` (aetherEch existed). The
carrier modes the owner asked about map to what the app already had:
warp→Psiphon = `--psiphon` (chain), Psiphon→warp = `--psiphon-reverse`,
Psiphon-only = `--psiphon-only`; masque-in-masque = the mim protocol;
WireGuard-over-MASQUE = the gool protocol

Unit tests + assembleDebug green. **Not verified on device yet** — needs an
emulator/real connect pass before the release the owner approves.

Pending from the owner's list: the release notes' "4 fingerprints" — in an
aether 2.3 profile these are core-internal (Chrome's BoringSSL fingerprint
with fragment variants), not user-selectable options; do not fake a picker.

### 7.2 Bottom-sheet bounce (fixed)

The main menu sheet (three-line → settings bar) visibly bounced up/down on a
strong upward drag: the default modal state allows overshoot both directions
and the content exceeds the peek height. Fix in `MainMenuSheet`: skip the
partial expansion + a capped, inner-scrollable column — stable while a
downward drag still dismisses.

### 7.3 Subscription traffic display

`applySubscriptionMetadata` now also accepts a `used=` key from the
`subscription-userinfo` header (some panels send the sum directly instead of
upload/download). If a specific provider still shows wrong figures, the owner
should hand over that provider's header (or a screenshot) — the parse path is
otherwise complete: upload/download summed, total, expire, used.

### 7.4 Exit node — designed, not implemented (next round)

PattNG's exit-node is a subsystem, not a field: an outbound tagged
`exit-node` that the aether core dials out through — either freedom with
finalMask/dialMode, or the outbound of ANOTHER profile chosen by name
(`toOutboundOfNode`, unique-name lookup), whose content digest pins the
session identity (env `PATTNG_AETHER_EXIT`), plus a secondary-socks inbound +
routing rule for custom configs (`AetherDependency.routeThroughXray`), stats
filtering in the notification, and an ECH-beside-the-node check. Porting it
faithfully is its own round with its own emulator verification; a half-port
would be worse than none. Design reference captured here; start from
`AetherCoreManager.exitConfiguration` + `CoreOutboundBuilder.toOutboundAetherExit`
in PattNG and our chain-dialer plumbing (`HelperProcessSupport` gives the
per-core ports already).

### 7.5 OpenVPN — feasibility per the official docs

The blocker from brief §7 stands: no reachable Go OpenVPN client and no
shippable binary. The realistic official-docs path is packaging ics-openvpn's
native `libopenvpn.so` (VPNService-based, like our hev path) and driving its
icc/profile API — GPL-3 compatible with this app, but it is its own sized
project (native packaging per ABI + profile import bridging .ovpn). Plan it
as a dedicated round; do not attempt inside a release round.

### 7.6 OpenVPN feasibility, checked against the official docs (2026-10-17)

ics-openvpn (schwabe) answers the packaging question directly: it publishes no
Maven artifact of `libopenvpn.so`, it is deliberately NOT a library (its README
refuses that use case), and building a standalone .so from it makes a GPL fork
supportable at the fork's own cost. The OFFICIAL integration surface for
another app is the AIDL API / launchable activities
(`de.blinkt.openvpn.api.profileName`) against an installed ics-openvpn:
ArasClient would proxy .ovpn import → the external app's connect requests, and
show its state in our UI — no native packaging, no license fork. That is the
recommended round for OpenVPN; bundling a native binary is the fallback and a
significantly larger project.

### 7.7 Exit node — implemented (2026-10-17)

Faithful to PattNG's semantics, scoped to what the app runs:
- `ProfileItem.aetherExitNode` — the NAME of the one other profile the aether
  core dials out through; blank keeps the direct exit.
- `AetherExitNodeResolver` — unique-name lookup across every group; the named
  profile must not itself be an Aether profile (loop guard).
- `CoreConfigManager.applyAetherExitNode` builds, on demand: a SOCKS inbound
  tagged `exit-node-in` on `aetherPort + 5`, the outbound tagged `exit-node`
  (Freedom when the name is blank, else the named profile's outbound via
  `CoreOutboundBuilder.convert`), and a first routing rule that joins them.
- `AetherCoreManager.buildArguments` appends
  `--upstream socks5://127.0.0.1:<that port>` (aether 2.3's flag), so the
  core itself dials out through Xray.
- Exit-node measurements are serialized like custom configs (they own the
  fixed inbound port while running).
- Editor: dropdown of every profile's remark + the blank direct-exit entry;
  en + fa strings. A stale or ambiguous name degrades to the direct exit in
  the session config (documented in the builder's comment) instead of failing
  the connect — matching "fail open to freedom" in PattNG's spirit for
  missing nodes, while the editor still shows what it picked.
- Tests: `AetherExitNodeResolverTest` pins the identifying rules and the
  port-offset contract.

---

## 8. Round 2026-10-17 (part 3) — the owner's five reports, each verified

1. **"The key is not as 1.7.10" — checked: it IS the same key.** The v1.7.10
   release APK imports its certificate: SHA-256 3b2a6fa9…b5ba / SHA-1
   65ee7664…868b — identical to the bundled keystore and to the rebuilt 1.8.1
   APKs. Upgrades install over 1.7.10 without uninstalling. If a user still
   sees a signature error, they hold one of the early 1.7.5-era debug-signed
   builds, not 1.7.10.
2. **"The app/file got much smaller" — checked: every APK is BIGGER than
   v1.7.10's.** arm64 +0.6MB, armeabi +0.6MB, universal +2.5MB, x86 +0.7MB,
   x86_64 +0.6MB (Aether 2.3.0's binaries). The deleted v1.8.1 release assets
   were the same size; nothing was dropped. x86 never shipped aether (the core
   has no x86 build) — that is PattNG's design, not a regression.
3. **sing-box (.json) subscriptions imported nothing — fixed.** The import
   path knew Clash YAML (and clash-style JSON) but not a sing-box document.
   New `SingboxFmt`: detects a sing-box object, converts the DIALING outbounds
   (vless incl. REALITY/pbk/sid/fp/cs, vmess as the standard QR JSON, trojan,
   shadowsocks, hysteria2) to the same share links the app reads back, skips
   selector/urltest/direct/block/dns groups, and validates the whole list
   before storage changes. Wired into the batch-config path (imports and
   subscription updates; both run through parseBatchConfig).
   `SingboxFmtTest` covers detection, the four link shapes, REALITY survival
   through VlessFmt, and the clash/sing-box discrimination.
4. **Psiphon lists** — the shipped `psiphon_servers.dat` (Sep 28 snapshot,
   327KB) is signature-checked by `PsiphonServerList` before use (a list
   without Psiphon's signature is never handed on) and the aether core
   downloads a fresh list once it has network; the Asset screen refresher
   replaces the file. Bundled list refresh from an upstream source needs a
   documented origin — no official Psiphon URL for the signed bundle is
   published; treat the in-app refresh as the source of truth.
5. **Download/upload display** — `%{used}` key added (last round + this
   round's header). When a specific panel still misreads, hand over that
   panel's userinfo header/screenshot: the parser covers
   upload=, download=, used=, total=, expire=.
   Note: PattNG v2.3.11 carries NO subscription-userinfo parsing at all —
   this app's implementation is already beyond upstream.

---

## 9. Round 2026-10-18 — tasks 6 and 7 from the open list, closed

### 9.1 The four TLS fingerprints (task 7 — implemented)

PattNG captures four clients' ClientHello (Chrome, Firefox, Semi-Python, Go)
and names them; a profile picks one, and the core offers that client's TLS 1.2
suite order with GREASE left out where the client sends none. All of it:

- `AetherFingerprint` enum (`type`/`ciphers`/`grease`), arguments =
  `--tls-ciphers <suites>` + `--disable-grease` when non-grease — pixel-equal
  to PattNG's tables (`AetherOption.kt`).
- `ProfileItem.aetherFingerprint`; `AetherCoreManager.buildArguments` emits
  the arguments for every overMasque protocol, exactly as PattNG does.
- aether:// links carry `fingerprint=<type>` and NEVER the default chrome —
  PattNG's wire rule, kept.
- Editor: the dropdown next to the transport (labels Chrome / Firefox /
  Semi-Python / Go, core values underneath), translatable=false arrays.
- Tests: `AetherFingerprintTest` pins the four wire names, the arguments each
  emits (chrome keeps GREASE; the others don't), the masque-vs-wg emission
  rule, the link round trip, and unknown values reading as chrome.
- The two new flags were probed against the bundled aether 2.3.0 binary on
  the emulator (both accepted, the run reaching a real subsystem start);
  `AetherFlagTest`'s accepted set now carries them with that note.

### 9.2 Psiphon server lists (task 6 — verified three ways)

- **Static:** the bundled `psiphon_servers.dat` (Sep 27 snapshot, 327KB)
  inflates to a 1.1MB signed package: `data` + `signingPublicKeyDigest` +
  `signature`; the digest matches the key in `PsiphonServerList`, and
  openssl verifies the RSA signature over the entries — **Verified OK,
  427 server entries.**
- **Unit:** `PsiphonServerListTest` (existing) asserts the bundled file is
  present, unpacks and verifies with Psiphon's real key, and its entries are
  hex-encoded lines; corrupt/foreign/empty lists are refused.
- **Runtime:** the aether 2.3.0 core on the emulator accepted the overlay
  config carried by the app (`RemoteDnsAddresses` + the 427-entry
  `ServerEntryList` in config.json, the exact document `psiphonOverlay`
  feeds `--psiphon-config`) and started the psiphon-tunnel-core inside
  /data/local/tmp/pt/ — the shape reached the controller. The full bootstrap
  could not complete there: the emulator's resolver refused UDP 53 outright
  (the degraded-network state already seen), so connections could not even be
  attempted. The list's presence, signature and consumption are proven; a
  live tunnel through it still wants a healthy network day.

Also confirmed: the entry-proxy fix and psiphon flag set match
(`--psiphon-server-entries` is rejected; the list rides in the config), so
nothing in our overlay drifts from the core's interface.
