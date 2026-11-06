# AMOVN — Android-only ZX integration workspace

This folder contains verbatim source snapshots from the authorized ZX VPN branch.

- Source repository: https://github.com/AMINOPY/ZX-VPN-
- Source ref: `config-finder-liquid-integration`
- Target: `AMINOPY/G-ST-VPN` branch `not-SIGNAL/NOT-NET`
- Platform target: Android only

## Copied source inventory

The files under `ZX-source/` preserve the original relative paths and source contents:
- Live Monitor page, Android platform metrics, and platform data model
- Settings screen source
- Connection Map screen, view model, canvas renderer, and route badge
- World map model, geographic lookup, and payload model
- Binary world map resource (`world.bin`)

## Integration status

These are staged source copies, not yet wired into G-ST-VPN's Android source sets. The Settings screen and map implementation reference ZX-specific resources, models, navigation, and KMP-generated APIs. The next integration phase must port/adapt those dependencies to the existing Kotlin/Jetpack Compose Android app and connect the screens to its settings/theme navigation.

No desktop/KMP target was added. No unrelated app source files were changed by this staging step. DNS Scanner / SlipNet work remains a separate implementation task and is not represented as completed by these source copies.
