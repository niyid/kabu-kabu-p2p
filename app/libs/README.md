# app/libs — I2P Embedded Router Artifacts

Kabu-Kabu P2P uses the same embedded I2P router strategy as buzzr-p2p.
The three artifacts below must be placed here before building.

## Required files

| File | Source |
|------|--------|
| `router.jar` | Built from [i2p.i2p](https://github.com/i2p/i2p.i2p) — run `ant pkg` |
| `sam.jar` | Same build, in `pkg/` output folder |
| `i2p-android-client.aar` | Built from [i2p.android.base](https://github.com/i2p/i2p.android.base) — `./gradlew assembleRelease` |

## Build steps

```bash
# 1. Build i2p.i2p
git clone https://github.com/i2p/i2p.i2p
cd i2p.i2p && ant pkg
cp pkg/router.jar /path/to/kabu-kabu-p2p/app/libs/
cp pkg/sam.jar    /path/to/kabu-kabu-p2p/app/libs/

# 2. Build i2p.android.base
git clone https://github.com/i2p/i2p.android.base
cd i2p.android.base && ./gradlew assembleRelease
cp client/build/outputs/aar/client-release.aar \
   /path/to/kabu-kabu-p2p/app/libs/i2p-android-client.aar
```

## Without the JARs

The build still succeeds — the app runs in "stub" mode:
- I2P router is disabled
- Peer discovery uses the loopback server only (single-device testing)
- All other features (Room DB, GeoHash, UI) work normally

This lets you run UI tests without the full I2P build environment.

## SAM port

Kabu-Kabu uses SAM port **7657** (Buzzr uses 7656) to allow both apps
to run on the same device during development.
