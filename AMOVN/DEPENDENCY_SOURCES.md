# Verified Android Engine Dependency Sources

Checked: 2026-11-06  
Target: `AMINOPY/G-ST-VPN`, branch `not-SIGNAL/NOT-NET`  
ZX source reference: `config-finder-liquid-integration`  
SlipNet source reference: `2ed00fd0072ad0b558e30ec6b5e8b30948f98894`

This document records verified upstream acquisition paths. It does not claim the binaries have already been installed or that engine integration has passed device tests.

## 1. Sing-box / Libbox

### ZX dependency status

ZX's Android Gradle file declares `implementation(files("libs/zedcore.aar"))`, but `zedcore.aar` is not present in the tracked tree at the pinned source ref. ZX's `SingBoxEngine.kt` imports the generated `libbox.*` API.

### Available Android binding

- Distribution repository: https://github.com/singbox-android/libbox
- Release 1.14.4: https://github.com/singbox-android/libbox/releases/tag/1.14.4
- Direct release asset: https://github.com/singbox-android/libbox/releases/download/1.14.4/libbox.aar
- Release asset size observed: 118,293,410 bytes.
- The repository's current `build.gradle` version is 1.14.4 and documents JitPack consumption using the `com.github.singbox-android:libbox` coordinate.

Candidate Gradle dependency (after adding JitPack to dependency repositories):
```kotlin
implementation("com.github.singbox-android:libbox:1.14.4")
```

**Compatibility gate:** this is a public Libbox distribution, not the missing ZX-specific `zedcore.aar`. Before replacing the latter, compile the exact ZX `SingBoxEngine.kt` against it and verify every imported method/callback. Pin the matching sing-box core and API version; do not treat a matching package name as binary/API compatibility.

Upstream build references:
- https://github.com/SagerNet/sing-box
- https://github.com/SagerNet/sing-box/blob/testing/cmd/internal/build_libbox/main.go
- https://sing-box.sagernet.org/installation/build-from-source/

## 2. Tor Android runtime

### Recommended current upstream artifact

- Source: https://github.com/guardianproject/tor-android
- Maven Central artifact: https://central.sonatype.com/artifact/info.guardianproject/tor-android
- Verified current version on 2026-11-06: `0.4.9.14`
- Coordinate: `info.guardianproject:tor-android:0.4.9.14`
- Control library used in upstream example: `info.guardianproject:jtorctl:0.4.5.7`
- License shown in the published POM: BSD 3-Clause.

Gradle:
```kotlin
implementation("info.guardianproject:tor-android:0.4.9.14")
implementation("info.guardianproject:jtorctl:0.4.5.7")
```

The upstream project documents `libtor.so` as an Android Tor daemon library and documents API 24+ support. It lists arm64-v8a, armeabi-v7a and x86_64; x86 is possible but disabled by default. G-ST-VPN currently builds x86 too, so do not silently remove that ABI. Either build/verify x86 support or gate Tor availability by ABI.

The upstream Tor README documents reproducible source builds with NDK 30.0.16248370 and `./tor-droid-make.sh build`:
https://github.com/guardianproject/tor-android/blob/master/README.md

### Existing SlipNet reference binaries

At the pinned SlipNet commit, the full flavor tracks:
- `app/src/full/jniLibs/arm64-v8a/libtor.so`
- `app/src/full/jniLibs/armeabi-v7a/libtor.so`
- `app/src/full/jniLibs/arm64-v8a/libobfs4proxy.so`
- `app/src/full/jniLibs/armeabi-v7a/libobfs4proxy.so`

These are useful reproducible reference artifacts, but the tracked prebuilt set found here does not cover every target ABI. Preserve upstream license notices and verify hashes/provenance before any binary redistribution.

### Important: Tor is not Snowflake

SlipNet's Snowflake support is a separate Go mobile binding:
- Source wrapper: https://github.com/anonvector/SlipNet/tree/2ed00fd0072ad0b558e30ec6b5e8b30948f98894/snowflake-mobile
- Bridge implementation: https://github.com/anonvector/SlipNet/blob/2ed00fd0072ad0b558e30ec6b5e8b30948f98894/app/src/full/java/app/slipnet/tunnel/SnowflakeBridge.kt
- Full-flavor binding dependency: `app/libs/golibs-full.aar`

The Kotlin bridge imports `snowflake.Snowflake` and `snowflake.SnowflakeClient`. Adding only `libtor.so` does not provide those Go classes or the Snowflake transport. The target already has `liblyrebird.so`, but ZX's Tor config expects separately named transport executables; map/rename only after validating the binary's actual command-line interface.

## 3. Psiphon Android SDK

### Verified official SDK artifact

- Android integration guide: https://github.com/Psiphon-Labs/psiphon-tunnel-core/blob/master/MobileLibrary/Android/README.md
- Android Maven repository: https://github.com/Psiphon-Labs/psiphon-tunnel-core-Android-library
- Latest verified release on 2026-11-06: `2.0.41`
- Artifact: `ca.psiphon:psiphontunnel:2.0.41`
- Maven metadata confirms version 2.0.41.
- Release: https://github.com/Psiphon-Labs/psiphon-tunnel-core-Android-library/releases/tag/v2.0.41
- Android library bundle: https://github.com/Psiphon-Labs/psiphon-tunnel-core/releases/download/v2.0.41/Psiphon-Android-Library.zip

Gradle repository and dependency:
```kotlin
repositories {
    maven {
        url = uri("https://raw.github.com/Psiphon-Labs/psiphon-tunnel-core-Android-library/master")
    }
}

dependencies {
    implementation("ca.psiphon:psiphontunnel:2.0.41")
}
```

The SDK exposes `ca.psiphon.PsiphonTunnel`, matching the class imported by ZX's `PsiphonController.kt`.

### Existing target state

G-ST-VPN already tracks `libpsiphon-tunnel-core.so` for arm64-v8a, armeabi-v7a and x86_64. Its current `app/build.gradle.kts` does not declare the Psiphon Android SDK artifact, and `app/libs` does not contain a `psiphontunnel` AAR. The native .so alone is not proof that the `ca.psiphon.PsiphonTunnel` Java binding is available. Verify the AAR's packaged ABIs and avoid duplicate/conflicting native libraries before adding it.

## 4. Integration policy

- Prefer Maven/Gradle pinned dependencies for large AARs instead of committing 100MB+ artifacts into Git.
- Verify package API compatibility, ABI contents, minSdk, native library collisions and licenses before adding dependencies.
- Keep `CoreVpnService` as the single TUN owner. Engine adapters may manage their own process/runtime, but must not create a competing VPN service/TUN.
- Do not claim operational support until compile, unit tests, APK packaging and device-level connect/disconnect tests pass.
- Do not change `main`; all work belongs on `not-SIGNAL/NOT-NET`.
