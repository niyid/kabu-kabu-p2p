# XMR Wallet Integration — Kabu-Kabu P2P

Lifted from **Verzus** (`com.techducat.verzus.wallet.WalletSuite`) and
adapted for ride-hailing escrow semantics.

---

## What was added

| File | Role |
|------|------|
| `wallet/WalletSuite.java` | Monero wallet engine (ported from Verzus, repackaged) |
| `wallet/RideWalletManager.kt` | Kotlin façade: balance gate, escrow lifecycle, auto-pay |
| `wallet/WalletActivity.kt` | Wallet UI screen (balance + address) |
| `service/I2PKabuServiceWalletPatch.kt` | New I2P message handlers for wallet events |
| `MainActivityWalletPatch.kt` | Annotated patch instructions for MainActivity |
| `res/layout/activity_wallet.xml` | Wallet screen layout |
| `assets/wallet.properties` | Daemon configuration (stagenet default) |
| `BUILD_CHANGES.kts` | Dependency diff for `app/build.gradle.kts` |

---

## Payment flow

```
  RIDER DEVICE                          DRIVER DEVICE
  ─────────────────────────────────────────────────────────────────
  1. Rider accepts driver offer
     │
     ├─ WalletSuite.checkBalanceForRide(fare)
     │    sufficient? ─── No ──► "Top up X XMR" dialog (ride blocked)
     │    sufficient? ─── Yes ──► continue
     │
  2. RideWalletManager.prepareLocalEscrow()
     │  ← getMultisigInfo() from Monero JNI
     │
     ├──── I2P MSG: wallet_multisig_info ────────────────────────►
     │                                      prepareLocalEscrow()
     │◄─── I2P MSG: wallet_multisig_info ────────────────────────
     │
  3. finalizeEscrowWithPeer(driverInfo, fare)    finalizeEscrowWithPeer(riderInfo, 0)
     │  makeMultisig([driverInfo], threshold=2)  makeMultisig([riderInfo], threshold=2)
     │  → SAME escrow address on both devices
     │
  4. Rider's wallet sends fare to escrow address (self-funded)
     │  sendTransactionInternal(escrowAddr, fare)
     │
     ├──── I2P MSG: wallet_escrow_ready (funded=true) ──────────►
     │
  5. ··· Ride in progress ···
     │
  6. DriverActivity emits TRIP_COMPLETED event
     │◄─── I2P MSG: trip_event (TRIP_COMPLETED) ─────────────────
     │
  7. onTripCompleted() on rider device
     │  releaseEscrowToDriver(driverXmrAddress, fare)
     │  exportMultisigImages() → partial TX hex
     │
     ├──── I2P MSG: wallet_partial_tx ──────────────────────────►
     │                                      coSignAndBroadcast(partialTxHex)
     │                                      → XMR broadcast to Monero network
     │◄─── I2P MSG: wallet_tx_confirmed (txId) ──────────────────
     │
  8. tvStatus: "Payment complete ✓"             Driver receives XMR
```

---

## Multisig escrow design

| Property | Value |
|----------|-------|
| Scheme | **2-of-2 Monero multisig** |
| Parties | Rider (key-share A) + Driver (key-share B) |
| Funds | Cannot move without **both** signatures |
| Dispute | Both sign a refund TX; no third-party arbitrator needed |
| Privacy | All key exchange over existing I2P tunnel (I2PKabuService) |
| Third party | **None** — pure peer-to-peer |

---

## I2P message types added

These travel over the existing encrypted device-to-device I2P tunnel
already established by `I2PKabuService` when a ride is accepted.

| Type constant | Direction | Payload fields |
|---------------|-----------|----------------|
| `wallet_multisig_info` | bidirectional | `info`, `address`, `fare_xmr`, `ride_id`, `is_rider` |
| `wallet_escrow_ready`  | bidirectional | `escrow_address`, `ride_id`, `funded` |
| `wallet_partial_tx`    | rider → driver | `partial_tx_hex`, `driver_address`, `fare_xmr`, `ride_id` |
| `wallet_tx_confirmed`  | driver → rider | `tx_id`, `ride_id`, `refund?` |
| `wallet_refund_req`    | either side | `rider_address`, `fare_xmr`, `ride_id` |

---

## Native library setup

The wallet requires `libmonerujo.so` — the Monero JNI native library.
This is **identical** to what Verzus uses:

```
app/src/main/jniLibs/
  arm64-v8a/
    libmonerujo.so
    libc++_shared.so
  armeabi-v7a/
    libmonerujo.so
    libc++_shared.so
  x86_64/          ← emulator
    libmonerujo.so
    libc++_shared.so
```

If Verzus is in the same workspace:
```bash
ln -s ../verzus-p2p/app/src/main/jniLibs  app/libs/jniLibs
```

Build Monerujo from source or extract from a verified Monerujo APK:
```
https://github.com/m2049r/xmrwallet
```

Also place `monerujo.aar` in `app/libs/` and uncomment the line in
`BUILD_CHANGES.kts`.

---

## Apply the patch (step-by-step)

### 1. Copy new files
```
app/src/main/java/com/techducat/kabukabu/wallet/WalletSuite.java
app/src/main/java/com/techducat/kabukabu/wallet/RideWalletManager.kt
app/src/main/java/com/techducat/kabukabu/wallet/WalletActivity.kt
app/src/main/java/com/techducat/kabukabu/service/I2PKabuServiceWalletPatch.kt
app/src/main/res/layout/activity_wallet.xml
app/src/main/assets/wallet.properties
```

### 2. Merge into existing files
- `app/build.gradle.kts` ← see `BUILD_CHANGES.kts`
- `app/src/main/AndroidManifest.xml` ← add `<activity>` for WalletActivity
- `app/src/main/java/.../service/I2PKabuService.kt` ← add constants + dispatch cases (see patch file)
- `app/src/main/java/.../MainActivity.kt` ← follow `MainActivityWalletPatch.kt` annotations

### 3. Set up native libs
Copy `libmonerujo.so` + `libc++_shared.so` + `monerujo.aar` as described above.

### 4. Switch to mainnet for production
Edit `app/src/main/assets/wallet.properties`:
```
network.type=0
daemon.address=node.moneroworld.com
daemon.port=18089
```

---

## Privacy guarantees preserved

- Wallet files stored in `context.filesDir/wallets/<sanitized-userId>/` — on-device only.
- Wallet password generated per-user, stored in `EncryptedSharedPreferences` — never in plaintext.
- No XMR addresses or transaction data transmitted to any central server.
- Key exchange (multisig setup) travels exclusively over the existing I2P garlic-routed tunnel.
- Escrow address is a standard Monero address visible on the public blockchain,
  but is not linked to any personal identity in Kabu-Kabu's privacy model.
