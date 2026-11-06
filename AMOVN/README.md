# AMOVN — ZX VPN Android integration workspace

This directory is an isolated source mirror for the requested Android-only integration from:

- Repository: https://github.com/AMINOPY/ZX-VPN-
- Source ref: config-finder-liquid-integration
- Source commit inspected: 41796b68fc171a351fa668d7991499cca395c0d0

## Scope mirrored here

- Live Monitor UI and platform statistics contract
- Android platform statistics implementation
- Connection Map UI, view model, canvas, route badge, world-map data and geo lookup models
- Settings screen and selected appearance/style picker components
- World map binary resource

The original paths are preserved below `AMOVN/ZX-VPN/` to make provenance and dependency tracing explicit.

## Important integration boundary

This folder is a verbatim source/dependency staging area. It is deliberately not added to the Android Gradle source sets: the ZX VPN files depend on ZX-specific packages, Compose Multiplatform resources, navigation, settings models, and platform contracts. The next integration stage must port/adapt those dependencies into G-ST-VPN's Android-only Kotlin/Jetpack Compose architecture, then wire the screens into its existing navigation/theme system.

No unrelated G-ST-VPN features are changed by this staging commit.

## License

The user states they have the necessary permissions for direct reuse. Preserve the applicable AGPL-3.0 licensing and copyright notices from the source project when integrating or redistributing these files.
