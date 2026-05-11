# kabu-kabu-p2p

**Kabu-Kabu** is a privacy-first, peer-to-peer taxi and courier app for Nigeria — and anywhere people want rides without a surveillance trail.

## Privacy model

| What traditional apps do | What Kabu-Kabu does |
|---|---|
| Store exact GPS coordinates on a central server | Convert GPS to a ~5 km GeoHash cell; raw coords are discarded immediately |
| Tie every trip to your real phone number | SHA-256 hash your number on-device; plaintext never leaves the phone |
| Route all traffic through a company relay | All peer traffic goes through the I2P anonymity network (3-hop garlic routing) |
| Keep trip history in a cloud database | Trip records live only in the on-device Room database |
| Share location data with advertisers | No server, no ads, no data brokers |

## Architecture

```
Rider/Driver device
├── MainActivity / DriverActivity
│     └── I2PKabuClient  ──TCP(127.0.0.1:8881)──▶
│                                                  I2PKabuService (foreground)
│                                                  ├── EmbeddedI2PRouter (I2P network)
│                                                  ├── Local TCP bridge (127.0.0.1:8881)
│                                                  └── KabuDatabase (Room / SQLite)
│
└── Privacy layer:
      GeoHash-5 only (no raw GPS over network)
      SHA-256 device identity (no plaintext phone)
      I2P garlic routing (no IP exposure)
      No cloud backup (backup_rules.xml + data_extraction_rules.xml)
```

## Tech stack

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8 Oreo) — required by I2P embedded router
- **DI**: Hilt
- **Persistence**: Room (on-device only)
- **Networking**: Embedded I2P router + SAM bridge
- **Location**: Google Fused Location → GeoHash-5 (ch.hsr:geohash)
- **Maps**: OsmDroid (offline tiles, no Google Maps API key)
- **Build**: Gradle with KSP, product flavors: `playstore` / `fdroid`

## Getting started

### Prerequisites

1. Android Studio Meerkat or later
2. JDK 17
3. I2P JARs in `app/libs/` — see [`app/libs/README.md`](app/libs/README.md)

### Build (without I2P JARs — stub mode)

```bash
git clone https://github.com/your-org/kabu-kabu-p2p
cd kabu-kabu-p2p
./gradlew :app:assemblePlaystoreDebug
```

### Build (full I2P mode)

Follow `app/libs/README.md` to place `router.jar`, `sam.jar`, and
`i2p-android-client.aar`, then:

```bash
./gradlew :app:assemblePlaystoreRelease
```

## Project structure

```
app/src/main/java/com/techducat/kabukabup2p/
├── KabuKabuApp.kt             Application class; starts I2P on boot
├── MainActivity.kt            Rider UI
├── I2PKabuClient.kt           P2P transport layer (replaces central relay)
├── model/
│   └── RideRequest.kt         Domain models (RideRequest, DriverOffer, TripEvent)
├── db/
│   ├── TripEntity.kt          Room entities
│   ├── TripDao.kt             Room DAO
│   └── KabuDatabase.kt        Room database singleton
├── network/
│   └── EmbeddedI2PRouter.kt   I2P router lifecycle
├── service/
│   └── I2PKabuService.kt      Foreground service; matchmaking bridge
└── ui/
    └── Adapters.kt            RecyclerView adapters
```

## Compared to buzzr-p2p

Kabu-Kabu is built on the same I2P P2P substrate as
[buzzr-p2p](../buzzr-p2p) but adapted for transport matching:

| buzzr-p2p | kabu-kabu-p2p |
|---|---|
| `IncidentClassifier` (keyword NLP) | `RideRequest` / `DriverOffer` matching |
| Community incident broadcast | Ride/courier request broadcast + driver offer response |
| I2P fan-out to all zone peers | I2P broadcast to zone + direct tunnel for accepted trips |
| `I2PBuzzrService` on port 8880 | `I2PKabuService` on port 8881 |
| Incident TTL 48 h | Request TTL 10 min; trip records retained 30 days |

## Roadmap

- [ ] DriverActivity (driver-side UI with incoming request list)
- [ ] OSMDroid offline map view with GeoHash cell overlay
- [ ] Post-trip peer review gossip (P2P star ratings)
- [ ] Offline fare estimator using OSRM local routing engine
- [ ] Crypto payment integration (USDT / cNGN via on-device wallet)
- [ ] F-Droid reproducible build
