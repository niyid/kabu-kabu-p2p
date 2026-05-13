package com.techducat.kabukabu.network

import android.content.Context
import android.os.Looper
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
 *
 * ══ Known Android I2P pitfalls (learned from verzus-p2p) ══════════════════
 *
 * 1. i2p.dir.base / i2p.dir.config MUST be System properties set BEFORE
 *    the Router constructor runs — NOT only in router.config.  The Router
 *    constructor reads these properties from System before it parses
 *    router.config, so if they are absent the base dir defaults to the
 *    process working directory (/) which is read-only on Android (EROFS).
 *    This was the root cause of the "router.info: open failed: EROFS" loop
 *    seen in adb.log: configDir was passed as the router.config *path* string
 *    to the single-String constructor, but the base-dir System properties
 *    were never set, so the router wrote router.info to "/" instead.
 *
 * 2. setKillVMOnEnd(false) is required — Router defaults to System.exit()
 *    on shutdown, which kills the whole Android process.
 *
 * 3. runRouter() must be called on a background thread — it blocks.
 *    Wait for isAlive() / isRunning() on the calling coroutine thread.
 *
 * 4. SAMBridge NEVER starts via clients.config on Android (LoadClientAppsJob
 *    is skipped).  Start it directly after router.isRunning().
 *
 * 5. SAMBridge construction requires Looper.prepare() on its thread (Android).
 *
 * 6. Do NOT call router.shutdown() on startup failure — crashes background
 *    crypto threads.
 *
 * 7. i2p.reseedURL must be a System property — it is shadowed in router.config
 *    by the internal _overrideProps map before System.getProperty() is reached.
 */
class EmbeddedI2PRouter private constructor(
    private val context:  Context,
    private val deviceId: String
) {
    companion object {
        private const val TAG                = "EmbeddedI2PRouter[KK]"
        private const val SAM_HOST           = "127.0.0.1"
        private const val SAM_PORT           = 7656   // SAM bridge default
        private const val I2P_CONFIG_DIR     = "i2p/kabukabu"
        private const val SAM_WAIT_TIMEOUT   = 600_000L
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
            // Do NOT call router.shutdown() here — crashes background crypto threads
            throw IOException("I2P router startup failed: ${e.message}", e)
        }
    }

    suspend fun stopRouter() = withContext(Dispatchers.IO) {
        if (!isRunning.get()) return@withContext
        try {
            samBridgeInstance?.let { sam ->
                try {
                    sam.javaClass.getMethod("stopSAMBridge").invoke(sam)
                } catch (_: NoSuchMethodException) {
                    try { sam.javaClass.getMethod("shutdown").invoke(sam) } catch (_: Exception) {}
                }
            }
            routerInstance?.let { router ->
                try { router.javaClass.getMethod("shutdown").invoke(router) } catch (_: Exception) {}
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

    // ── Config ────────────────────────────────────────────────────────────────

    private fun initializeConfig(): File {
        val configDir = File(context.filesDir, I2P_CONFIG_DIR)
        if (!configDir.exists()) configDir.mkdirs()
        File(configDir, "netDb").mkdirs()

        val routerConfig = File(configDir, "router.config")
        if (!routerConfig.exists()) {
            val props = Properties().apply {
                // Transport
                setProperty("i2np.udp.port", "0")
                setProperty("i2np.ntcp.port", "0")
                setProperty("i2np.ntcp.autoip", "true")
                setProperty("i2np.udp.autoip", "true")

                // Misc
                setProperty("router.updateDisabled", "true")
                setProperty("eepget.useDNSOverHTTPS", "false")  // broken on Android
                setProperty("i2p.naming.impl", "net.i2p.client.naming.HostsTxtNamingService")

                // Reseed
                setProperty("router.reseedDisable", "false")
                setProperty("router.reseedSSLTime", "180000")
                // NOTE: i2p.reseedURL is NOT set here — must be a System property only.
                //       See startI2PRouter() for explanation.
            }
            FileOutputStream(routerConfig).use { props.store(it, "Kabu-Kabu I2P Config") }
            Log.i(TAG, "Written router.config to ${routerConfig.absolutePath}")
        }

        // hosts.txt must exist to avoid WorkingDir "Cannot find I2P installation" warnings
        val hostsTxt = File(configDir, "hosts.txt")
        if (!hostsTxt.exists()) hostsTxt.createNewFile()

        return configDir
    }

    // ── Router startup ────────────────────────────────────────────────────────

    private fun startI2PRouter(configDir: File) {
        // FIX: Set System properties BEFORE constructing the Router.
        // The Router constructor reads i2p.dir.base from System.getProperty() before
        // it parses router.config.  Without these, the base dir defaults to the process
        // working directory ("/") which is read-only on Android, causing the EROFS loop.
        System.setProperty("i2p.dir.base",    configDir.absolutePath)
        System.setProperty("i2p.dir.config",  configDir.absolutePath)
        System.setProperty("i2p.dir.router",  configDir.absolutePath)
        System.setProperty("wrapper.logfile", File(configDir, "wrapper.log").absolutePath)
        System.setProperty("i2p.naming.impl", "net.i2p.client.naming.HostsTxtNamingService")
        System.setProperty("i2p.disableSSLHostnameVerification", "true")

        // CRITICAL: reseedURL must be a System property — it is shadowed in router.config
        // by the Router's internal _overrideProps before System.getProperty() fallback.
        System.setProperty("i2p.reseedURL", RESEED_URLS)
        Log.i(TAG, "Set i2p.dir.base = ${configDir.absolutePath}")
        Log.i(TAG, "Set i2p.reseedURL = ${RESEED_URLS.take(60)}...")

        try {
            val routerClass = Class.forName("net.i2p.router.Router")
            Log.d(TAG, "I2P classes loaded")

            // Prefer the (configPath, Properties) constructor so we can pass an inline
            // override map.  Fall back to single-String or no-arg if unavailable.
            val routerConfigPath = File(configDir, "router.config").absolutePath
            val router = try {
                val ctor = routerClass.getConstructor(String::class.java, Properties::class.java)
                val overrideProps = Properties().apply {
                    setProperty("i2p.dir.base",   configDir.absolutePath)
                    setProperty("i2p.dir.config", configDir.absolutePath)
                    setProperty("i2p.naming.impl", "net.i2p.client.naming.HostsTxtNamingService")
                }
                Log.d(TAG, "Using Router(String, Properties): $routerConfigPath")
                ctor.newInstance(routerConfigPath, overrideProps)
            } catch (e: NoSuchMethodException) {
                try {
                    Log.d(TAG, "Using Router(String): $routerConfigPath")
                    routerClass.getConstructor(String::class.java).newInstance(routerConfigPath)
                } catch (e2: NoSuchMethodException) {
                    Log.d(TAG, "Using Router() no-arg")
                    routerClass.getConstructor().newInstance()
                }
            }

            routerInstance = router

            // Prevent Router from calling System.exit() on shutdown
            try {
                routerClass.getMethod("setKillVMOnEnd", Boolean::class.javaPrimitiveType)
                    .invoke(router, false)
                Log.i(TAG, "setKillVMOnEnd(false)")
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "setKillVMOnEnd not found — router may call System.exit() on shutdown")
            }

            val isAliveMethod   = routerClass.getMethod("isAlive")
            val isRunningMethod = try { routerClass.getMethod("isRunning") } catch (_: Exception) { null }

            // runRouter() blocks — must run on its own thread
            Thread({
                try {
                    Log.i(TAG, "=== Invoking runRouter() ===")
                    routerClass.getMethod("runRouter").invoke(router)
                    Log.i(TAG, "runRouter() returned")
                } catch (e: Exception) {
                    Log.e(TAG, "runRouter() threw: ${e.javaClass.name}: ${e.message}", e)
                    isRunning.set(false)
                }
            }, "I2P-Router-Start").apply { isDaemon = true; start() }

            // Wait for isAlive() — typically a few seconds
            Log.i(TAG, "Waiting for router.isAlive()...")
            var attempts = 0
            while (!(isAliveMethod.invoke(router) as Boolean) && attempts < 60) {
                Thread.sleep(1000); attempts++
                if (attempts % 10 == 0) Log.d(TAG, "isAlive wait: $attempts/60s")
            }
            if (!(isAliveMethod.invoke(router) as Boolean)) {
                throw IOException("Router did not become alive within 60s")
            }
            Log.i(TAG, "Router is alive after ${attempts}s")

            // Wait for isRunning() — may take 2–3 min on first boot during reseed
            var routerAlreadyRunning = false
            if (isRunningMethod != null) {
                Log.i(TAG, "Waiting for router.isRunning() (reseed may take 2–3 min on first boot)...")
                attempts = 0
                while (!(isRunningMethod.invoke(router) as Boolean) && attempts < 300) {
                    Thread.sleep(1000); attempts++
                    if (attempts % 30 == 0) Log.d(TAG, "isRunning wait: $attempts/300s")
                }
                routerAlreadyRunning = isRunningMethod.invoke(router) as Boolean
                if (routerAlreadyRunning) {
                    Log.i(TAG, "Router is RUNNING after ${attempts}s")
                } else {
                    Log.w(TAG, "Router did not reach isRunning() after 300s — starting SAM anyway")
                }
            } else {
                Log.w(TAG, "isRunning() not found — waiting 10s then starting SAM")
                Thread.sleep(10_000)
                routerAlreadyRunning = true
            }

            startSAMBridgeDirectly(routerClass, isRunningMethod, router, configDir, routerAlreadyRunning)

        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "router.jar not on classpath — I2P disabled (stub mode)")
        }
    }

    private fun startSAMBridgeDirectly(
        routerClass: Class<*>,
        isRunningMethod: java.lang.reflect.Method?,
        router: Any,
        configDir: File,
        routerAlreadyRunning: Boolean = false
    ) {
        val isAliveMethod = routerClass.getMethod("isAlive")

        Thread({
            Log.i(TAG, "SAM bridge thread starting")

            if (routerAlreadyRunning) {
                // Poll until router is alive + running before constructing the SAM bridge
                var attempts = 0
                while (true) {
                    val alive   = isAliveMethod.invoke(router) as Boolean
                    val running = isRunningMethod?.invoke(router) as? Boolean ?: true
                    if (!alive) { Log.e(TAG, "Router stopped before SAM could start"); return@Thread }
                    if (running) break
                    attempts++
                    if (attempts % 10 == 0) Log.d(TAG, "SAM waiting for isRunning(): ${attempts}s")
                    Thread.sleep(1000)
                }
            }

            // Check if port is already bound (leftover from a previous session)
            val samAlreadyBound = try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(SAM_HOST, SAM_PORT), 2000); true
                }
            } catch (_: Exception) { false }

            if (samAlreadyBound) {
                Log.i(TAG, "SAM port $SAM_PORT already in use — reusing existing bridge")
                samReady.set(true)
                return@Thread
            }

            try {
                try {
                    Looper.prepare()
                    Log.d(TAG, "Looper.prepare() done on SAM thread")
                } catch (_: RuntimeException) {
                    Log.d(TAG, "Looper already prepared on this thread")
                }

                val samBridgeClass = Class.forName("net.i2p.sam.SAMBridge")
                val samKeysFile    = File(configDir, "sam.keys").absolutePath
                val samConfigDir   = File(configDir, "sam_config").also { it.mkdirs() }
                val samProps       = Properties()

                val bridge = try {
                    val ctor = samBridgeClass.getConstructor(
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Properties::class.java,
                        String::class.java,
                        File::class.java,
                        Class.forName("net.i2p.sam.SAMSecureSessionInterface")
                    )
                    Log.d(TAG, "Using SAMBridge 7-arg constructor")
                    ctor.newInstance(SAM_HOST, SAM_PORT, false, samProps, samKeysFile, samConfigDir, null)
                } catch (_: NoSuchMethodException) {
                    try {
                        val ctor = samBridgeClass.getConstructor(
                            String::class.java,
                            Int::class.javaPrimitiveType,
                            Properties::class.java
                        )
                        Log.d(TAG, "Using SAMBridge 3-arg constructor")
                        ctor.newInstance(SAM_HOST, SAM_PORT, samProps)
                    } catch (_: NoSuchMethodException) {
                        Log.d(TAG, "Using SAMBridge no-arg constructor")
                        samBridgeClass.getConstructor().newInstance()
                    }
                }

                samBridgeInstance = bridge
                Log.i(TAG, "SAMBridge constructed — calling run()")
                samBridgeClass.getMethod("run").invoke(bridge)
                Log.i(TAG, "SAMBridge.run() returned")

            } catch (e: Exception) {
                Log.e(TAG, "SAMBridge failed: ${e.javaClass.name}: ${e.message}", e)
            }
        }, "I2P-SAM-Bridge").apply { isDaemon = true; start() }

        Log.i(TAG, "SAM bridge thread launched")
    }

    // ── SAM readiness ─────────────────────────────────────────────────────────

    private suspend fun waitForSAM() = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        Log.i(TAG, "Polling SAM bridge on $SAM_HOST:$SAM_PORT (up to ${SAM_WAIT_TIMEOUT / 1000}s)...")
        while (System.currentTimeMillis() - start < SAM_WAIT_TIMEOUT) {
            if (isSamPortOpen()) {
                samReady.set(true)
                Log.i(TAG, "SAM bridge ready after ${(System.currentTimeMillis() - start) / 1000}s")
                return@withContext
            }
            delay(SAM_CHECK_INTERVAL)
        }
        throw IOException("SAM bridge not available on $SAM_HOST:$SAM_PORT within ${SAM_WAIT_TIMEOUT}ms")
    }

    private fun isSamPortOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(SAM_HOST, SAM_PORT), 3000); true }
    } catch (_: Exception) { false }

    private fun checkTunnelReady() {
        try {
            routerInstance?.let { router ->
                val isRunningMethod = router.javaClass.getMethod("isRunning")
                if (isRunningMethod.invoke(router) == true) tunnelReady.set(true)
            }
        } catch (_: Exception) {}
    }
}
