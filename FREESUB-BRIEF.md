# ArasClient — AI Handoff Brief (persian-speaking owner: ArasTey)

## WHAT THIS APP IS
ArasClient — Android VPN client (Kotlin + Jetpack Compose), fork lineage from
2dust/v2rayNG. Package: `com.aras.client`. Public repo:
https://github.com/ArasTey/ArasClient. Latest release: **v1.6.8** (versionCode 140).

## CORE ARCHITECTURE
The core is a custom-built AAR at `app/libs/aras-core.aar`, built from:
- **ArasTey/xray-core** (public): Xray-core 26.9.1 + patches:
  - AnyTLS outbound (password auth frame SHA-256+padding, session mux, UoT for UDP)
  - AmneziaWG 3.1 (wireguard-go → amneziawg-go v3.1.20260828; junk params
    Jc/Jmin/Jmax/S1/S2/H1-H4 are DEVICE-level in AWG UAPI and MUST be emitted
    BEFORE the first `public_key=` line)
  - `allowInsecure` restored (proto field 23 `insecure_skip_verify`)
  - TLS proto field 13/14: cookie/transport junk headers (H3/H4 fix)
- **ArasTey/AndroidLibXrayLite** (public): gomobile bindings +
  `AwgTurnOn(fd, uapiConfig, mtu)` — standalone amneziawg-go tunnel (fd wrapped
  with minimal read/write tun.Device, no /dev/net/tun ioctls — those are EACCES).
  Go DUPS the fd (fdsan double-close kills process otherwise); Kotlin detaches.
- `keystore/debug.keystore` + `keystore.properties` are COMMITTED so CI and
  local builds share the same signature (root-relative path:
  `storeFile=keystore/debug.keystore`).

## FREE SUB FEATURE (current design)
`ArasTey/freesub` (public repo) holds `config.txt` — one link per line
(vless:// ss:// trojan:// anytls:// awg://... all schemes supported via
`AngConfigManager.parseAnyLink`).

`FreeSubManager.kt` (app/src/main/java/com/aras/client/handler/):
- `DEFAULT_URL = https://raw.githubusercontent.com/ArasTey/freesub/main/config.txt`
- `sync()`: ensures group "Free" (guid = `freesub-protected`), fetches URL,
  imports every link as PROTECTED profile (marked via
  `ArasExportImportManager.markProtected`) → users cannot view/edit/share;
  they CAN ping and connect. Delete IS allowed now.
- Called from `MainViewModel.initialize` + `importConfigViaSub` (update button)
- Group/sub is hidden from Subscriptions settings list
  (SubscriptionsViewModel filters FREE_SUB_ID)
- Sub removal guards: `removeSubscription`/`removeServerViaSubid` return early
  for FREE_SUB_ID

## ⚠️ KNOWN OPEN BUG — sub reordering broken
Symptom: dragging subs in Subscriptions settings (SubSettingActivity.kt) does
not reorder them.

Wired like this:
- `SubSettingActivity.kt:132` — `rememberReorderableLazyListState(lazyListState)
  { from, to -> viewModel.move(from.index, to.index) }`
- `SubscriptionsViewModel.move(from, to)` — uses `subscriptions.moveItem(...)`
  extension, persists via `MmkvManager.encodeSubsList`, refreshes `_subsFlow`
  (which filters out FREE_SUB_ID).

Likely causes to investigate:
1. `_subsFlow` FILTERS OUT the Free sub — so the LazyColumn indexes (from/to)
   come from the FILTERED list, but `subscriptions.moveItem(from, to)` operates
   on the UNFILTERED backing list which includes the Free sub at its stored
   position → index mismatch → wrong item moved / no visual change.
2. The free sub may sit at index 0 of the backing list (imported first), so
   every drag is off-by-one or targets the free sub.
3. Possible fix directions: (a) make `move()` translate filtered indexes to
   unfiltered indexes, or (b) keep the Free sub out of `subscriptions`
   entirely and store it separately, or (c) sort/rebuild the visible list from
   the encoded subsList order on reload.

Also verify: does `MmkvManager.encodeSubsList` include the FREE_SUB_ID guid
(`decodeSubscriptions()` reads it from `subStorage`)? If the Free sub guid is
written into SUBS list, it will also appear in the reorder list and shift
everything.

## KEY FILES
- app/build.gradle.kts — versionCode/versionName (136 / 1.6.8)
- app/libs/aras-core.aar — compiled core (rebuild via AndroidLibXrayLite)
- app/src/main/java/com/aras/client/handler/FreeSubManager.kt — free sub
- app/src/main/java/com/aras/client/handler/ArasExportImportManager.kt —
  protected marks (isProtected/markProtected/forgetProtected)
- app/src/main/java/com/aras/client/handler/MmkvManager.kt — storage layer +
  removal guards
- app/src/main/java/com/aras/client/handler/AngConfigManager.kt — sub update +
  `parseAnyLink`
- app/src/main/java/com/aras/client/core/CoreServiceManager.kt — launchCore
  branches AMNEZIAWG before xray
- app/src/main/java/com/aras/client/ui/subscription/SubSettingActivity.kt —
  reorder UI (the bug)
- app/src/main/java/com/aras/client/ui/subscription/SubscriptionsViewModel.kt
- .github/workflows/build.yml — CI: builds core AAR from public source then app

## GOTCHAS
- Any push containing .github/workflows needs gh token with `workflow` scope
- `arasc-crypto.aar` is intentionally closed-source — never decompile/commit
  its sources
- Git identity: ArasTey <ArasTey@users.noreply.github.com> ONLY
- GitHub Actions builds sign with committed keystore → same signature as local
- Emulator `Waira_Emulator` gets SystemUI ANRs after many force-stops — flaky
  for UI automation
- When parsing user links, NEVER hardcode a scheme subset — always use the
  full `configFmtParsers` dispatch (past bug: ss:// and trojan:// were dropped)
