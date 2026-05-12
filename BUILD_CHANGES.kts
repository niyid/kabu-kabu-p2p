// ============================================================================
//  build.gradle.kts — XMR wallet dependency additions for Kabu-Kabu
//
//  Merge the lines below into app/build.gradle.kts at the positions
//  indicated.  Lines marked with (+) are new; all others are context.
// ============================================================================

// ── SECTION: android { packaging { resources { excludes ─────────────────────
// Add inside the existing excludes set:

//  (+) "META-INF/INDEX.LIST",
//  (+) "META-INF/*.kotlin_module",
//  (+) "**/libmonerujo.so.sha256",   // duplicate checksum sidecars from Monerujo
//  (+) "**/*.proto"

// ── SECTION: android { packagingOptions ─────────────────────────────────────
// Add inside android {} block:

//  (+) packaging {
//  (+)     jniLibs {
//  (+)         pickFirsts += "**/libmonerujo.so"
//  (+)         pickFirsts += "**/libc++_shared.so"
//  (+)     }
//  (+) }

// ── SECTION: dependencies {} ────────────────────────────────────────────────
// Add after the existing Room dependencies:

dependencies {

    // ── (existing deps kept) ─────────────────────────────────────────────

    // ── Monero / Monerujo wallet (XMR embedded wallet) ───────────────────
    // The Monerujo AAR bundles the Monero JNI bindings.
    // Build it from https://github.com/m2049r/xmrwallet or extract the
    // .so files from a verified Monerujo release APK.
    //
    // Place the following in app/libs/ (see app/libs/README.md):
    //   monerujo.aar          ← the Monerujo Android library
    //
    // And in app/src/main/jniLibs/<abi>/ for each ABI you support:
    //   libmonerujo.so
    //   libc++_shared.so
    //
    // Then uncomment:
    // (+) if (file("libs/monerujo.aar").exists()) {
    // (+)     add("implementation", files("libs/monerujo.aar"))
    // (+) } else {
    // (+)     logger.warn("⚠️  libs/monerujo.aar not found — XMR wallet disabled. See app/libs/README.md")
    // (+) }

    // ── If you are already building Verzus in the same workspace ──────────
    // You can share the monerujo.aar via a composite build or by symlinking:
    //
    //   ln -s ../verzus-p2p/app/libs/monerujo.aar app/libs/monerujo.aar
    //   ln -s ../verzus-p2p/app/src/main/jniLibs  app/src/main/jniLibs

    // ── Encrypted storage for per-user wallet passwords ───────────────────
    // Already in Kabu-Kabu build — verify it's present:
    // implementation("androidx.security:security-crypto:1.1.0-alpha06")
}

// ── SECTION: jniLibs directory layout ───────────────────────────────────────
//
//  app/src/main/jniLibs/
//    arm64-v8a/
//      libmonerujo.so
//      libc++_shared.so
//    armeabi-v7a/
//      libmonerujo.so
//      libc++_shared.so
//    x86_64/
//      libmonerujo.so          ← for emulator testing
//      libc++_shared.so
//
//  These are the same .so files used by Verzus.  If both apps are in the
//  same repo/workspace, symlink rather than duplicate.

// ── SECTION: AndroidManifest.xml additions ───────────────────────────────────
//
//  Add inside <application>:
//
//  (+) <activity
//  (+)     android:name=".wallet.WalletActivity"
//  (+)     android:label="XMR Wallet"
//  (+)     android:exported="false"
//  (+)     android:windowSoftInputMode="adjustResize"/>
//
//  No new permissions needed — the wallet communicates with the daemon
//  via the existing INTERNET permission already declared for I2P.
