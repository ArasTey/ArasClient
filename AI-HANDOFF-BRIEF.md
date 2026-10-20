# ArasClient — AI Handoff Brief

Send this whole file to the next AI. It is the entry point; `HANDBOOK.md` is the
deep reference. Everything below was verified, not remembered.

---

## 1. Identity, repos, state

| | |
|---|---|
| Owner | **ArasTey** — Persian-speaking. Reply in Persian if they write in Persian. |
| Git identity | `ArasTey <308009703+ArasTey@users.noreply.github.com>` — set repo-locally on both repos already. Never use a placeholder identity. |
| App repo | `/Users/admin/Documents/ArasClient/ArasClient` — remote `ArasTey/ArasClient`, branch `main` |
| Core repo | `~/corebuild/xray-core` — remote `ArasTey/xray-core`, branch **`feat/awg-mimic-packets-26.10`** (patterniha v26.10.13 + the app's patches). The tag `aras-core-26.10.13` is what CI builds the AAR from. |
| Bindings | `~/corebuild/AndroidLibXrayLite` — remote `ArasTey/AndroidLibXrayLite` |
| Builds | the release APKs of the last round, built by CI for `v1.8.1` |
| Current commit | run `git rev-parse --short HEAD` and `git status --short --branch` — a hash written *inside* the commit it describes cannot stay correct, because amending changes it. |
| Version | `versionName 1.8.1`, `versionCode 181` (`app/build.gradle.kts`) |
| Tests | 254 `@Test` across 33 files, 0 failures |


The owner prefers changes kept as **one clean commit** on `main`, fully local,
until they say otherwise. Do not push, tag, or create a GitHub release without
being asked — that is the irreversible step.

---

## 2. Build

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20/libexec/openjdk.jdk/Contents/Home
cd /Users/admin/Documents/ArasClient/ArasClient

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintVitalRelease --no-daemon
```

That is the whole gate: tests, both assembles, and fatal lint — 5–18 minutes.
`testDebugUnitTest` is the gate, not a formality: the house rule is that every
bug gets a test written to look for it.

APKs land in `app/build/outputs/apk/release/`, five per release (ABI splits).
**Each split is a complete standalone app** — there is no base to install first.

---

## 3. Release signing — read before building a release

Releases are signed with a dedicated key that exists **only on this machine**:

```
CN=ArasClient, OU=Release, O=ArasClient, L=Tehran, C=IR
SHA-1:   65:EE:76:64:C8:0E:5B:6B:2C:D4:6B:F7:FC:20:26:A5:65:4D:86:8B
SHA-256: 3b2a6fa9c328d002ff737976a7b384423ad844346927d208052bb49ed920b5ba
```

`keystore/arasclient-release.jks` and `keystore.properties` are **gitignored**.
A missing `keystore.properties` is a build failure, not a silent fallback — that
is deliberate.

**Do not touch the signing config.** Every release up to and including 1.7.5 was
signed with the Android Studio *debug* key, because the committed
`keystore.properties` pointed at `keystore/debug.keystore`. That caused the
"Package invalid" reports and Play Protect warnings. Fixed in `4d84df9`.

If the key is ever lost, **no update can ever be shipped to an existing
install** — everyone would have to uninstall first. Treat it as irreplaceable.

Verify before shipping:

```bash
AS=~/Library/Android/sdk/build-tools/36.0.0/apksigner
cd app/build/outputs/apk/release
for f in *.apk; do
  echo "$("$AS" verify --print-certs "$f" 2>/dev/null | grep 'SHA-1 digest' | head -1)  $f"
done
```

All five must show the same SHA-1 above, and `apksigner verify --verbose` must
say **`Number of signers: 1`** for each. v2-only signing is correct and expected
(`minSdk 24`) — do not "fix" the missing v1 signature.

CI gets the same key from four repository secrets: `KEYSTORE_B64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` (`arasclient`), `KEY_PASSWORD`.

---

## 4. Publishing (only when asked)

```bash
git -c http.postBuffer=524288000 -c http.lowSpeedLimit=0 -c http.lowSpeedTime=999999 \
    push origin main                      # ~160 MB of native libs; plain push times out (HTTP 408)

git tag -a v1.8.0 -m "v1.8.0" && git push origin v1.8.0
gh release create v1.8.0 --title "ArasClient v1.8.0" --notes-file /tmp/notes.md --target "$(git rev-parse HEAD)"
gh release upload v1.8.0 --repo ArasTey/ArasClient app/build/outputs/apk/release/ArasClient_*.apk --clobber
```

**The core must be published first or the release ships the old core.** That
blocker is now **cleared**: `feat/awg-mimic-packets` and the tag
`aras-core-26.9.28` are both pushed to `aras`, and `.github/workflows/build.yml`
line 27 is pinned to `aras-core-26.9.28`. Do not re-pin it to
`aras-core-26.9.27`. CI builds the core from that ref and overwrites
`app/libs/aras-core.aar`, so the old ref ships a core **without** the I1–I5
mimic packets — the headline feature of 1.8.0. The tracked `.aar` on disk does
contain them, so local builds are fine; only a CI rebuild from the wrong ref
loses them.

---

## 5. Rebuilding the native core

Only needed when the Go core changes.

```bash
cd ~/corebuild/AndroidLibXrayLite
go mod edit -replace=github.com/xtls/xray-core=../xray-core
go mod tidy

export ANDROID_HOME=/Users/admin/Library/Android/sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.3.13750724
export GOPROXY=https://goproxy.cn,direct        # proxy.golang.org 403s from here
export PATH="$HOME/go/bin:$PATH"                  # gomobile lives here
gomobile bind -androidapi 24 -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0' \
  -o /tmp/aras-core.aar ./                       # → app/libs/aras-core.aar
```

Protoc (only when a `.proto` changes):

```bash
go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.12
cd ~/corebuild/xray-core
PATH="$HOME/go/bin:$PATH" protoc --proto_path=. --go_out=/tmp/wgproto \
  --go_opt=paths=source_relative proxy/wireguard/config.proto
```

Then bring the generated file in **with Write/Edit**. The security hook blocks
Bash from writing source files, so generating straight into the repo is refused.
Verify the result is byte-identical with `diff` against the temp output.

CI asserts on the built core; run the same check locally:

```bash
cd /tmp && rm -rf ck && mkdir ck && cd ck
unzip -o -q <aar> "jni/arm64-v8a/libgojni.so"
strings jni/arm64-v8a/libgojni.so | grep -ci anytls     # must be > 0
strings jni/arm64-v8a/libgojni.so | grep -ci amneziawg  # must be > 0
strings jni/arm64-v8a/libgojni.so | grep -c 'golang.zx2c4.com/wireguard'   # must be 0
```

A non-zero wireguard-go count means the amneziawg import did not take effect.

---

## 6. What 1.8.0 fixed — do not regress these

Five defects, each with a test. They are the reason this build exists.

1. **The server-card flag was only right after a ping.** The card resolved its
   country from `GeoIPResolver`, but nothing recomposed it when the cache
   filled — the lookup finishes after the card composes and `cached()` is not an
   observable read. `GeoIPResolver.version` is now a `StateFlow` the card keys
   on. (`MainServerPager.kt`, `GeoIPResolver.kt`)

2. **An entry proxy that is Aether/Mieru never started its daemon**, so the whole
   subscription reported `-1`. Both managers hold one global slot and were only
   started when the *selected* profile was itself one of those types.
   `HelperProcessSupport` resolves the chain and refcounts the daemons. The
   latency test also no longer probes a chained profile's own server directly.
   (`HelperProcessSupport.kt`, `CoreServiceManager.kt`, `RealPingWorkerService.kt`)

3. **The TUN descriptor had two readers.** hev-socks5-tunnel and the standalone
   awg-go tunnel were both handed it; tun2socks won the race and drained packets
   into a SOCKS proxy that no core serves in AmneziaWG mode.
   `shouldRunTun2Socks()` skipped it for AMNEZIAWG in 1.8.0 — **the standalone
   tunnel is gone entirely since 2026-10-20**: every protocol, AmneziaWG
   included, is dialled by the core, so the TUN has one reader again.
   (`CoreVpnService.kt`, `CoreServiceManager.kt`, `CoreOutboundBuilder.kt`)

4. **AmneziaWG junk params were read from `[Peer]`, but AmneziaVPN writes them
   under `[Interface]`** — they are device-level, and the UAPI parser is in peer
   context after the first `public_key=`. The parser found nothing,
   `applyDefaultJunkParams` substituted stock values, and the server dropped
   every packet. It now reads `[Interface]` first, `[Peer]` as fallback.

5. **`.conf` files were imported as plain WireGuard** whenever they did not spell
   the key exactly `Jc` — the test was `contains("Jc", ignoreCase = false)`.
   `AmneziawgFmt.looksLikeAmneziaConf()` now decides on the key set,
   case-insensitively, including S3/S4 and I1–I5.

New feature: **mimic packets (I1–I5)** — decoys sent ahead of each handshake so
the first bytes on the wire belong to another protocol. `MimicPackets.kt`,
`AwgConfigBuilder`, `CoreOutboundBuilder`, plus core proto fields
`PeerConfig.mimic_packet_1..5`. A spec the core cannot parse fails the whole
device string, so `MimicPackets.validate` gates what is written.

### The trap that hides all of this

**Release builds default the log level to `"warning"`, so every `LogUtil.i` is
suppressed** — including the UAPI dump. That is why these bugs stayed invisible
so long. To diagnose anything, build `:app:assembleDebug` (defaults to
`"info"`) or set the log level to info in Settings. When a user reports a VPN
problem, ask for those lines *with the log level at info*.

### Config fixes do not repair stored profiles

These fixes change **parsing**. A profile already imported with a broken build
keeps the wrong values in MMKV, and nothing can tell them apart from correct
ones. **Users must delete the profile and re-import the file.** Say this
explicitly whenever a config fix ships.

---

## 7. Still open

- **OpenVPN is not implemented and cannot be added the way Aether/Mieru were.**
  No OpenVPN binary exists to ship, and no reachable Go OpenVPN client to link
  (`go-tun2socks`'s companion, `hectap/go-openvpn`, `KaringX/go-openvpn` all 404
  from `goproxy.cn`); patterniha v26.9.27 has no OpenVPN across all 42 refs of
  its remotes. Do not start on the app side.
- **The standalone AWG socket is gone** (2026-10-20): AmneziaWG is dialled by
  the core like every other protocol, so the question of a socket that owns the
  TUN and has no `protect()` no longer exists. The TUN has one reader.
- **A batch latency test cannot report an egress country.** *Solved in 1.8.1*:
  the gomobile shim grew `MeasureOutboundDelayWithInfo`, which fetches the
  egress country and IP through the same `core.Dial` path as the delay test,
  and the card is patched with it.
- **The entry-proxy chain fixes were never tested on a device** — they need an
  Aether or Mieru profile to exercise.
- **Hysteria v1 has no outbound builder.** `EConfigType.HYSTERIA` exists and
  a `hysteria://` link imports into it, but `CoreOutboundBuilder.convert` does
  not map it, so such a profile builds no outbound. The core's `hysteria`
  proxy is a different protocol from hysteria2, so this needs a decision
  before it is touched.
  Harmless, not compiled. Deleting it is optional cleanup.

---

## 8. Traps that will cost you time

- **`keystore/` must never be staged.**
  `git diff --cached --name-only | grep -iE "keystore|\.jks" && echo STOP`
- **The security hook refuses Bash writes to source files.** Use Write/Edit; for
  generated code, generate to a temp path and bring it in with Write.
- **amneziawg-go must be imported directly**
  (`github.com/amnezia-vpn/amneziawg-go/v3/...`). Never
  `replace golang.zx2c4.com/wireguard => amneziawg-go` — that loads one module
  under two paths and fails with `used for two different module paths`.
- **Go ignores `replace` in a dependency's go.mod**, so you cannot point the core
  at a local amneziawg fork. Anything new must resolve from the real proxy.
- **amneziawg has no `address` UAPI key.** Sending one fails the tunnel with
  `invalid UAPI device key: address`. The device is handed an already-configured
  VpnService TUN and cannot set an address. The accepted key set is pinned by a
  test in `AmneziaConfSectionTest`.
- **Persian locale must stay complete.** Adding a string means adding it to
  `values/strings.xml` *and* `values-fa/strings.xml` in the same change; 134
  strings once went missing and half the UI rendered in English.
- **`ProfileItem` enums persist by `name()`** — renaming a constant invalidates
  saved profiles. `ArasShareItem` ints are a separate v2rayN wire format; 7 and 9
  are swapped there and 1–12 must never be renumbered.
- **The top bar wordmark must stay LTR.** It is two `Text`s in a `Row` and will
  otherwise render "ClientAras" in Persian; it is pinned with
  `CompositionLocalProvider`.
- **The connect button acts on whatever profile is selected**, not the one you
  were looking at. Select the card first.

---

## 9. Layout

```
app/src/main/java/com/aras/client/
  core/            CoreConfigManager (outbound assembly), CoreOutboundBuilder
                   (profile → outbound), CoreServiceManager (start/stop/reload),
                   CoreConfigContextBuilder (chain resolution), HelperProcessSupport,
                   core/aether/, core/mieru/
  service/         CoreVpnService (TUN lifecycle), RealPingWorkerService
  fmt/             AmneziawgFmt, WireguardFmt, AetherFmt, ArasFmt — link parsers
  ui/              Compose screens
  handler/         AngConfigManager (import/share), MmkvManager, SettingsManager
  dto/entities/    ProfileItem, SubscriptionItem, ServersCache
app/libs/aras-core.aar        built from the core, tracked in git
app/libs/<abi>/*.so           aether, mieru, psiphon, lyrebird, hev tun2socks
.github/workflows/build.yml   builds the core, then the app; asserts on the core
```

The three files that matter most: `.github/workflows/build.yml`,
`app/build.gradle.kts`, and `core/CoreOutboundBuilder.kt` (every config bug of
the last session was there or in its callers).

---

## 10. Working agreement

Ask questions freely — the owner is available and prefers being asked over being
guessed at. Three guesses were made during 1.8.0 and all three were wrong; the
right move is always to reproduce on the emulator, not to reason about the code.

To reproduce properly:

```bash
adb push <config> /sdcard/Download/<name>.txt   # .conf files are hidden by the
                                                # picker's filters; .txt shows up
adb -s emulator-5554 shell appops set com.aras.client ACTIVATE_VPN allow
adb -s emulator-5554 logcat -c
# then tap connect in the app, and read:
adb logcat -d | grep -E "AWG UAPI|jc=|persistent_keepalive|tunnel up|AwgTunnel|invalid UAPI"
```

Confirm traffic actually crossed the tunnel rather than the emulator's own NAT:

```bash
adb -s emulator-5554 shell ping -c 3 -W 4 1.1.1.1
adb -s emulator-5554 shell cat /proc/net/dev | grep tun
```

Both AmneziaWG configs the owner has were verified working end-to-end this way:
tunnel up, `jc/jmin/jmax/s1/s2` matching the file, `i1`/`i2` emitted for the
mimic one, and 3/3 pings through the tun interface on three separate on/off
cycles.