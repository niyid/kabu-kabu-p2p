package com.techducat.kabukabu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.techducat.kabukabu.BuildConfig
import com.techducat.kabukabu.KabuKabuApp
import com.techducat.kabukabu.R
import com.techducat.kabukabu.db.KabuDatabase
import com.techducat.kabukabu.db.TripDao
import com.techducat.kabukabu.db.TripEntity
import com.techducat.kabukabu.network.EmbeddedI2PRouter
import com.techducat.kabukabu.wallet.RideWalletManager
import com.techducat.kabukabu.wallet.WalletSuite
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * I2PKabuService — privacy-first foreground service for Kabu-Kabu P2P.
 *
 * WHAT THIS SERVICE DOES:
 *  1. Starts the embedded I2P router so all peer traffic is anonymised.
 *  2. Binds a local TCP server on 127.0.0.1:8881 for the UI (I2PKabuClient).
 *  3. Routes ride requests, driver offers, trip events, and XMR wallet
 *     messages between the I2P network and the local UI client.
 *  4. Persists trips in the on-device Room database (TripDao).
 *  5. Handles 2-of-2 multisig escrow message flows on behalf of both
 *     rider and driver devices.
 */
class I2PKabuService : LifecycleService() {

    companion object {
        private const val TAG              = "I2PKabuService"
        private const val SERVICE_CHANNEL  = "I2PKabuServiceChannel"
        private const val LOCAL_PORT       = BuildConfig.I2P_SERVICE_PORT
        private const val CLEANUP_INTERVAL = 2 * 60 * 60 * 1000L
        private const val PEER_SYNC_WINDOW = 24 * 60 * 60 * 1000L
        private const val MAX_ACTIVE_REQUESTS = 200

        // ── XMR wallet message types ──────────────────────────────────────────
        // ── I2P network state message type ────────────────────────────────────
        // Sent to local UI clients (I2PKabuClient) whenever the router state changes.
        const val MSG_TYPE_I2P_STATE         = "i2p_state"
        const val I2P_STATE_BOOTSTRAPPING    = "bootstrapping"    // router starting, reseeding
        const val I2P_STATE_BUILDING_TUNNELS = "building_tunnels" // SAM up, tunnels pending
        const val I2P_STATE_READY            = "ready"            // tunnels confirmed up
        const val I2P_STATE_ERROR            = "error"            // startup failed

        // ── XMR wallet message types ──────────────────────────────────────────
        const val MSG_TYPE_WALLET_MULTISIG_INFO = "wallet_multisig_info"
        const val MSG_TYPE_WALLET_ESCROW_READY  = "wallet_escrow_ready"
        const val MSG_TYPE_WALLET_PARTIAL_TX    = "wallet_partial_tx"
        const val MSG_TYPE_WALLET_TX_CONFIRMED  = "wallet_tx_confirmed"
        const val MSG_TYPE_WALLET_REFUND_REQ    = "wallet_refund_req"

        @Volatile private var instance: I2PKabuService? = null
        fun getInstance(): I2PKabuService? = instance

        fun startService(context: Context) {
            val intent = Intent(context, I2PKabuService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun isRunning(context: Context): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", LOCAL_PORT), 1000); true }
        } catch (_: Exception) { false }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var tripDao: TripDao
    private var embeddedRouter: EmbeddedI2PRouter? = null
    private var deviceId: String = ""

    private var serverSocket: ServerSocket? = null
    private val localClients = CopyOnWriteArrayList<ClientSession>()
    private var serverJob: Job? = null

    private val activeRequests = ConcurrentHashMap<String, JSONObject>()
    private val peerRegistry   = ConcurrentHashMap<String, PeerRecord>()

    // ── XMR wallet ────────────────────────────────────────────────────────────

    private lateinit var rideWalletManager: RideWalletManager

    private var wakeLock: PowerManager.WakeLock? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        tripDao  = KabuDatabase.getInstance(applicationContext).tripDao()
        deviceId = getSharedPreferences(KabuKabuApp.PREFS_NAME, MODE_PRIVATE)
            .getString(KabuKabuApp.KEY_DEVICE_ID, "") ?: ""

        rideWalletManager = RideWalletManager(this)
        // FIX 2: do NOT call initializeWallet() here.
        // Wallet lifecycle belongs to the UI layer (MainActivity / WalletActivity).
        // Calling it here races with MainActivity.initWallet() — both land on the same
        // single-threaded executor within milliseconds, both fail (when walletPassword was
        // null), and the second attempt never re-runs because isInitialized stays false.
        // The service uses WalletSuite only after the wallet is already open (via the
        // coSignAndBroadcast / prepareEscrow calls that arrive through I2P messages).

        Log.i(TAG, "I2PKabuService starting (P2P mode)…")
        acquireWakeLock()
        startForeground(2, buildNotification())
        startLocalServer()
        startCleanupTask()
        startI2PRouter()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serverJob?.cancel()
        localClients.forEach { it.close() }
        localClients.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        lifecycleScope.launch(Dispatchers.IO) {
            try { embeddedRouter?.stopRouter() } catch (_: Exception) {}
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        Log.i(TAG, "I2PKabuService stopped")
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                SERVICE_CHANNEL, getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }
        return NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.service_channel_name))
            .setContentText(getString(R.string.service_channel_content))
            .setSmallIcon(R.drawable.ic_notification)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KabuKabuApp:I2PKabuWakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    // ── I2P Router ────────────────────────────────────────────────────────────

    private fun startI2PRouter() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val router = EmbeddedI2PRouter.getInstance(applicationContext, deviceId)
                embeddedRouter = router

                // Notify UI that I2P is bootstrapping
                broadcastI2PState(I2P_STATE_BOOTSTRAPPING)

                router.startRouter()
                Log.i(TAG, "I2P router started; waiting for tunnels…")

                // SAM is up — router is alive but tunnels may not be ready yet
                broadcastI2PState(I2P_STATE_BUILDING_TUNNELS)

                router.awaitTunnelsReady()
                Log.i(TAG, "I2P tunnels ready — Kabu-Kabu P2P transport active")

                broadcastI2PState(I2P_STATE_READY)
                wireI2PReceiveListener()
            } catch (e: Exception) {
                Log.e(TAG, "I2P router startup failed — loopback-only mode", e)
                broadcastI2PState(I2P_STATE_ERROR)
            }
        }
    }

    /**
     * Broadcast an I2P network state change to all connected local UI clients.
     * The UI listens for messages of type [MSG_TYPE_I2P_STATE] and updates the
     * status indicator accordingly.
     */
    private fun broadcastI2PState(state: String) {
        val msg = JSONObject().apply {
            put("type",  MSG_TYPE_I2P_STATE)
            put("state", state)
        }
        broadcastToLocalClients(msg.toString())
        Log.i(TAG, "I2P state → $state")
    }

    private fun wireI2PReceiveListener() {
        try {
            val cls         = Class.forName("com.techducat.kabukabu.network.I2PTransport")
            val getInstance = cls.getMethod("getInstance", Context::class.java, String::class.java)
            val transport   = getInstance.invoke(null, applicationContext, deviceId)
            val setListener = cls.getMethod("setReceiveListener", Function1::class.java)
            val listener: (ByteArray) -> Unit = { bytes -> handleI2PIncoming(bytes) }
            setListener.invoke(transport, listener)
            Log.i(TAG, "I2P receive listener wired")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "I2PTransport not on classpath (AAR absent) — inbound I2P disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wire I2P receive listener", e)
        }
    }

    private fun handleI2PIncoming(bytes: ByteArray) {
        try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            localClients.forEach { it.send(json.toString()) }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid incoming I2P message: ${e.message}")
        }
    }

    // ── Local TCP server ──────────────────────────────────────────────────────

    private fun startLocalServer() {
        serverJob = lifecycleScope.launch(Dispatchers.IO) {
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    serverSocket?.close()
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress("127.0.0.1", LOCAL_PORT))
                    }
                    backoffMs = 1_000L
                    Log.i(TAG, "Local API server listening on 127.0.0.1:$LOCAL_PORT")
                    while (isActive) {
                        try {
                            val socket = serverSocket!!.accept()
                            handleLocalClient(socket)
                        } catch (e: Exception) {
                            if (isActive) Log.e(TAG, "Accept error", e)
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.e(TAG, "Local server error — retrying in ${backoffMs}ms", e)
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private fun handleLocalClient(socket: Socket) {
        lifecycleScope.launch(Dispatchers.IO) {
            val session = ClientSession(socket)
            localClients.add(session)
            try {
                val reader = socket.getInputStream().bufferedReader()
                while (isActive && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        try { processLocalMessage(JSONObject(line), session) }
                        catch (e: Exception) { Log.w(TAG, "Local msg parse error: ${e.message}") }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Client disconnected: ${e.message}")
            } finally {
                localClients.remove(session)
                session.close()
                Log.i(TAG, "Local client removed; ${localClients.size} remaining")
            }
        }
    }

    private suspend fun processLocalMessage(json: JSONObject, session: ClientSession) {
        when (val type = json.optString("type")) {

            "handshake" -> {
                val deviceId = json.getString("device_id")
                val geohash  = json.getString("geohash")
                val role     = json.optString("role", "rider")
                peerRegistry[deviceId] = PeerRecord(deviceId, geohash, role, System.currentTimeMillis())
                session.deviceId = deviceId
                session.send(JSONObject().apply {
                    put("type",    "handshake_ack")
                    put("status",  "ok")
                    put("version", "1.0")
                }.toString())
                pushRelevantRequests(geohash, session)
                Log.i(TAG, "Local client handshake: $deviceId @ $geohash ($role)")
            }

            "ride_request" -> {
                val requestId = json.getString("request_id")
                json.put("_received_ms", System.currentTimeMillis())
                // Bug 16: enforce capacity cap — evict oldest entry when full
                if (activeRequests.size >= MAX_ACTIVE_REQUESTS) {
                    activeRequests.entries
                        .minByOrNull { it.value.optLong("_received_ms", 0L) }
                        ?.key
                        ?.let { activeRequests.remove(it) }
                }
                activeRequests[requestId] = json
                // Bug 22: rides from the local client originate on this device
                val localRole = session.deviceId?.let { peerRegistry[it]?.role } ?: "rider"
                persistTripRecord(json, localRole, isLocalOrigin = true)
                fanOutToLocalClients(json, excludeSession = session)
                sendOverI2P(json)
                Log.i(TAG, "Ride request fanned out: $requestId")
            }

            "driver_offer", "offer_accept", "offer_reject", "trip_event", "peer_review" -> {
                fanOutToLocalClients(json, excludeSession = session)
                sendOverI2P(json)
            }

            "location_update" -> {
                val id         = json.optString("device_id")
                val newGeohash = json.optString("new_geohash")
                peerRegistry[id]?.let { rec ->
                    peerRegistry[id] = rec.copy(geohash = newGeohash, lastSeen = System.currentTimeMillis())
                }
                fanOutToLocalClients(json, excludeSession = session)
                sendOverI2P(json)
            }

            "peer_list_request" -> {
                val geohash   = json.optString("geohash")
                val peerArray = JSONArray()
                peerRegistry.values
                    .filter { it.geohash.startsWith(geohash.take(4)) }
                    .forEach { rec ->
                        peerArray.put(JSONObject().apply {
                            put("peer_id", rec.deviceId)
                            put("role",    rec.role)
                            put("geohash", rec.geohash)
                        })
                    }
                session.send(JSONObject().apply {
                    put("type",  "peer_list")
                    put("peers", peerArray)
                }.toString())
            }

            "heartbeat" -> {
                session.send(JSONObject().apply {
                    put("type",      "heartbeat_ack")
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }

            "disconnect" -> {
                session.deviceId?.let { peerRegistry.remove(it) }
            }

            // ── XMR wallet message types ──────────────────────────────────────

            MSG_TYPE_WALLET_MULTISIG_INFO -> handleWalletMultisigInfo(json, session)
            MSG_TYPE_WALLET_PARTIAL_TX    -> handleWalletPartialTx(json, session)
            MSG_TYPE_WALLET_REFUND_REQ    -> handleWalletRefundRequest(json, session)

            else -> Log.w(TAG, "Unknown local message type: $type")
        }
    }

    // ── XMR wallet message handlers ───────────────────────────────────────────

    /**
     * Peer sent their multisig info. We finalise our side of the escrow,
     * then confirm the escrow address back over I2P.
     */
    private fun handleWalletMultisigInfo(json: JSONObject, session: ClientSession) {
        val peerInfo   = json.optString("info", "")
        val fareAtomic = json.optLong("fare_xmr", 0L)
        val isRider    = json.optBoolean("is_rider", false)
        val rideId     = json.optString("ride_id", "")

        if (peerInfo.isEmpty()) { Log.w(TAG, "Empty multisig info — ignoring"); return }
        Log.i(TAG, "handleWalletMultisigInfo: rideId=$rideId peerIsRider=$isRider")

        val rwm = rideWalletManager
        // `is_rider` flag identifies the SENDER of this message, not this device.
        //
        //  Message from RIDER  → received on DRIVER device: isRider=true  → driver doesn't fund → 0L
        //  Message from DRIVER → received on RIDER  device: isRider=false → rider funds escrow  → fareAtomic
        //
        // The driver echoes the agreed fare_xmr in its message so the rider knows how much to lock.
        rwm.finalizeEscrowWithPeer(peerInfo, if (isRider) 0L else fareAtomic)

        rwm.setPaymentListener(object : RideWalletManager.RidePaymentListener {
            override fun onEscrowReady(escrowAddress: String) {
                broadcastToLocalClients(JSONObject().apply {
                    put("type",          MSG_TYPE_WALLET_ESCROW_READY)
                    put("escrow_address", escrowAddress)
                    put("ride_id",       rideId)
                }.toString())
            }
            override fun onEscrowFunded(escrowAddress: String) {
                broadcastToLocalClients(JSONObject().apply {
                    put("type",          MSG_TYPE_WALLET_ESCROW_READY)
                    put("escrow_address", escrowAddress)
                    put("ride_id",       rideId)
                    put("funded",        true)
                }.toString())
            }
            override fun onPaymentReleased(txId: String) {
                broadcastToLocalClients(JSONObject().apply {
                    put("type",    MSG_TYPE_WALLET_TX_CONFIRMED)
                    put("tx_id",   txId)
                    put("ride_id", rideId)
                }.toString())
            }
            override fun onRefundComplete(txId: String) {
                broadcastToLocalClients(JSONObject().apply {
                    put("type",    MSG_TYPE_WALLET_TX_CONFIRMED)
                    put("tx_id",   txId)
                    put("ride_id", rideId)
                    put("refund",  true)
                }.toString())
            }
            override fun onBalanceCheckResult(s: Boolean, u: String, f: String, sh: String) {}
            override fun onPaymentError(phase: String, error: String) {
                Log.e(TAG, "Escrow error in $phase: $error")
            }
        })
    }

    /**
     * Peer (rider) sent their partial TX hex. We co-sign and broadcast.
     */
    private fun handleWalletPartialTx(json: JSONObject, session: ClientSession) {
        val partialTxHex  = json.optString("partial_tx_hex", "")
        val driverAddress = json.optString("driver_address", "")
        val rideId        = json.optString("ride_id", "")

        if (partialTxHex.isEmpty() || driverAddress.isEmpty()) {
            Log.w(TAG, "Invalid wallet_partial_tx payload"); return
        }
        Log.i(TAG, "handleWalletPartialTx: rideId=$rideId — co-signing")

        WalletSuite.getInstance(this).coSignAndBroadcast(
            partialTxHex,
            driverAddress,
            object : WalletSuite.PaymentReleaseCallback {
                override fun onSuccess(txId: String, amountAtomic: Long) {
                    Log.i(TAG, "✓ Payment broadcast: rideId=$rideId txId=$txId")
                    broadcastToLocalClients(JSONObject().apply {
                        put("type",    MSG_TYPE_WALLET_TX_CONFIRMED)
                        put("tx_id",   txId)
                        put("ride_id", rideId)
                    }.toString())
                }
                override fun onError(error: String) {
                    Log.e(TAG, "coSignAndBroadcast error: $error")
                }
            }
        )
    }

    /**
     * Peer requested a refund (trip cancelled or dispute).
     */
    private fun handleWalletRefundRequest(json: JSONObject, session: ClientSession) {
        val riderAddress = json.optString("rider_address", "")
        val fareAtomic   = json.optLong("fare_xmr", 0L)
        val rideId       = json.optString("ride_id", "")

        if (riderAddress.isEmpty()) { Log.w(TAG, "Empty rider address in refund req"); return }
        Log.i(TAG, "handleWalletRefundRequest: rideId=$rideId")

        val rwm = rideWalletManager
        rwm.setPaymentListener(object : RideWalletManager.RidePaymentListener {
            override fun onRefundComplete(txId: String) {
                broadcastToLocalClients(JSONObject().apply {
                    put("type",    MSG_TYPE_WALLET_TX_CONFIRMED)
                    put("tx_id",   txId)
                    put("ride_id", rideId)
                    put("refund",  true)
                }.toString())
            }
            override fun onPaymentError(phase: String, error: String) {
                Log.e(TAG, "Refund error in $phase: $error")
            }
            override fun onEscrowReady(a: String) {}
            override fun onEscrowFunded(a: String) {}
            override fun onBalanceCheckResult(s: Boolean, u: String, f: String, sh: String) {}
            override fun onPaymentReleased(txId: String) {}
        })
        rwm.refundToRider(riderAddress, fareAtomic, emptyList())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    internal fun broadcastToLocalClients(msg: String) {
        localClients.forEach { it.send(msg) }
    }

    private fun fanOutToLocalClients(json: JSONObject, excludeSession: ClientSession? = null) {
        val msg = json.toString()
        localClients.forEach { if (it != excludeSession) it.send(msg) }
    }

    private fun sendOverI2P(json: JSONObject) {
        try {
            val cls         = Class.forName("com.techducat.kabukabu.network.I2PTransport")
            val getInstance = cls.getMethod("getInstance", Context::class.java, String::class.java)
            val transport   = getInstance.invoke(null, applicationContext, deviceId)
            val broadcast   = cls.getMethod("broadcast", ByteArray::class.java)
            broadcast.invoke(transport, json.toString().toByteArray(Charsets.UTF_8))
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "I2PTransport absent — outbound I2P skipped")
        } catch (e: Exception) {
            Log.e(TAG, "I2P send failed: ${e.message}")
        }
    }

    private suspend fun persistTripRecord(json: JSONObject, localRole: String = "rider", isLocalOrigin: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            val entity = TripEntity(
                tripId         = json.getString("request_id"),
                localRole      = localRole,
                peerId         = "",
                pickupGeohash  = json.optString("pickup_geohash"),
                dropoffGeohash = json.optString("dropoff_geohash"),
                serviceType    = json.optString("service_type", "TAXI"),
                fareXmr        = json.optLong("fare_estimate_xmr", 0),
                status         = "OPEN",
                note           = json.optString("note"),
                timestamp      = json.optLong("timestamp", now),
                expiresAt      = now + TripEntity.TRIP_RETENTION_MS,
                isLocalOrigin  = isLocalOrigin
            )
            tripDao.insertTrip(entity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist trip: ${e.message}")
        }
    }

    private fun pushRelevantRequests(geohash: String, session: ClientSession) {
        val prefix = geohash.take(4)
        activeRequests.values
            .filter { it.optString("pickup_geohash").startsWith(prefix) }
            .forEach { session.send(it.toString()) }
    }

    private fun startCleanupTask() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                val now = System.currentTimeMillis()
                activeRequests.entries.removeAll { entry ->
                    val ttl = entry.value.optLong("ttl_ms", 10 * 60 * 1000L)
                    val ts  = entry.value.optLong("_received_ms", 0L)
                    now - ts > ttl
                }
                // Bug 17: evict peers not seen within PEER_SYNC_WINDOW
                val stalePeers = peerRegistry.entries
                    .filter { now - it.value.lastSeen > PEER_SYNC_WINDOW }
                    .map { it.key }
                stalePeers.forEach { peerRegistry.remove(it) }
                if (stalePeers.isNotEmpty()) Log.i(TAG, "Evicted ${stalePeers.size} stale peer(s)")
                val deleted = tripDao.cleanupExpired(now)
                if (deleted > 0) Log.i(TAG, "Purged $deleted expired trip records")
            }
        }
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    inner class ClientSession(private val socket: Socket) {
        var deviceId: String? = null
        private val writer = PrintWriter(socket.getOutputStream(), true)

        fun send(msg: String) {
            try { writer.println(msg) } catch (_: Exception) {}
        }

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    data class PeerRecord(
        val deviceId: String,
        val geohash:  String,
        val role:     String,
        val lastSeen: Long
    )
}
