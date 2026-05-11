# app/libs — Native dependency binaries

This directory holds the local AAR/JAR files that cannot be published to Maven Central for licensing or size reasons.

## Required files

| File | Source | Purpose |
|------|--------|---------|
| `i2p-android-client.aar` | ajo-android-p2p/app/libs/ | I2P Android client library |
| `router.jar` | ajo-android-p2p/app/libs/ | I2P router core |
| `sam.jar` | ajo-android-p2p/app/libs/ | I2P SAM v3.1 bridge |
| `monerujo-xmrwallet.aar` | ajo-android-p2p/app/libs/ | Monero wallet JNI wrapper (monerujo) |

## How to obtain

1. Clone or unzip `ajo-android-p2p`
2. Copy everything from `ajo-android-p2p/app/libs/` into this `app/libs/` directory:
   ```
   cp ajo-android-p2p/app/libs/*.aar scrowit-android-p2p/app/libs/
   cp ajo-android-p2p/app/libs/*.jar scrowit-android-p2p/app/libs/
   ```
3. Clean and rebuild: `./gradlew clean assembleDebug`

## What happens without them

The build still succeeds — `build.gradle.kts` detects absence and emits a warning. The app boots with `MockTransport` (no real I2P) and wallet operations are disabled. All trade state-machine logic, QR flows and Firestore sync still work for development.

## Native `.so` files

`monerujo-xmrwallet.aar` bundles `libmonerujo.so` for all four ABIs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`). The `packagingOptions` in `build.gradle.kts` uses `pickFirsts` to avoid duplicate `.so` conflicts if you add a second native AAR.

## Building monerujo from source (optional)

See [monerujo build instructions](https://github.com/m2049r/xmrwallet#building) if you need a specific Monero daemon version or custom network (mainnet/stagenet/testnet).
