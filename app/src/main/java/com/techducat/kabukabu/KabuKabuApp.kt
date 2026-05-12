package com.techducat.kabukabu

import android.app.Application
import android.content.Context
import android.util.Log
import com.bugfender.sdk.Bugfender
import com.techducat.kabukabu.network.EmbeddedI2PRouter
import com.techducat.kabukabu.service.I2PKabuService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.BUGFENDER_KEY.isNotEmpty()) {
            Bugfender.init(this, BuildConfig.BUGFENDER_KEY, BuildConfig.DEBUG)
            Bugfender.enableCrashReporting()
            Bugfender.enableLogcatLogging()
        }
        Log.i(TAG, "KabuKabuApp starting — P2P mode, I2P transport")

        // Start I2P service immediately if device is already registered
        val deviceId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_DEVICE_ID, "") ?: ""
        if (deviceId.isNotEmpty()) {
            Log.i(TAG, "Existing device ID found — starting I2PKabuService")
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
