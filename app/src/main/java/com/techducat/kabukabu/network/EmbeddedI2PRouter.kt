package com.techducat.kabukabu.network

import android.content.Context
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EmbeddedI2PRouter — Kabu-Kabu P2P network transport.
 *
 * Directly adapted from verzus-p2p/network/EmbeddedI2PRouter.kt.
 * All Verzus-specific references replaced with Kabu-Kabu equivalents.
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
 * ══ Known Android I2P pitfalls (ported from verzus-p2p) ══════════════════
 *
 * 1. KeyStoreUtil.loadSystemKeyStore() on Android tries /system/etc/security/cacerts.bks
 *    which does not exist on modern Android.
 *    FIX A: copy I2P's own reseed/router/ssl certs from assets/i2p/certificates/
 *           into configDir/certificates/ BEFORE the router starts.
 *    FIX B: export the Android CA store (AndroidCAStore) as PEM files into
 *           configDir/certificates/ssl/ BEFORE the router starts.
 *    Both must happen in initializeConfig() before startI2PRouter() is called.
 *    Without this step every HTTPS reseed attempt fails with:
 *    "Trust anchor for certification path not found."
 *
 * 2. i2p.dir.base / i2p.dir.config MUST be System properties set BEFORE
 *    the Router constructor runs — NOT only in router.config.
 *
 * 3. i2p.reseedURL MUST be a System property, NOT in router.config (shadowed
 *    by _overrideProps before System.getProperty() fallback is reached).
 *
 * 4. router.reseedSSLDisable=true MUST NOT be set — it filters out all HTTPS
 *    reseed URLs.
 *
 * 5. network_security_config.xml has NO EFFECT on SSLEepGet — it uses raw SSLSocket.
 *
 * 6. eepget.useDNSOverHTTPS=false in router.config — I2P's built-in DoH breaks on Android.
 *
 * 7. LoadClientAppsJob is EXPLICITLY SKIPPED on Android in StartupJob.
 *    SAMBridge NEVER starts via clients.config on Android.
 *    Fix: start SAMBridge directly after router.isRunning().
 *
 * 8. SAMBridge requires Looper.prepare() on the thread that constructs it (Android).
 *
 * 9. setKillVMOnEnd(false) is required — Router defaults to System.exit() on shutdown.
 *
 * 10. Do NOT call router.shutdown() on startup failure — crashes background crypto threads.
 *
 * 11. SAM bridge HELLO handshake — a bare TCP connect is not enough.
 *     Send "HELLO VERSION MIN=3.1 MAX=3.1" and check the reply.
 *
 * 12. Stale config detection — if router.config exists but netDb has fewer than 20
 *     router infos, wipe the directory and force a fresh reseed.
 *
 * CORRECT INITIALISATION ORDER (mirrors verzus-p2p):
 *   initializeConfig()
 *     ├─ mkdirs for certificates/, peerProfiles/, keyBackup/, netDb/
 *     ├─ copyI2PCertsFromAssets()        ← must come FIRST
 *     ├─ exportAndroidCAsToI2PDir()      ← must come SECOND (supplements above)
 *     └─ createRouterConfig()            ← must come LAST (reads nothing from disk)
 *   startI2PRouter()
 *     ├─ System.setProperty(i2p.dir.base, …)
 *     ├─ System.setProperty(i2p.reseedURL, …)
 *     ├─ Router(configPath, props)
 *     ├─ setKillVMOnEnd(false)
 *     ├─ runRouter() on background thread
 *     ├─ wait isAlive() ≤ 60 s
 *     ├─ wait isRunning() ≤ 300 s
 *     └─ startSAMBridgeDirectly()
 *   waitForSAM()   — HELLO handshake probe
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

        private const val NAMING_IMPL_PROP   = "i2p.naming.impl"
        private const val HOSTS_TXT_NAMING   = "net.i2p.client.naming.HostsTxtNamingService"

        // Reseed servers — ordered by observed reliability (mirrors verzus-p2p ordering).
        // reseed.i2p-projekt.de is kept last: it timed out in production logs (ETIMEDOUT).
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

        /** Sanitise deviceId so it is safe to embed in a file-system path. */
        internal fun sanitizeDeviceId(deviceId: String): String =
            deviceId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
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
            isRunning.set(false)
            samReady.set(false)
            tunnelReady.set(false)
            isInitialized.set(false)
            cleanup()
            Log.i(TAG, "I2P router stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping router: ${e.message}")
        }
    }

    /**
     * Wait until a full SAM SESSION CREATE with a TRANSIENT destination succeeds,
     * meaning exploratory tunnels are genuinely up.  This is a stronger signal than
     * [isSamReady] (which only confirms the TCP port is open).
     */
    suspend fun awaitTunnelsReady(
        timeoutMs: Long = SAM_WAIT_TIMEOUT,
        checkIntervalMs: Long = SAM_CHECK_INTERVAL
    ) = withContext(Dispatchers.IO) {
        if (tunnelReady.get()) return@withContext
        val deadline = System.currentTimeMillis() + timeoutMs
        Log.i(TAG, "Waiting for I2P tunnel readiness (SESSION CREATE probe, up to ${timeoutMs / 1000}s)…")
        while (!tunnelReady.get() && System.currentTimeMillis() < deadline) {
            if (testTunnelReady()) {
                tunnelReady.set(true)
                Log.i(TAG, "I2P tunnels ready (SESSION CREATE probe succeeded)")
                return@withContext
            }
            delay(checkIntervalMs)
        }
        if (!tunnelReady.get()) Log.w(TAG, "Tunnels not ready within ${timeoutMs / 1000}s")
    }

    fun isSamReady()    = samReady.get()
    fun isTunnelReady() = tunnelReady.get()
    fun isRouterReady() = isRunning.get() && isInitialized.get()

    fun getConfigDir(): File =
        File(context.filesDir, "$I2P_CONFIG_DIR/${sanitizeDeviceId(deviceId)}")

    // ── Config ────────────────────────────────────────────────────────────────

    private fun initializeConfig(): File {
        val configDir = File(context.filesDir, I2P_CONFIG_DIR)

        // Stale-config detection (mirrors verzus-p2p logic):
        // If router.config exists but netDb has fewer than 20 router infos the
        // previous reseed failed partially.  Wipe and force a clean reseed.
        val routerConfigExists = File(configDir, "router.config").exists()
        val netDbInfoCount = File(configDir, "netDb")
            .listFiles { f -> f.name.startsWith("routerInfo-") }
            ?.size ?: 0
        val isStaleConfig = routerConfigExists && netDbInfoCount < 20
        val isFirstBoot   = !routerConfigExists || isStaleConfig

        if (isFirstBoot) {
            if (isStaleConfig) {
                Log.w(TAG, "Stale config — netDb has only $netDbInfoCount router infos. Wiping for clean reseed.")
            } else {
                Log.i(TAG, "First boot — creating clean I2P configuration")
            }
            if (configDir.exists()) configDir.deleteRecursively()
        }

        configDir.mkdirs()
        File(configDir, "certificates").mkdirs()
        File(configDir, "peerProfiles").mkdirs()
        File(configDir, "keyBackup").mkdirs()
        File(configDir, "netDb").mkdirs()

        // CORRECT ORDER (must match verzus-p2p):
        // 1. Copy I2P's own certs from assets — provides reseed/router CA material.
        // 2. Export Android CA store — provides HTTPS trust anchors for SSLEepGet.
        //    Without step 2, every reseed attempt fails:
        //    "Trust anchor for certification path not found."
        // 3. Write router.config last (reads nothing from disk).
        copyI2PCertsFromAssets(configDir)
        exportAndroidCAsToI2PDir(configDir)
        createRouterConfig(configDir)

        // hosts.txt must exist to avoid WorkingDir "Cannot find I2P installation" warnings
        val hostsTxt = File(configDir, "hosts.txt")
        if (!hostsTxt.exists()) hostsTxt.createNewFile()

        Log.i(TAG, "I2P config initialised (firstBoot=$isFirstBoot, netDbInfos=$netDbInfoCount)")
        return configDir
    }

    private fun createRouterConfig(configDir: File) {
        val props = Properties().apply {
            // Transport
            setProperty("i2np.udp.port",     "0")
            setProperty("i2np.ntcp.port",    "0")
            setProperty("i2np.ntcp.autoip",  "true")
            setProperty("i2np.udp.autoip",   "true")
            setProperty("i2np.udp.ipv6",     "auto")
            setProperty("i2np.ntcp.ipv6",    "auto")

            // Bandwidth
            setProperty("router.sharePercentage",                    "50")
            setProperty("i2np.bandwidth.inboundKBytesPerSecond",     "100")
            setProperty("i2np.bandwidth.outboundKBytesPerSecond",    "100")

            // Misc
            setProperty("router.updateDisabled",          "true")
            setProperty("router.updateUnsigned",          "false")
            setProperty("i2np.laptop.preferredPlatform",  "true")
            setProperty("router.hiddenMode",              "false")

            // Disable I2P's built-in DNS-over-HTTPS — broken SSLContext on Android.
            setProperty("eepget.useDNSOverHTTPS", "false")

            // Logging
            setProperty("logger.defaultLevel",                                   "DEBUG")
            setProperty("logger.fileSize",                                        "2M")
            setProperty("logger.record.net.i2p.router.networkdb.reseed",         "DEBUG")
            setProperty("logger.record.net.i2p.util.EepGet",                     "DEBUG")
            setProperty("logger.record.net.i2p.router.networkdb.reseed.Reseeder","DEBUG")
            setProperty("logger.record.net.i2p.router.Router",                   "DEBUG")

            // Naming
            setProperty(NAMING_IMPL_PROP, HOSTS_TXT_NAMING)

            // Reseed
            setProperty("router.reseedDisable",  "false")
            setProperty("router.reseedSSLTime",  "180000")  // 3-min timeout
            // IMPORTANT: i2p.reseedURL is NOT set here — must be a System property only.
            // See startI2PRouter() for explanation.
        }

        FileOutputStream(File(configDir, "router.config")).use {
            props.store(it, "Kabu-Kabu I2P Router Configuration")
        }
        Log.i(TAG, "Written router.config with ${props.size} properties")
    }

    // ── Certificate setup (ported from verzus-p2p) ────────────────────────────

    /**
     * Copy I2P's bundled CA certificates from assets/i2p/certificates/{reseed,router,ssl}/
     * into configDir/certificates/{reseed,router,ssl}/.
     *
     * These certs authenticate I2P reseed servers and router updates.
     * They must be present BEFORE the router starts, otherwise KeyStoreUtil
     * logs "All key store loads failed, will only load local certificates"
     * and the reseed SSL handshake fails.
     *
     * Assets must be populated from the i2p.i2p source tree:
     *   installer/resources/certificates/{reseed,router,ssl}/
     */
    private fun copyI2PCertsFromAssets(configDir: File) {
        val subdirs = listOf("reseed", "router", "ssl")
        var totalCopied = 0

        for (subdir in subdirs) {
            val assetDir = "i2p/certificates/$subdir"
            val destDir  = File(configDir, "certificates/$subdir")

            val assetFiles = try {
                context.assets.list(assetDir) ?: emptyArray()
            } catch (e: Exception) {
                Log.w(TAG, "Asset dir not found: $assetDir — ${e.message}")
                continue
            }

            if (assetFiles.isEmpty()) {
                Log.w(TAG, "No files in asset dir: $assetDir")
                continue
            }

            destDir.mkdirs()
            var copied = 0

            for (filename in assetFiles) {
                val dest = File(destDir, filename)
                if (dest.exists()) continue
                try {
                    context.assets.open("$assetDir/$filename").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    copied++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to copy $assetDir/$filename: ${e.message}")
                }
            }

            Log.i(TAG, "Copied $copied/${assetFiles.size} certs from assets/$assetDir")
            totalCopied += copied
        }

        Log.i(TAG, "Total I2P certs copied from assets: $totalCopied")
    }

    /**
     * Export the Android CA store (AndroidCAStore) as PEM files into
     * configDir/certificates/ssl/.
     *
     * I2P's SSLEepGet uses a raw SSLSocket backed by KeyStoreUtil, which on Android
     * tries to load /system/etc/security/cacerts.bks — a path that does not exist
     * on modern Android.  Exporting the CAs as PEM files into the I2P ssl dir gives
     * KeyStoreUtil the trust anchors it needs to verify HTTPS reseed servers.
     *
     * Without this step every reseed attempt fails with:
     *   "Trust anchor for certification path not found."
     *
     * The threshold of > 10 files distinguishes "fully exported" (≈ 130+ CAs from
     * the Android store) from "only I2P's 3 bundled ssl/ certs" which copyI2PCertsFromAssets
     * may have already placed there.
     */
    private fun exportAndroidCAsToI2PDir(configDir: File) {
        val sslCertsDir = File(configDir, "certificates/ssl")

        val existingCount = sslCertsDir.listFiles()?.size ?: 0
        if (existingCount > 10) {
            Log.i(TAG, "CA certs already exported: $existingCount files in certificates/ssl/")
            return
        }

        sslCertsDir.mkdirs()
        Log.i(TAG, "Exporting Android CA store -> ${sslCertsDir.absolutePath}")

        var exported = 0
        var skipped  = 0

        try {
            val androidCaStore = KeyStore.getInstance("AndroidCAStore")
            androidCaStore.load(null, null)

            val aliases = androidCaStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                try {
                    val cert = androidCaStore.getCertificate(alias) as? X509Certificate
                    if (cert == null) { skipped++; continue }

                    val safeName = alias
                        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        .take(80)

                    File(sslCertsDir, "$safeName.pem").bufferedWriter().use { out ->
                        out.write("-----BEGIN CERTIFICATE-----\n")
                        Base64.getEncoder()
                            .encodeToString(cert.encoded)
                            .chunked(64)
                            .forEach { line -> out.write(line); out.write("\n") }
                        out.write("-----END CERTIFICATE-----\n")
                    }
                    exported++
                } catch (e: Exception) {
                    Log.w(TAG, "Skipped cert '$alias': ${e.message}")
                    skipped++
                }
            }

            Log.i(TAG, "Exported $exported Android CA certs to certificates/ssl/ ($skipped skipped)")
            if (exported == 0) {
                Log.e(TAG, "Zero certs exported — AndroidCAStore empty or inaccessible")
            }

        } catch (e: Exception) {
            Log.e(TAG, "AndroidCAStore access failed: ${e.message}", e)
        }
    }

    // ── Router startup ────────────────────────────────────────────────────────

    private fun startI2PRouter(configDir: File) {
        // Set System properties BEFORE constructing the Router.
        // The Router constructor reads i2p.dir.base from System.getProperty() before
        // it parses router.config.  Without these, the base dir defaults to the process
        // working directory ("/") which is read-only on Android (EROFS).
        System.setProperty("i2p.dir.base",    configDir.absolutePath)
        System.setProperty("i2p.dir.config",  configDir.absolutePath)
        System.setProperty("i2p.dir.router",  configDir.absolutePath)
        System.setProperty("wrapper.logfile", File(configDir, "wrapper.log").absolutePath)
        System.setProperty(NAMING_IMPL_PROP,  HOSTS_TXT_NAMING)
        System.setProperty("i2p.disableSSLHostnameVerification", "true")

        // CRITICAL: reseedURL must be a System property — it is shadowed in router.config
        // by the Router's internal _overrideProps before System.getProperty() fallback.
        System.setProperty("i2p.reseedURL", RESEED_URLS)
        Log.i(TAG, "Set i2p.dir.base = ${configDir.absolutePath}")
        Log.i(TAG, "Set i2p.reseedURL = ${RESEED_URLS.take(60)}…")

        try {
            val routerClass = Class.forName("net.i2p.router.Router")
            Log.d(TAG, "I2P classes loaded")

            val routerConfigPath = File(configDir, "router.config").absolutePath
            val router = try {
                val ctor = routerClass.getConstructor(String::class.java, Properties::class.java)
                val overrideProps = Properties().apply {
                    setProperty("i2p.dir.base",   configDir.absolutePath)
                    setProperty("i2p.dir.config", configDir.absolutePath)
                    setProperty(NAMING_IMPL_PROP, HOSTS_TXT_NAMING)
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

            try {
                routerClass.getMethod("setKillVMOnEnd", Boolean::class.javaPrimitiveType)
                    .invoke(router, false)
                Log.i(TAG, "setKillVMOnEnd(false)")
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "setKillVMOnEnd not found — router may call System.exit() on shutdown")
            }

            val isAliveMethod   = routerClass.getMethod("isAlive")
            val isRunningMethod = try { routerClass.getMethod("isRunning") } catch (_: Exception) { null }

            startWrapperLogMonitor(configDir)

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

            Log.i(TAG, "Waiting for router.isAlive()…")
            var attempts = 0
            while (!(isAliveMethod.invoke(router) as Boolean) && attempts < 60) {
                Thread.sleep(1000); attempts++
                if (attempts % 10 == 0) Log.d(TAG, "isAlive wait: $attempts/60s")
            }
            if (!(isAliveMethod.invoke(router) as Boolean)) {
                throw IOException("Router did not become alive within 60s")
            }
            Log.i(TAG, "Router is alive after ${attempts}s")

            var routerAlreadyRunning = false
            if (isRunningMethod != null) {
                Log.i(TAG, "Waiting for router.isRunning() (reseed may take 2–3 min on first boot)…")
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
        Log.i(TAG, "Polling SAM bridge on $SAM_HOST:$SAM_PORT (up to ${SAM_WAIT_TIMEOUT / 1000}s)…")
        while (System.currentTimeMillis() - start < SAM_WAIT_TIMEOUT) {
            if (testSAMConnection()) {
                samReady.set(true)
                Log.i(TAG, "SAM bridge ready after ${(System.currentTimeMillis() - start) / 1000}s")
                return@withContext
            }
            delay(SAM_CHECK_INTERVAL)
        }
        throw IOException("SAM bridge not available on $SAM_HOST:$SAM_PORT within ${SAM_WAIT_TIMEOUT / 1000}s")
    }

    private fun testSAMConnection(): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(SAM_HOST, SAM_PORT), 5000)
            val out = java.io.PrintWriter(socket.getOutputStream(), true)
            val inp = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))
            out.println("HELLO VERSION MIN=3.1 MAX=3.1")
            val response = inp.readLine()
            val ok = response?.startsWith("HELLO REPLY RESULT=OK") == true
            if (ok) Log.d(TAG, "SAM replied: $response")
            ok
        }
    } catch (e: Exception) {
        Log.d(TAG, "SAM not yet ready: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun testTunnelReady(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(SAM_HOST, SAM_PORT), 5000)
                socket.soTimeout = 90_000
                val out = java.io.PrintWriter(socket.getOutputStream(), true)
                val inp = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))

                out.println("HELLO VERSION MIN=3.1 MAX=3.1")
                val hello = inp.readLine()
                if (hello?.startsWith("HELLO REPLY RESULT=OK") != true) return false

                val probeId = "kk_probe_${System.currentTimeMillis()}"
                out.println(
                    "SESSION CREATE STYLE=STREAM ID=$probeId " +
                    "DESTINATION=TRANSIENT SIGNATURE_TYPE=EdDSA_SHA512_Ed25519"
                )
                val sessionReply = inp.readLine()
                val ok = sessionReply?.startsWith("SESSION STATUS RESULT=OK") == true
                if (ok) {
                    Log.d(TAG, "Tunnel probe OK: $sessionReply")
                    // Probe session is TRANSIENT — SAM bridge removes it when socket closes.
                    // Do NOT send SESSION REMOVE — SAM v3.1 has no such command.
                } else {
                    Log.d(TAG, "Tunnel probe not ready: $sessionReply")
                }
                ok
            }
        } catch (e: Exception) {
            Log.d(TAG, "Tunnel probe failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun startWrapperLogMonitor(configDir: File) {
        val wrapperLog = File(configDir, "wrapper.log")
        Thread({
            try {
                Thread.sleep(2000)
                Log.i(TAG, "Monitoring wrapper.log…")
                var lastPosition = 0L
                while (!isInitialized.get() || isRunning.get()) {
                    if (wrapperLog.exists() && wrapperLog.length() > lastPosition) {
                        java.io.RandomAccessFile(wrapperLog, "r").use { raf ->
                            raf.seek(lastPosition)
                            var line = raf.readLine()
                            while (line != null) {
                                Log.d("I2P-wrapper.log", line)
                                line = raf.readLine()
                            }
                            lastPosition = raf.filePointer
                        }
                    }
                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "wrapper.log monitor error: ${e.message}")
            }
        }, "I2P-Log-Monitor").apply { isDaemon = true; start() }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun cleanup() {
        val bridge = samBridgeInstance
        val router = routerInstance
        samBridgeInstance = null
        routerInstance    = null

        bridge?.let {
            try {
                val samClass = Class.forName("net.i2p.sam.SAMBridge")
                val shutdownMethod = try {
                    samClass.getMethod("shutdown")
                } catch (_: NoSuchMethodException) {
                    try { samClass.getMethod("stopRunning") } catch (_: NoSuchMethodException) { null }
                }
                if (shutdownMethod != null) {
                    shutdownMethod.invoke(it)
                    Log.i(TAG, "SAMBridge shutdown OK")
                } else {
                    Log.w(TAG, "SAMBridge: no known shutdown method found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "SAMBridge shutdown error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        router?.let {
            try {
                Class.forName("net.i2p.router.Router")
                    .getMethod("shutdown", Int::class.javaPrimitiveType)
                    .invoke(it, 0)
                Log.i(TAG, "Router shutdown initiated")
            } catch (e: Exception) {
                Log.w(TAG, "Router shutdown error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
