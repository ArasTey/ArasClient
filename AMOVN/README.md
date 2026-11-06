# AMOVN — Android ZX Integration

This directory is the isolated workspace for the Android-only integration of the authorized ZX VPN features into G-ST-VPN.

## Scope
- Live Monitor UI, animations, Android metrics, and supporting resources.
- Connection Map UI, map rendering, geographic lookup, and map assets.
- Relevant Settings and theme UI, including Liquid styling and transitions.
- DNS Scanner behavior and UI improvements based on the approved SlipNet reference.

## Rules
- Android-only implementation using the target app's Kotlin/Jetpack Compose architecture.
- Adapt platform-specific dependencies to G-ST-VPN; do not add desktop or other KMP targets.
- Keep unrelated G-ST-VPN features untouched.
- All work stays on `not-SIGNAL/NOT-NET`; never modify `main`.
- Preserve explicit user confirmation for applying DNS resolver changes.
- Do not treat DNS timeouts as definitive proof of DPI.

Source files and resources will be organized here only when they can be integrated cleanly into the Android build; runtime source code must be wired into the app's source sets as needed.
