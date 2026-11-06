# AMOVN — ZX VPN Android integration workspace

This directory stages the Android-relevant source from:

- Repository: https://github.com/AMINOPY/ZX-VPN-
- Source ref: `config-finder-liquid-integration`
- Source commit inspected: `41796b68fc171a351fa668d7991499cca395c0d0`

## Mirrored source scope

The canonical source mirror is under `AMOVN/ZX-source/`, preserving the original repository paths. It currently contains the 53 source files selected for:

- Live Monitor UI, platform statistics contract and Android implementation
- Connection Map UI, view model, canvas, route badge, world-map data and geo lookup
- Settings UI and appearance/style pickers
- ZX theme system: colors, gradients, Liquid surfaces, motion budget, personalization, shapes, typography and theme profiles
- Navigation UI and related network/telemetry support sources
- World map binary data

The earlier `AMOVN/ZX-VPN/` subtree is a small initial mirror of a subset of these files; use `AMOVN/ZX-source/` as the canonical complete selected-source mirror.

## Integration status

This is a source mirror, **not yet a completed app integration**. It is not added to G-ST-VPN's Android Gradle source sets. ZX source depends on Compose Multiplatform resources, ZX-specific settings/domain models, navigation and platform contracts. The next stage must port/adapt those dependencies to G-ST-VPN's Android-only Kotlin + Jetpack Compose app, import the UI resources needed by the selected screens, and wire them into the existing theme picker/navigation.

No unrelated G-ST-VPN features are intended to change. The work is scoped to branch `not-SIGNAL/NOT-NET`; `main` is not the target.

## License

The source project is AGPL-3.0. Preserve its copyright and license notices and comply with AGPL-3.0 when distributing a combined/derived integration. The target branch currently carries an AGPL-3.0 license.
