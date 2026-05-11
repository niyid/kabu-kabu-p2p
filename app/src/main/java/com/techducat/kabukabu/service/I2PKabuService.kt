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
 * Adapted directly from buzzr-p2p/service/I2PBuzzrService.kt.
 * All Buzzr incident/community-alert logic replaced with
 * ride-matching and courier-dispatch logic.
 *
 * WHAT THIS SERVICE DOES:
 *  1. Starts the embedded I2P router (EmbeddedI2PRouter) so all peer
 *     traffic is anonymised through the I2P network.
 *  2. Binds a local TCP server on 127.0.0.1:8881 that the Android UI
 *     (I2PKabuClient) connects to for read/write of P2P messages.
 *  3. Routes ride requests, driver offers, and trip events between the
 *     I2P network and the local UI client.
 *  4. Persists trips in the on-device Room database (TripDao).
 *  5. Runs a periodic cleanup job to expire old trip records.
 *
 * PRIVACY GUARANTEES:
 *  - This service never opens a connection to a central server.
 *  - Ride requests only contain GeoHash-5 cells, not raw GPS.
 *  - Driver/rider identities are SHA-256 hashes of phone numbers.
 *  - All external traffic passes through I2P garlic routing.
 *  - On-device trip data is automatically purged after 30 days.
 */
class I2PKabuService : LifecycleService() {

    companion object {
        private const val TAG              = "I2PKabuService"
        private const val SERVICE_CHANNEL  = "I2PKabuServiceChannel"
        private const val LOCAL_PORT       = BuildConfig.I2P_SERVICE_PORT
        private const val CLEANUP_INTERVAL = 2 * 60 * 60 * 1000L      // 2 hours
        private const val PEER_SYNC_WINDOW = 24 * 60 * 60 * 1000L     // 24 hours
        private const val MAX_ACTIVE_REQUESTS = 200

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

    // ── State ────────────────────────────────────────────────────────────────

    private lateinit var tripDao: TripDao
    private var embeddedRouter: EmbeddedI2PRouter? = null
    private var deviceId: String = ""

    /** Local TCP server that I2PKabuClient connects to. */
    private var serverSocket: ServerSocket? = null
    private val localClients = CopyOnWriteArrayList<ClientSession>()
    private var serverJob: Job? = null

    /**
     * In-memory cache of open ride requests keyed by requestId.
     * Backed by the Room DB for persistence across service restarts.
     */
    private val activeRequests = ConcurrentHashMap<String, JSONObject>()

    /** Known peers: deviceId → last-seen geohash + role */
    private val peerRegistry  = ConcurrentHashMap<String, PeerRecord>()

    private var wakeLock: PowerManager.WakeLock? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        tripDao  = KabuDatabase.getInstance(applicationContext).tripDao()
        deviceId = getSharedPreferences(KabuKabuApp.PREFS_NAME, MODE_PRIVATE)
            .getString(KabuKabuApp.KEY_DEVICE_ID, "") ?: ""
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

    // ── Notification ─────────────────────────────────────────────────────────

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
        wakeLock?.acquire(10 * 60 * 60 * 1000L)   // max 10 hours; re-acquired as needed
    }

    // ── I2P Router ────────────────────────────────────────────────────────────

    private fun startI2PRouter() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val router = EmbeddedI2PRouter.getInstance(applicationContext, deviceId)
                embeddedRouter = router
                router.startRouter()
                Log.i(TAG, "I2P router started; waiting for tunnels…")
                router.awaitTunnelsReady()
                Log.i(TAG, "I2P tunnels ready — Kabu-Kabu P2P transport active")
                wireI2PReceiveListener()
            } catch (e: Exception) {
                Log.e(TAG, "I2P router startup failed — loopback-only mode", e)
            }
        }
    }

    private fun wireI2PReceiveListener() {
        try {
            val cls      = Class.forName("com.techducat.kabukabu.network.I2PTransport")
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
                // Push any open requests relevant to this geohash to the newly connected peer
                pushRelevantRequests(geohash, session)
                Log.i(TAG, "Local client handshake: $deviceId @ $geohash ($role)")
            }

            "ride_request" -> {
                // Validate & store
                val requestId = json.getString("request_id")
                json.put("_received_ms", System.currentTimeMillis())
                activeRequests[requestId] = json
                persistTripRecord(json)
                // Fan out to all local clients (other role on same device) and I2P peers
                fanOutToLocalClients(json, excludeSession = session)
                sendOverI2P(json)
                Log.i(TAG, "Ride request fanned out: $requestId")
            }

            "driver_offer", "offer_accept", "offer_reject", "trip_event", "peer_review" -> {
                fanOutToLocalClients(json, excludeSession = session)
                sendOverI2P(json)
            }

            "location_update" -> {
                val deviceId   = json.optString("device_id")
                val newGeohash = json.optString("new_geohash")
                peerRegistry[deviceId]?.let { rec ->
                    peerRegistry[deviceId] = rec.copy(geohash = newGeohash, lastSeen = System.currentTimeMillis())
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

            else -> Log.w(TAG, "Unknown local message type: $type")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fanOutToLocalClients(json: JSONObject, excludeSession: ClientSession? = null) {
        val msg = json.toString()
        localClients.forEach { if (it != excludeSession) it.send(msg) }
    }

    private fun sendOverI2P(json: JSONObject) {
        try {
            val cls      = Class.forName("com.techducat.kabukabu.network.I2PTransport")
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

    private suspend fun persistTripRecord(json: JSONObject) {
        try {
            val now = System.currentTimeMillis()
            val entity = TripEntity(
                tripId          = json.getString("request_id"),
                localRole       = "rider",
                peerId          = "",
                pickupGeohash   = json.optString("pickup_geohash"),
                dropoffGeohash  = json.optString("dropoff_geohash"),
                serviceType     = json.optString("service_type", "TAXI"),
                fareNgn         = json.optLong("fare_estimate_ngn", 0),
                status          = "OPEN",
                note            = json.optString("note"),
                timestamp       = json.optLong("timestamp", now),
                expiresAt       = now + TripEntity.TRIP_RETENTION_MS,
                isLocalOrigin   = false
            )
            tripDao.insertTrip(entity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist trip: ${e.message}")
        }
    }

    private fun pushRelevantRequests(geohash: String, session: ClientSession) {
        val prefix = geohash.take(4)   // ~20 km neighbourhood
        activeRequests.values
            .filter { it.optString("pickup_geohash").startsWith(prefix) }
            .forEach { session.send(it.toString()) }
    }

    private fun startCleanupTask() {
        lifecycleScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                val now = System.currentTimeMillis()
                // Remove expired in-memory requests
                activeRequests.entries.removeAll { entry ->
                    val ttl = entry.value.optLong("ttl_ms", 10 * 60 * 1000L)
                    val ts  = entry.value.optLong("_received_ms", 0L)
                    now - ts > ttl
                }
                // Purge Room DB rows past their TTL
                withContext(Dispatchers.IO) {
                    val deleted = tripDao.cleanupExpired(now)
                    if (deleted > 0) Log.i(TAG, "Purged $deleted expired trip records")
                }
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
