# AMOVN — Android integration workspace

This directory stages the Android-relevant source from:

- Repository: https://github.com/AMINOPY/ZX-VPN-
- Source ref: `config-finder-liquid-integration`
- Source commit inspected: `41796b68fc171a351fa668d7991499cca395c0d0`

## Mirrored source scope

- `AMOVN/ZX-source/` preserves the selected ZX source tree and the complete audited Kotlin files for Sing-box, Tor, Psiphon, OpenVPN configuration/import, settings and UI.
- `AMOVN/SlipNet-source/` preserves the relevant SlipNet DNS Scanner/Resolver, Tor/Snowflake bridge, VPN/profile, storage, navigation and dependency source files at the pinned commit.
- Each mirror includes its upstream README and AGPL-3.0 license file.

The ZX source mirror includes selected files for:

- Live Monitor UI, platform statistics contract and Android implementation
- Connection Map UI, view model, canvas, route badge, world-map data and geo lookup
- Settings UI and appearance/style pickers
- ZX theme system: colors, gradients, Liquid surfaces, motion budget, personalization, shapes, typography and theme profiles
- Navigation UI and related network/telemetry support sources
- World map binary data

The earlier `AMOVN/ZX-VPN/` subtree is a small initial mirror of a subset of ZX files; use `AMOVN/ZX-source/` as the canonical selected-source mirror. SlipNet source files are isolated under `AMOVN/SlipNet-source/` and are reference material only.

## Integration status

These mirrors are upstream reference snapshots, **not compiled source sets**. The target app now contains an adapted DNS scan coordinator/progress/cancel flow and a native offline Compose world map. The Sing-box, Tor, Psiphon and OpenVPN runtime integrations remain blocked on native bindings/binaries and service adaptation. Do not add ZX KMP or SlipNet Hilt/database/service graphs wholesale.

No unrelated G-ST-VPN features are intended to change. The work is scoped to branch `not-SIGNAL/NOT-NET`; `main` is not the target.

## Licenses

Both upstream projects are AGPL-3.0 at the pinned refs. Their license texts are mirrored as `AMOVN/ZX-source/LICENSE` and `AMOVN/SlipNet-source/LICENSE`. Preserve all upstream copyright and license notices and verify individual native/dependency licenses before packaging. The target branch also carries AGPL-3.0.
