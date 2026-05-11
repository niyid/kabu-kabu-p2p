package com.techducat.kabukabup2p.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EmbeddedI2PRouter — Kabu-Kabu P2P network transport.
 *
 * Directly adapted from buzzr-p2p/network/EmbeddedI2PRouter.kt.
 * All Buzzr-specific references replaced with Kabu-Kabu equivalents.
 *
 * What this achieves for privacy:
 *  - All peer traffic is routed through the I2P anonymity network.
 *    Neither the originating IP nor the destination IP is visible to
 *    any intermediate node (3-hop garlic routing by default).
 *  - Driver and rider devices communicate as I2P "destinations" —
 *    opaque 512-byte cryptographic identifiers with no IP binding.
 *  - Reseed servers are contacted only once at first run to bootstrap
 *    the peer table; thereafter the router uses cached peer info.
 *
 * Required artifacts in app/libs/ (see README.md):
 *   router.jar              — from i2p.i2p `ant pkg`
 *   sam.jar                 — from i2p.i2p `ant pkg`
 *   i2p-android-client.aar  — from i2p.android.base
 */
class EmbeddedI2PRouter private constructor(
    private val context:  Context,
    private val deviceId: String
) {
    companion object {
        private const val TAG               = "EmbeddedI2PRouter[KK]"
        private const val SAM_HOST          = "127.0.0.1"
        private const val SAM_PORT          = 7657   // distinct from Buzzr's 7656
        private const val I2P_CONFIG_DIR    = "i2p/kabukabup2p"
        private const val SAM_WAIT_TIMEOUT  = 600_000L
        private const val SAM_CHECK_INTERVAL = 5_000L

        private const val RESEED_URLS =
            "https://reseed-fr.i2pd.xyz/," +
            "https://reseed-pl.i2pd.xyz/," +
            "https://reseed.memcpy.io/," +
            "https://banana.incognet.io/," +
            "https://netdb.i2p2.no/," +
            "https://i2p.mooo.com/netDb/," +
            "https://reseed.onion.im/," +
            "https://reseed.i2p-projekt.de/"

        @Volatile private var _instance: EmbeddedI2PRouter? = null

        fun getInstance(context: Context, deviceId: String): EmbeddedI2PRouter =
            _instance ?: synchronized(this) {
                _instance ?: EmbeddedI2PRouter(context.applicationContext, deviceId)
                    .also { _instance = it }
            }

        fun releaseInstance() { _instance = null }
    }

    private val isRunning     = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    private val samReady      = AtomicBoolean(false)
    private val tunnelReady   = AtomicBoolean(false)

    private var routerInstance:    Any? = null
    private var samBridgeInstance: Any? = null

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun startRouter() = withContext(Dispatchers.IO) {
        if (isRunning.get()) { Log.i(TAG, "Router already running"); return@withContext }
        try {
            Log.i(TAG, "Starting embedded I2P router for Kabu-Kabu…")
            val configDir = initializeConfig()
            startI2PRouter(configDir)
            waitForSAM()
            isRunning.set(true)
            isInitialized.set(true)
            Log.i(TAG, "I2P router + SAM ready")
        } catch (e: Exception) {
            Log.e(TAG, "I2P router startup failed: ${e.message}", e)
            isRunning.set(false)
            throw IOException("I2P router startup failed: ${e.message}", e)
        }
    }

    suspend fun stopRouter() = withContext(Dispatchers.IO) {
        if (!isRunning.get()) return@withContext
        try {
            samBridgeInstance?.let { sam ->
                sam.javaClass.getMethod("stopSAMBridge").invoke(sam)
            }
            routerInstance?.let { router ->
                router.javaClass.getMethod("shutdown").invoke(router)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping router: ${e.message}")
        } finally {
            isRunning.set(false)
            isInitialized.set(false)
            samReady.set(false)
            tunnelReady.set(false)
            routerInstance    = null
            samBridgeInstance = null
        }
    }

    suspend fun awaitTunnelsReady() = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + SAM_WAIT_TIMEOUT
        while (!tunnelReady.get() && System.currentTimeMillis() < deadline) {
            delay(SAM_CHECK_INTERVAL)
            checkTunnelReady()
        }
        if (!tunnelReady.get()) throw IOException("I2P tunnels not ready within timeout")
    }

    fun isSamReady()    = samReady.get()
    fun isRouterReady() = isRunning.get() && isInitialized.get()

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun initializeConfig(): File {
        val configDir = File(context.filesDir, I2P_CONFIG_DIR)
        if (!configDir.exists()) configDir.mkdirs()

        val routerConfig = File(configDir, "router.config")
        if (!routerConfig.exists()) {
            val props = Properties().apply {
                setProperty("i2p.dir.base",    configDir.absolutePath)
                setProperty("i2p.dir.config",  configDir.absolutePath)
                setProperty("i2p.dir.router",  configDir.absolutePath)
                setProperty("i2p.dir.log",     File(configDir, "logs").absolutePath)
                setProperty("router.reseedUrls", RESEED_URLS)
                setProperty("i2p.naming.impl", "net.i2p.client.naming.HostsTxtNamingService")
                setProperty("sam.enabled",  "true")
                setProperty("sam.host",     SAM_HOST)
                setProperty("sam.port",     SAM_PORT.toString())
            }
            FileOutputStream(routerConfig).use { props.store(it, "Kabu-Kabu I2P Config") }
        }
        return configDir
    }

    private fun startI2PRouter(configDir: File) {
        try {
            val routerClass = Class.forName("net.i2p.router.Router")
            val ctor = routerClass.getConstructor(String::class.java)
            routerInstance = ctor.newInstance(configDir.absolutePath)
            routerClass.getMethod("runRouter").invoke(routerInstance)
            Log.i(TAG, "I2P Router instance started")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "router.jar not on classpath — I2P disabled (stub mode)")
        }
    }

    private suspend fun waitForSAM() = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + SAM_WAIT_TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            if (isSamPortOpen()) { samReady.set(true); return@withContext }
            delay(SAM_CHECK_INTERVAL)
        }
        throw IOException("SAM bridge not available on $SAM_HOST:$SAM_PORT within ${SAM_WAIT_TIMEOUT}ms")
    }

    private fun isSamPortOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(SAM_HOST, SAM_PORT), 3000); true }
    } catch (_: Exception) { false }

    private fun checkTunnelReady() {
        // Query router status via reflection when router.jar is present
        try {
            routerInstance?.let { router ->
                val isRunningMethod = router.javaClass.getMethod("isRunning")
                if (isRunningMethod.invoke(router) == true) tunnelReady.set(true)
            }
        } catch (_: Exception) {}
    }
}
