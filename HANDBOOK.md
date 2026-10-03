# ArasClient — maintenance and release guide

Written after the v1.7.5 → v1.7.10 work. It records what changed, what is still open,
and the exact order to work in when you touch this project again.

Everything below was verified against the repository, not remembered. Where something is
uncertain it says so.

---

## 1. What this project is

A VPN client for Iran. It forks v2rayNG/PattNG. All proxying is delegated to a native
Xray-core compiled to an Android AAR; the app's own job is to build the JSON configuration,
hand it to the core, and run a few helper processes beside it.

| Piece | Where it lives |
|---|---|
| App | `/Users/admin/Documents/ArasClient/ArasClient` — `github.com/ArasTey/ArasClient` |
| Core fork | `~/corebuild/xray-core` — `github.com/ArasTey/xray-core` |
| Core bindings | `~/corebuild/AndroidLibXrayLite` — `github.com/ArasTey/AndroidLibXrayLite` |
| Aether reference | `github.com/CluvexStudio/Aether` (real releases; `patterniha/Aether` is a fork) |
| Mieru | `github.com/enfein/mieru` |

**Current state:** v1.7.10, versionCode 151, `main` at `4d84df9`.
Core pinned by tag `aras-core-26.9.27` (`d6451464`).

---

## 2. Build and test commands

Everything from the repo root. The JDK path is Homebrew's; if yours differs, substitute it.

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20/libexec/openjdk.jdk/Contents/Home
```

```bash
# everything CI runs
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintVitalRelease --no-daemon

# just the tests, while iterating
./gradlew :app:testDebugUnitTest --no-daemon

# release APKs only
./gradlew :app:assembleRelease --no-daemon
```

APKs land in `app/build/outputs/apk/release/`. Because the build uses ABI splits you get five
per release, one per architecture, **each a complete standalone APK** — there is no separate
base to install alongside them.

The test count is **178** across 27 files. `testDebugUnitTest` is the one that matters; it is
the gate, not a formality.

---

## 3. Release signing — read this before building a release

Releases are signed with a dedicated keystore:

```
CN=ArasClient, OU=Release, O=ArasClient, L=Tehran, C=IR
SHA-1: 65:EE:76:64:C8:0E:5B:6B:2C:D4:6B:F7:FC:20:26:A5:65:4D:86:8B
```

**Why this was changed.** Every release up to and including v1.7.10 shipped with the Android
Studio *debug* keystore, because the committed `keystore.properties` pointed at
`keystore/debug.keystore`. That caused the "Package invalid" reports: a phone that already had
ArasClient installed from a differently-signed build refuses the new one. Play Protect also
flags debug-signed packages, and if anyone regenerated a debug keystore the signature would
change and updates would break for every user at once.

**Where the key lives now.** `keystore/arasclient-release.jks` and `keystore.properties` are
**untracked** — see `.gitignore`. They exist only on this machine. CI gets the same key from
repository secrets, so a release and a local build produce byte-identical signatures.

The four secrets on `ArasTey/ArasClient`:

| Secret | Value |
|---|---|
| `KEYSTORE_B64` | base64 of the `.jks`, no line breaks |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `arasclient` |
| `KEY_PASSWORD` | key password |

**If the key is ever lost, you can never ship an update to an existing install.** Anyone would
have to uninstall first. Treat it as irreplaceable and back it up somewhere safe. It is
deliberately *not* in the repository, because anyone who can read it can sign an update the
phone will accept.

A missing `keystore.properties` is now a **build failure**, not a silent fallback to the debug
key. If you see that error, copy `keystore-example.properties` to `keystore.properties` and
fill it in.

---

## 4. The native core

The core is **built from source in CI**, not committed. `.github/workflows/build.yml`:

1. checks out `ArasTey/AndroidLibXrayLite` (bindings) and `ArasTey/xray-core` **at the tag
   `aras-core-26.9.27`** — pinned by tag so a release builds the core it was tested against
2. builds `aras-core.aar` with `gomobile bind -androidapi 24`
3. asserts on the resulting `.so` that AnyTLS and AmneziaWG are present and stock
   wireguard-go is **not**
4. builds the app, runs tests and lint
5. attaches the APKs to the release

### The core fork

`ArasTey/xray-core` carries three patch families on top of patterniha v26.9.27:

| Patch | Why it exists |
|---|---|
| **AnyTLS** outbound + vendored transport stack | not in upstream 26.9.27 at all |
| **AmneziaWG 3.1** junk params (`PeerConfig` fields 6–14) plus the UAPI emission that must precede the first `public_key=` line | ArasClient-specific; upstream has none of it |
| **`allowInsecure`** as `tls/config.proto` field 23 | upstream rejects the setting outright |
| `DeviceConfig.domain_strategy` (field 7) | patterniha dropped it between .13 and .27; AmneziaWG needs it |

**amneziawg-go is imported directly** (`github.com/amnezia-vpn/amneziawg-go/v3/...`), never via
`replace golang.zx2c4.com/wireguard`. That replace makes Go load one module under two paths and
fails with `used for two different module paths`. `proxy/masque` and `proxy/tun` must import the
amneziawg path too, because they exchange tun types with `proxy/wireguard`.

### Rebuilding the core by hand

```bash
cd ~/corebuild/xray-core
git checkout feat/patterniha-26.9.27

cd ~/corebuild/AndroidLibXrayLite
go mod edit -replace=github.com/xtls/xray-core=../xray-core
go mod tidy

export ANDROID_HOME=/Users/admin/Library/Android/sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.3.13750724
export GOPROXY=https://goproxy.cn,direct     # proxy.golang.org 403s from here
gomobile bind -androidapi 24 -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0' \
  -o /Users/admin/Documents/ArasClient/ArasClient/app/libs/aras-core.aar ./
```

**Always verify afterwards** — this is the check CI runs:

```bash
AS=~/Library/Android/sdk/build-tools/36.0.0/apksigner   # or any build-tools
cd /tmp && rm -rf ck && mkdir ck && cd ck
unzip -o -q /Users/admin/Documents/ArasClient/ArasClient/app/build/outputs/apk/release/ArasClient_*.apk \
  "lib/arm64-v8a/libgojni.so"
strings lib/arm64-v8a/libgojni.so | grep -ci anytls        # must be > 0
strings lib/arm64-v8a/libgojni.so | grep -ci amneziawg     # must be > 0
strings lib/arm64-v8a/libgojni.so | grep -c 'golang.zx2c4.com/wireguard'  # must be 0
```

A non-zero wireguard-go count means the AmneziaWG replace did not take effect and the junk
parameters are being accepted and then ignored.

---

## 5. Native libraries bundled in the APK

`app/libs/<abi>/` is a `jniLibs` source directory, so anything placed there is packaged into
the APK. `useLegacyPackaging = true` and `extractNativeLibs="true"` are what make those files
**executable on device** — a file in the app's data directory cannot be exec'd on Android 10+,
which is why these are shipped rather than downloaded.

| Library | Source | ABI |
|---|---|---|
| `libgojni.so` | built from source in CI | all four |
| `libaether.so` | `CluvexStudio/Aether` v2.1.0, checksum verified | arm64-v8a, armeabi-v7a, x86_64 |
| `libpsiphon-tunnel-core.so` | `enfein/mieru` v3.38.0 — actually Mieru's psiphon | arm64-v8a, armeabi-v7a, x86_64 |
| `liblyrebird.so` | same tarball — Tor's pluggable transport | arm64-v8a, armeabi-v7a, x86_64 |
| `libhev-socks5-tunnel.so`, `libhevsockstun.so` | prebuilt, long-standing | all four |

Mieru publishes **no `armeabi-v7a` or `x86` Android build** — only arm64. That is why those
splits are smaller and why the Mieru editor reports the client as absent rather than failing
obscurely.

The Psiphon **server list** lives at `app/src/main/assets/psiphon_servers.dat` and is a signed,
zlib-compressed list from Psiphon. Its signature is verified against Psiphon's public key in
`PsiphonServerList.kt` on every use. If you refresh it, verify before shipping:

```bash
curl -fsSL -o /tmp/list.dat \
  https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed
shasum -a 256 /tmp/list.dat
```

---

## 6. Helper processes

Three protocols are dialled by a **separate process**, not by the core. The app's outbound for
each is a SOCKS hop to that process's loopback listener.

| Profile | Process | Start | Readiness |
|---|---|---|---|
| Aether | `libaether.so run` | `AETHER_CONFIG`, `AETHER_MASQUE_CONFIG`, `AETHER_WG_CONFIG` env + flags | `identity ready: device=` |
| Aether scan | same, with `scan=true` arguments | one-shot | `selected WireGuard endpoint X` |
| Mieru | `libmieru.so run` | `MIERU_CONFIG_JSON_FILE` env | `socks5 server is running` |
| AmneziaWG | in-process, `awg-go` | via `ArasCoreManager` | listener probe |

**Both external processes can be orphaned** by an app process that is killed — Rust ignores
SIGPIPE and neither daemon has parent-death handling. A survivor keeps the loopback port and the
next start dies with `address already in use`. Both managers therefore reap before starting,
keyed on the owning pid being present in `/proc/<pid>/environ`.

**Aether specifics.** Registering a WARP key calls `https://api.cloudflareclient.com/v0a4471/reg`,
so **the VPN must be ON for that step**; scanning for an endpoint wants it **OFF** so the sweep
sees the network you will actually use. The editor says so.

**Two flags aether 2.1 rejects**, found by running the real binary — do not reintroduce them:
`--psiphon-server-entries` and `--psiphon-cdn-sets`. The server list goes through the core's own
psiphon config instead. `AetherFlagTest` pins the accepted set.

---

## 7. Config types

`EConfigType` is the spine. Adding one means touching all of the places below — the AnyTLS
commit (`27c03be`) touched 22 files and is the template to follow.

| Type | Notes |
|---|---|
| VMESS, VLESS, TROJAN, SHADOWSOCKS, SOCKS, HTTP | stock |
| WIREGUARD, AMNEZIAWG | share the wireguard outbound; AMNEZIAWG adds junk params |
| HYSTERIA, HYSTERIA2 | share the hysteria outbound |
| ANYTLS | has its own transport |
| MASQUE | import-only: no editor, `EConfigType.IMPORT_ONLY` hides Edit |
| AETHER | import-only; dials the aether daemon's SOCKS |
| MIERU | import-only; dials the mieru client's SOCKS |
| POLICYGROUP, PROXYCHAIN | complex; no outbound of their own |

**Adding a protocol, the checklist:** `EConfigType` → scheme in `AppConfig` → `XxxFmt` parser
and `toUri` → register in `AngConfigManager.configFmtParsers` and `shareConfig` → outbound
builder + `createInitOutbound` → wire int in `ArasShareItem` → profile fields in `ProfileItem`
→ editor screen + manifest + strings in **every** locale → ping exclusion → mux-disable list →
`TestSettings`-backed tests.

**Numbers that matter:** `EConfigType.value` is *not* the on-disk format. Gson persists enums
by `name()`, so renaming a constant invalidates saved profiles. The `ArasShareItem` ints are a
separate, externally-visible wire format inherited from v2rayN — **7 and 9 are swapped**
relative to `EConfigType` there, and never renumber 1–12.

---

## 8. Localization

The Persian locale carries **every** string the default does. When you add a string, add it to
`values/strings.xml` **and** `values-fa/strings.xml` in the same change — 134 strings went
missing once and the whole Aether and Mieru editor rendered in English inside a Persian app.

```bash
# find anything untranslated
python3 - <<'PY'
import re
n=lambda p:set(re.findall(r'<string name="([^"]+)"',open(p,encoding='utf-8').read()))
d=n('app/src/main/res/values/strings.xml'); fa=n('app/src/main/res/values-fa/strings.xml')
print(len([k for k in d if k not in fa]), "missing")
PY
```

`android:supportsRtl="true"` is set and the code uses direction-aware modifiers. **One trap:**
the top bar draws the wordmark as two `Text`s in a `Row`, which laid out right-to-left and made
the title read "ClientAras". That `Row` is pinned LTR via `CompositionLocalProvider`. Anywhere
else a Latin run appears inside Persian text, wrap it with `String.ltrIsolated()` — but never
where it builds a file name, or the marks end up in the path.

---

## 9. Before you push

```bash
git status --short              # anything unexpected?
git diff                        # read it
```

Checklist:

- [ ] `./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintVitalRelease` is green
- [ ] New strings exist in `values-fa/strings.xml`
- [ ] No secrets: `git diff | grep -iE "password|token|apikey|private key|ghp_"`
- [ ] Nothing under `keystore/` or `keystore.properties` is staged — `git status` should not
      show them
- [ ] If `app/libs/aras-core.aar` changed, the four marker counts from §4 still hold
- [ ] If `app/libs/<abi>/*.so` changed, the ELF machine type matches the directory
      (`0xb7` arm64, `0x28` armv7, `0x3e` x86_64)

```bash
# confirm no key is about to be committed
git diff --cached --name-only | grep -iE "keystore|\.jks" && echo "STOP"
```

```bash
# push. The lowSpeed settings matter: the repo carries ~160 MB of native
# libraries and a plain push times out with HTTP 408.
git -c http.postBuffer=524288000 -c http.lowSpeedLimit=0 -c http.lowSpeedTime=999999 \
    push origin <branch>
```

If you changed the core, push and tag it **first** — CI checks the tag out, so a release will
build the old core otherwise:

```bash
cd ~/corebuild/xray-core
git push aras <branch>
git tag -f aras-core-<version> <commit> && git push aras aras-core-<version>
```

Then update `ref:` in `.github/workflows/build.yml` to the new tag.

---

## 10. Before a release

- [ ] `versionName` and `versionCode` bumped in `app/build.gradle.kts`
- [ ] Full gate green
- [ ] **Signed with the release key**, not debug:
      `apksigner verify --print-certs <apk>` must show `CN=ArasClient, OU=Release`
- [ ] All five APKs present and sharing one signature
- [ ] Release notes written — say what changed and what is still unproven

```bash
# one command to check every APK is signed with the release key
AS=~/Library/Android/sdk/build-tools/36.0.0/apksigner
cd app/build/outputs/apk/release
for f in *.apk; do
  echo "$("$AS" verify --print-certs "$f" 2>/dev/null | grep 'SHA-1 digest' | head -1)  $f"
done
```

**Creating a release.** Prefer a **new** tag for anything a user has downloaded, so the tag
stays a faithful source reference:

```bash
git push origin main
git tag -a v1.7.11 -m "v1.7.11"
git push origin v1.7.11
gh release create v1.7.11 --title "ArasClient v1.7.11" \
   --notes-file /tmp/notes.md --target "$(git rev-parse HEAD)"
```

**Replacing the files on an existing release** — no new tag, no new release. Use this when only
assets changed and the version number did not:

```bash
cd app/build/outputs/apk/release
gh release upload v1.7.10 --repo ArasTey/ArasClient ArasClient_1.7.10_*.apk --clobber
```

Be aware this leaves the **tag pointing at older source than the binaries**. Anyone who
downloads gets the new build, but the tag no longer describes it. Say so in the notes.

Then watch the run and verify the artefact, not just the green tick:

```bash
gh run list --limit 3
gh run view <id> --json jobs -q '.jobs[] | .name + "=" + .conclusion'
gh release view <tag> --json assets -q '.assets[].name'

# download one and check what is actually inside
gh release download <tag> --repo ArasTey/ArasClient -p "*_arm64-v8a.apk" -D /tmp/v
strings /tmp/v/*.apk >/dev/null   # then the marker checks from §4
```

---

## 11. What is done

- **Core on patterniha v26.9.27**, all patches carried over, MASQUE and XDRIVE available.
  AnyTLS, AmneziaWG junk params, `allowInsecure` and `domain_strategy` verified in the binary.
- **Five config-generation bugs fixed**, each found by a test that was written to look for it:
  - AnyTLS emitted `network: "tcp"` with no `anytlsSettings` — the transport was set where
    `populateTransportSettings` immediately overwrote it, so the feature never worked at all
  - AmneziaWG junk defaults were applied to the profile *after* the peer was populated, so the
    first connect tunnelled unobfuscated
  - a non-numeric or out-of-range port threw out of `convertProfile2Outbound`, whose callers
    only guard a null return — one bad server killed the whole configuration
  - Hysteria2 share links dropped bandwidth and the port-hopping interval
  - AmneziaWG H3 (`cookiePacketJunkHeader`) was parsed from `.conf` but never from a URI, and
    never emitted
- **MASQUE, XDRIVE, AETHER and MIERU** config types, Aether and Mieru import-only.
- **Core failures are diagnosable**: the core's own error reaches the UI instead of a generic
  "Failed to start service", and the generated config is logged at error level.
- **CI actually gates**: it runs the tests and lint (it used to only assemble), and asserts on
  the built core.
- **Release signing** with a dedicated key, out of the repository, failing loudly if absent.
- **RTL**: the wordmark reads correctly in Persian, and Persian covers every string.
- 178 unit tests, up from 17.

## 12. What is not done

- **Nothing has been device-tested.** Every release from 1.7.5 to 1.7.10 was built and verified
  but never installed. That is the largest open risk, and it covers the helper-process
  lifecycle, the orphan reaping, the port allocation and the new button.
- **SSH is not implemented.** There is no standalone SSH-to-SOCKS binary for Android, so it needs
  a new outbound inside the Go core using `golang.org/x/crypto/ssh` — the same shape of work as
  AnyTLS. `golang.org/x/crypto` is already in the module graph, so the dependency is free.
- **TUIC is deliberately disabled.** patterniha 26.9.27 has no TUIC implementation at all; the
  enum entry is commented out in `EConfigType.kt` and must not be enabled without a core port.
- **CI does not run on pull requests** — only on push to `main` and on release. Adding
  `pull_request` with the fast jobs would catch breakage in minutes instead of at release time.
- **Every release run rebuilds the core**, ~10 of its ~11 minutes. Skipping the rebuild when the
  core tag is unchanged would make releases roughly three times quicker.
- **The observatory settings are global.** `CoreConfigManager` merges several policy groups into
  one observatory and the first wins. This is a **core limitation** — `Observatory` is a single
  object in `infra/conf/xray.go`, not a list — so it cannot be fixed in the app.
- **APK size is growing**: ~52 MB per split, ~140 MB universal, mostly native libraries.

---

## 13. The three files that matter most

If you only read three things:

1. **`.github/workflows/build.yml`** — how a release is actually built, which core, and what is
   asserted about the result.
2. **`app/build.gradle.kts`** — versioning, the ABI splits, and the signing config.
3. **`app/src/main/java/com/aras/client/core/CoreOutboundBuilder.kt`** — where a profile becomes
   an outbound. Every config bug found this session was in here or in its callers.