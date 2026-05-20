package com.techducat.kabukabu

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.techducat.kabukabu.network.EmbeddedI2PRouter
import com.techducat.kabukabu.service.I2PKabuService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * KabuKabuApp — Privacy-first P2P taxi & courier application.
 *
 * Privacy guarantees enforced at the application layer:
 *  1. No central server receives location data — the I2P router
 *     ensures all peer traffic is anonymised at the network layer.
 *  2. Exact GPS coordinates are NEVER persisted or transmitted.
 *     Only GeoHash-5 fuzzy cells (~5 km radius) are used for matching.
 *  3. Device identity is a SHA-256 hash of the phone number; the
 *     plaintext number is never sent over the network.
 *  4. All trip records are stored exclusively in the on-device Room DB.
 *
 * Architecture mirrors buzzr-p2p:
 *   I2P embedded router (EmbeddedI2PRouter)
 *     → Local TCP bridge on 127.0.0.1:8881 (I2PKabuService)
 *       → I2PKabuClient (MainActivity / DriverActivity)
 */
@HiltAndroidApp
class KabuKabuApp : Application() {

    companion object {
        private const val TAG         = "KabuKabuApp"
        const val PREFS_NAME          = "com.techducat.kabukabu.Prefs"
        const val KEY_DEVICE_ID       = "key_device_id"
        const val KEY_ROLE            = "key_role"          // "rider" | "driver" | "courier"
        const val KEY_POLICY_ACCEPTED = "key_policy_accepted"
    }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // In release builds, plant a concise tree that only logs W+ to logcat
            // and forwards errors to Firebase Crashlytics.
            // NOTE: FirebaseCrashlytics.getInstance() must NOT be called here — Firebase
            // is not initialized until onCreate(). Use lazy so the first access happens
            // after FirebaseApp.initializeApp() has run.
            Timber.plant(object : Timber.Tree() {
                private val crashlytics by lazy { FirebaseCrashlytics.getInstance() }

                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (priority < Log.WARN) return
                    Log.println(priority, tag ?: "KabuKabuApp", message)
                    // Log non-fatal issues and exceptions to Crashlytics
                    crashlytics.log("[$tag] $message")
                    if (t != null) {
                        if (priority >= Log.ERROR) {
                            crashlytics.recordException(t)
                        }
                    }
                }
            })
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase (required before any Firebase service, including Crashlytics)
        FirebaseApp.initializeApp(this)
        // Enable Crashlytics crash collection (disabled in debug builds to reduce noise)
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG

        Timber.tag(TAG).i("KabuKabuApp starting — P2P mode, I2P transport")

        // Start I2P service immediately if device is already registered
        val deviceId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_DEVICE_ID, "") ?: ""
        if (deviceId.isNotEmpty()) {
            Timber.tag(TAG).i("Existing device ID found — starting I2PKabuService")
            I2PKabuService.startService(this)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        val deviceId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_DEVICE_ID, "") ?: ""
        if (deviceId.isNotEmpty()) {
            val router = EmbeddedI2PRouter.getInstance(this@KabuKabuApp, deviceId)
            applicationScope.launch(Dispatchers.IO) {
                try { router.stopRouter() } catch (_: Exception) {}
            }
        }
        EmbeddedI2PRouter.releaseInstance()
    }
}
