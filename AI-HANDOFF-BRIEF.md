# ArasClient — AI Handoff Brief
# (send this to any AI; it knows the files and what to do)
# Owner: ArasTey (persian-speaking). Repo: https://github.com/ArasTey/ArasClient
# Local workspace: /Users/admin/Documents/ArasClient/ArasClient
# Extra repos: ArasTey/xray-core, ArasTey/AndroidLibXrayLite, ArasTey/freesub

## PENDING TASKS (do these, v1.7.0 — DO NOT RELEASE, code changes only)

### 1. GroupTabBar UI broken in "double / two-column" display mode
File: app/src/main/java/com/aras/client/ui/main/GroupTabBar.kt (+ MainServerPager.kt)
In two-column mode:
  1. Country flag overlaps the ping needle/arrow icon
  2. Green ping number is truncated/cut off (shown incomplete)
  3. Overall UI is messy
Fix: add proper spacing between flag → ping row, let ping text have min width,
clip only the ping text itself, and verify both single and two-column layouts.

### 2. Right arrow in group tab bar is redundant
File: GroupTabBar.kt (the trailing arrow/chevron next to the Aras logo area)
When the settings menu opens, the arrow to the right of the Aras logo must be
removed (it duplicates the menu affordance).

### 3. Logo background box color should match theme (it's currently inverted)
File: GroupTabBar.kt — the container behind the Aras logo.
Light theme: box appears DARKER than surface (wrong) → make it lighter
(surfaceContainerHigh-ish). Dark theme: it appears LIGHTER (wrong) → make it
darker (surfaceContainerLow-ish). Use MaterialTheme.colorScheme tokens, not
hardcoded colors.

### 4. Status subtitle text should clear after leaving the screen
Files: app/src/main/java/com/aras/client/ui/main/MainScreen.kt →
`MainBottomBar(subtitleOverride = ...)`, MainViewModel.formatStatus.
Currently after a test shows:
  "Connection succeeded in 151 ms" / "(NL) 152.55.185.184" /
  "Connected. Tap to check connection."
the "(Connected. Tap to check connection.)" subtitle sticks around under the
row forever. Wanted: show it once after connect/ping; clear it afterwards
(e.g. revert to "Tap to connect" / empty) until the next connect/ping on that
server. Implement via a timeout or a one-shot flag in MainViewModel state.

### 5. Update the cores
Files: ~/corebuild/xray-core (branch feat/amneziawg), ~/corebuild/AndroidLibXrayLite
- git fetch upstream, rebase onto latest Xray-core, keep all ArasClient patches:
  - AnyTLS outbound (auth frame SHA-256 + padding, session mux, UoT for UDP)
  - AmneziaWG 3.1 (junk params DEVICE-level, emitted BEFORE first public_key)
  - allowInsecure restore (proto field 23)
  - TLS proto fields 13/14 cookie/transport junk headers
- Then: gomobile bind -androidapi 24 -trimpath
  -ldflags='-s -w -buildid= -checklinkname=0' -o aras-core.aar ./
  → copy to app/libs/aras-core.aar
- Toolchain: Go 1.25+, JDK 17, NDK r27 (~/Library/Android/sdk/ndk/27.3.13750724),
  GOPROXY=https://goproxy.cn,direct (proxy.golang.org 403s locally)
- Verify: strings libgojni.so | grep -cE 'anytls|amnezia' > 0, 'patterniha' == 0

### CONSTRAINTS
- versionName = 1.7.0 (bump versionCode accordingly)
- DO NOT create a release, do NOT upload APKs — code changes only
- Git identity: ArasTey <ArasTey@users.noreply.github.com> only
- app/libs/arasc-crypto.aar is intentionally closed-source
- Never hardcode scheme subsets when parsing links — use full
  AngConfigManager.parseAnyLink / configFmtParsers dispatch
- The Free sub fetches from ArasTey/freesub (config.txt) at launch + update;
  all its configs are protected (view/edit/share blocked; ping/connect/delete OK)
- CI: .github/workflows/build.yml builds core from public source then the app;
  signing keystore is committed (keystore/debug.keystore + keystore.properties)

## CURRENT STATE (as of handoff)
- Last commit: 17750d7 "v1.6.9: bottom bar ping colors" (already on origin/main)
- versionName 1.6.9, versionCode 136 in build.gradle.kts (ABI offsets add +3/+4)
- Latest release: v1.6.9 (left dot + connect button green/red by ping — shipped)
- Free sub verified: fetches ArasTey/freesub, protected, reorder index fix done
- Working tree clean
