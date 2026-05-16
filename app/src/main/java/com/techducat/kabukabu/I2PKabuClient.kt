package com.techducat.kabukabu

import android.content.Context
import android.util.Log
import com.techducat.kabukabu.BuildConfig
import com.techducat.kabukabu.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * I2PKabuClient — P2P transport layer for Kabu-Kabu.
 *
 * Adapted from buzzr-p2p/I2PBuzzrClient.kt. The central relay server
 * (WebSocket endpoint) is replaced entirely by a local I2P Messenger
 * Service running on 127.0.0.1:8881.
 *
 * ARCHITECTURE:
 *   Android app ──TCP──▶ I2PKabuService (127.0.0.1:8881)
 *                                │
 *                      I2P anonymity network (3-hop garlic routing)
 *                                │
 *                   Peer Kabu-Kabu devices anywhere on I2P
 *
 * PRIVACY PROPERTIES:
 *   - No IP address or phone number is ever sent to any peer.
 *   - Location is sent as a GeoHash-5 cell string only (~5 km precision).
 *   - Device identity is a SHA-256 hash of the user's phone number.
 *   - All messages are end-to-end encrypted by the I2P layer (ElGamal/AES).
 *   - The local service (I2PKabuService) stores no logs on a server.
 *
 * MESSAGE TYPES (outbound):
 *   handshake           — device_id + geohash + role + capabilities
 *   heartbeat           — keepalive every 30 s
 *   ride_request        — broadcast a new ride/courier request
 *   driver_offer        — driver responds to a ride_request
 *   offer_accept        — rider accepts a specific driver_offer
 *   offer_reject        — rider rejects an offer
 *   trip_event          — status update during trip (en_route, arrived, etc.)
 *   peer_review         — post-trip star rating for peer
 *   peer_list_request   — ask service for known drivers in geohash zone
 *   disconnect          — polite teardown
 *
 * MESSAGE TYPES (inbound):
 *   ride_request        — incoming request (driver sees this)
 *   driver_offer        — incoming offer (rider sees this)
 *   offer_accept/reject — incoming accept or reject
 *   trip_event          — driver location/status update
 *   peer_list           — roster from local service
 *   peer_status         — peer came online / went offline
 *   heartbeat_ack
 *   error
 */
class I2PKabuClient(private val context: Context) {

    companion object {
        private const val TAG = "I2PKabuClient"

        const val SERVICE_HOST           = "127.0.0.1"
        val SERVICE_PORT                 = BuildConfig.I2P_SERVICE_PORT

        const val CONNECTION_TIMEOUT_MS  = 15_000
        const val READ_TIMEOUT_MS        = 30_000
        const val HEARTBEAT_INTERVAL_MS  = 30_000L
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val RECONNECT_BASE_DELAY   = 5_000L
        const val PROTOCOL_VERSION       = "1.0"
        const val CLIENT_NAME            = "KabuKabuP2P"

        private fun newMsgId() = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private val state       = AtomicReference(State.DISCONNECTED)
    private val connected   = AtomicBoolean(false)
    private val initializing = AtomicBoolean(false)
    private val reconnectLock = Mutex()
    private val lastHeartbeatOk = AtomicLong(System.currentTimeMillis())
    @Volatile private var reconnectCount  = 0  // written in reconnectLock and on successful connect

    private var socket: Socket?           = null
    private var writer: PrintWriter?      = null
    private var reader: BufferedReader?   = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job?        = null

    private var deviceId:       String = ""
    private var currentGeohash: String = ""
    private var currentRole:    String = "rider"

    // ── Listener interfaces ───────────────────────────────────────────────────

    interface RideRequestHandler {
        /** Called on an IO thread when a rider broadcasts a ride/courier request.
         *  Implementations must marshal to the main thread themselves (e.g. runOnUiThread). */
        fun onRideRequestReceived(request: RideRequest)
    }

    interface DriverOfferHandler {
        /** Called on an IO thread when a driver responds to our request.
         *  Implementations must marshal to the main thread themselves (e.g. runOnUiThread). */
        fun onDriverOfferReceived(offer: DriverOffer)
    }

    interface TripEventHandler {
        /** Called on an IO thread for status updates during an active trip.
         *  Implementations must marshal to the main thread themselves (e.g. runOnUiThread). */
        fun onTripEventReceived(event: TripEvent)
    }

    interface OfferAcceptHandler {
        /** Called when a rider accepted our driver offer. */
        fun onOfferAccepted(offerId: String, requestId: String, riderId: String)
        /** Called when a rider rejected our driver offer. */
        fun onOfferRejected(offerId: String, requestId: String)
    }

    interface PeerStatusHandler {
        fun onPeerOnline(peerId: String)
        fun onPeerOffline(peerId: String)
        fun onConnectionStateChanged(connected: Boolean)
    }

    interface I2PStateHandler {
        /** Called on an IO thread when the I2P router reports a state change.
         *  Implementations must marshal to the main thread themselves (e.g. runOnUiThread). */
        fun onI2PStateChanged(state: String)
    }

    /**
     * Handler for XMR wallet messages forwarded by [com.techducat.kabukabu.service.I2PKabuService].
     *
     * The service relays wallet protocol messages from peers (and from its own wallet event
     * listeners) to every connected local UI client.  Activities / fragments that participate
     * in the escrow flow must register here to receive them.
     *
     * Message types delivered:
     *   - wallet_multisig_info  — peer's multisig info string (step 1 of escrow setup)
     *   - wallet_escrow_ready   — shared escrow address confirmed
     *   - wallet_partial_tx     — first signer's partial TX blob (step 1 of payment release)
     *   - wallet_tx_confirmed   — final TX broadcast confirmed (payment or refund)
     *   - wallet_refund_req     — peer requested a refund
     */
    interface WalletMessageHandler {
        fun onWalletMessage(type: String, payload: JSONObject)
    }

    private val rideRequestListeners  = ConcurrentHashMap<String, RideRequestHandler>()
    private val driverOfferListeners  = ConcurrentHashMap<String, DriverOfferHandler>()
    private val tripEventListeners    = ConcurrentHashMap<String, TripEventHandler>()
    private val offerAcceptListeners  = ConcurrentHashMap<String, OfferAcceptHandler>()
    private val peerStatusListeners   = ConcurrentHashMap<String, PeerStatusHandler>()
    private val i2pStateListeners     = ConcurrentHashMap<String, I2PStateHandler>()
    private val walletMessageListeners = ConcurrentHashMap<String, WalletMessageHandler>()

    fun addRideRequestHandler (key: String, h: RideRequestHandler)  { rideRequestListeners[key] = h }
    fun addDriverOfferHandler (key: String, h: DriverOfferHandler)  { driverOfferListeners[key] = h }
    fun addTripEventHandler   (key: String, h: TripEventHandler)    { tripEventListeners[key] = h }
    fun addOfferAcceptHandler (key: String, h: OfferAcceptHandler)  { offerAcceptListeners[key] = h }
    fun addPeerStatusHandler  (key: String, h: PeerStatusHandler)   { peerStatusListeners[key] = h }
    fun addI2PStateHandler    (key: String, h: I2PStateHandler)     { i2pStateListeners[key] = h }
    fun addWalletMessageHandler(key: String, h: WalletMessageHandler) { walletMessageListeners[key] = h }

    // ── Connection lifecycle ──────────────────────────────────────────────────

    suspend fun initialize(deviceId: String, geohash: String, role: String = "rider"): Boolean {
        if (initializing.get()) return false
        if (connected.get()) return true
        this.deviceId       = deviceId
        this.currentGeohash = geohash
        this.currentRole    = role
        initializing.set(true)
        return withContext(Dispatchers.IO) {
            try {
                cleanupConnection()
                if (!isServiceAvailable()) {
                    Log.e(TAG, "I2PKabuService not reachable on $SERVICE_HOST:$SERVICE_PORT"); return@withContext false
                }
                socket = Socket().apply {
                    soTimeout  = READ_TIMEOUT_MS; keepAlive = true; tcpNoDelay = true
                }
                socket!!.connect(java.net.InetSocketAddress(SERVICE_HOST, SERVICE_PORT), CONNECTION_TIMEOUT_MS)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                if (!performHandshake()) { cleanupConnection(); return@withContext false }
                connected.set(true); state.set(State.CONNECTED); reconnectCount = 0
                notifyConnectionChange(true)
                startMessageListener()
                startHeartbeat()
                delay(500)
                broadcastAvailability()
                Log.i(TAG, "I2PKabuClient connected @ $geohash as $role"); true
            } catch (e: Exception) {
                Log.e(TAG, "initialize() failed", e); cleanupConnection(); false
            } finally { initializing.set(false) }
        }
    }

    fun close() {
        scope.launch { cleanupConnection() }
        scope.cancel()
    }

    // ── Outbound messages ─────────────────────────────────────────────────────

    /**
     * Broadcast a [RideRequest] to drivers in the same and adjacent GeoHash zones.
     * Only the geohash cell is transmitted — never raw GPS.
     */
    suspend fun broadcastRideRequest(request: RideRequest): Boolean {
        val json = JSONObject().apply {
            put("type",             "ride_request")
            put("request_id",       request.requestId)
            put("rider_id",         request.riderId)
            put("pickup_geohash",   request.pickupGeohash)     // ~5 km cell
            put("dropoff_geohash",  request.dropoffGeohash)    // ~5 km cell
            put("service_type",     request.serviceType.name)
            put("fare_estimate_xmr",request.fareEstimateXMR)
            put("note",             request.noteForDriver)
            put("timestamp",        request.timestamp)
            put("ttl_ms",           request.ttlMs)
        }
        return sendMessageSuspend(json)
    }

    /**
     * Send a [DriverOffer] to a specific rider via their I2P destination.
     * The driver's I2P destination (not IP) is the only identifier shared.
     */
    suspend fun sendDriverOffer(offer: DriverOffer): Boolean {
        val json = JSONObject().apply {
            put("type",            "driver_offer")
            put("offer_id",        offer.offerId)
            put("request_id",      offer.requestId)
            put("driver_id",       offer.driverId)
            put("driver_i2p_dest", offer.driverI2pDest)
            put("eta_minutes",     offer.etaMinutes)
            put("vehicle_type",    offer.vehicleType)
            put("rating_score",    offer.ratingScore)
            offer.counterFareXMR?.let { put("counter_fare_xmr", it) }
            put("timestamp",       offer.timestamp)
        }
        return sendMessageSuspend(json)
    }

    /** Accept a driver offer — opens a direct encrypted I2P tunnel. */
    suspend fun acceptOffer(offerId: String, requestId: String): Boolean {
        val json = JSONObject().apply {
            put("type",       "offer_accept")
            put("offer_id",   offerId)
            put("request_id", requestId)
            put("rider_id",   deviceId)
            put("timestamp",  System.currentTimeMillis())
        }
        return sendMessageSuspend(json)
    }

    /** Reject a driver offer. */
    suspend fun rejectOffer(offerId: String, requestId: String): Boolean {
        val json = JSONObject().apply {
            put("type",       "offer_reject")
            put("offer_id",   offerId)
            put("request_id", requestId)
            put("timestamp",  System.currentTimeMillis())
        }
        return sendMessageSuspend(json)
    }

    /**
     * Send a [TripEvent] over the direct device-to-device I2P tunnel.
     * [currentGeohash] is GeoHash-5 — ~5 km fuzzy; never raw GPS.
     */
    suspend fun sendTripEvent(event: TripEvent): Boolean {
        val json = JSONObject().apply {
            put("type",            "trip_event")
            put("trip_id",         event.tripId)
            put("driver_id",       event.driverId)
            put("event_type",      event.eventType.name)
            put("current_geohash", event.currentGeohash)   // ~5 km cell
            put("message_text",    event.messageText)
            put("timestamp",       event.timestamp)
        }
        return sendMessageSuspend(json)
    }

    /**
     * Gossip a peer review to neighbours — no central rating server needed.
     * Reviews propagate organically through the I2P gossip layer.
     */
    suspend fun sendPeerReview(tripId: String, reviewedPeerId: String, stars: Int, comment: String): Boolean {
        val json = JSONObject().apply {
            put("type",             "peer_review")
            put("trip_id",          tripId)
            put("reviewed_peer_id", reviewedPeerId)
            put("stars",            stars)
            put("comment",          comment)
            put("from_device_id",   deviceId)
            put("timestamp",        System.currentTimeMillis())
        }
        return sendMessageSuspend(json)
    }

    /** Update our GeoHash cell when we move to a new zone (driver going to next area). */
    suspend fun updateLocation(oldGeohash: String, newGeohash: String): Boolean {
        val json = JSONObject().apply {
            put("type",         "location_update")
            put("device_id",    deviceId)
            put("old_geohash",  oldGeohash)
            put("new_geohash",  newGeohash)
            put("timestamp",    System.currentTimeMillis())
        }
        return sendMessageSuspend(json)
    }

    // ── Inbound message dispatch ──────────────────────────────────────────────

    private fun handleIncoming(json: JSONObject) {
        when (val type = json.optString("type")) {
            "ride_request" -> {
                val req = RideRequest(
                    requestId       = json.getString("request_id"),
                    riderId         = json.getString("rider_id"),
                    pickupGeohash   = json.getString("pickup_geohash"),
                    dropoffGeohash  = json.getString("dropoff_geohash"),
                    serviceType     = ServiceType.valueOf(json.getString("service_type")),
                    fareEstimateXMR = json.getLong("fare_estimate_xmr"),
                    noteForDriver   = json.optString("note"),
                    timestamp       = json.getLong("timestamp")
                )
                rideRequestListeners.values.forEach { it.onRideRequestReceived(req) }
            }
            "driver_offer" -> {
                val offer = DriverOffer(
                    offerId        = json.getString("offer_id"),
                    requestId      = json.getString("request_id"),
                    driverId       = json.getString("driver_id"),
                    driverI2pDest  = json.getString("driver_i2p_dest"),
                    etaMinutes     = json.getInt("eta_minutes"),
                    vehicleType    = json.getString("vehicle_type"),
                    ratingScore    = json.getDouble("rating_score").toFloat(),
                    counterFareXMR = if (json.has("counter_fare_xmr")) json.getLong("counter_fare_xmr") else null,
                    timestamp      = json.getLong("timestamp")
                )
                driverOfferListeners.values.forEach { it.onDriverOfferReceived(offer) }
            }
            "trip_event" -> {
                val event = TripEvent(
                    tripId         = json.getString("trip_id"),
                    driverId       = json.getString("driver_id"),
                    eventType      = TripEventType.valueOf(json.getString("event_type")),
                    currentGeohash = json.getString("current_geohash"),
                    messageText    = json.optString("message_text"),
                    timestamp      = json.getLong("timestamp")
                )
                tripEventListeners.values.forEach { it.onTripEventReceived(event) }
            }
            "offer_accept" -> {
                val offerId   = json.optString("offer_id")
                val requestId = json.optString("request_id")
                val riderId   = json.optString("rider_id")
                offerAcceptListeners.values.forEach { it.onOfferAccepted(offerId, requestId, riderId) }
            }
            "offer_reject" -> {
                val offerId   = json.optString("offer_id")
                val requestId = json.optString("request_id")
                offerAcceptListeners.values.forEach { it.onOfferRejected(offerId, requestId) }
            }
            "peer_status" -> {
                val peerId = json.getString("peer_id")
                val online = json.getString("status") == "online"
                peerStatusListeners.values.forEach {
                    if (online) it.onPeerOnline(peerId) else it.onPeerOffline(peerId)
                }
            }
            "i2p_state" -> {
                val state = json.optString("state")
                i2pStateListeners.values.forEach { it.onI2PStateChanged(state) }
            }
            // ── XMR wallet messages forwarded from I2PKabuService ─────────────
            // These originate either from the local service (wallet lifecycle events)
            // or from the peer device (multisig handshake, partial TX).
            // They must be relayed to the walletMessageListeners in the Activity layer
            // so the RideWalletManager / WalletSuite can process them.
            "wallet_multisig_info",
            "wallet_escrow_ready",
            "wallet_partial_tx",
            "wallet_tx_confirmed",
            "wallet_refund_req" -> {
                walletMessageListeners.values.forEach { it.onWalletMessage(type, json) }
            }
            "heartbeat_ack" -> lastHeartbeatOk.set(System.currentTimeMillis())
            "error" -> Log.e(TAG, "Service error: ${json.optString("message")}")
            else -> Log.w(TAG, "Unknown message type: $type")
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun startMessageListener() {
        scope.launch {
            while (isActive && connected.get()) {
                try {
                    val line = reader?.readLine() ?: break
                    if (line.isNotBlank()) {
                        try { handleIncoming(JSONObject(line)) }
                        catch (e: Exception) { Log.w(TAG, "Parse error: ${e.message}") }
                    }
                } catch (e: SocketTimeoutException) {
                    if (System.currentTimeMillis() - lastHeartbeatOk.get() > HEARTBEAT_INTERVAL_MS * 3) {
                        Log.w(TAG, "Heartbeat timeout — reconnecting")
                        attemptReconnect(); break
                    }
                } catch (e: Exception) {
                    if (isActive) { Log.e(TAG, "Read error — reconnecting", e); attemptReconnect(); break }
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && connected.get()) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendMessage(JSONObject().apply { put("type", "heartbeat"); put("device_id", deviceId) })
            }
        }
    }

    private suspend fun attemptReconnect() {
        reconnectLock.withLock {
            if (reconnectCount >= MAX_RECONNECT_ATTEMPTS) {
                Log.e(TAG, "Max reconnect attempts reached"); notifyConnectionChange(false); return
            }
            state.set(State.RECONNECTING); connected.set(false); notifyConnectionChange(false)
            val delay = RECONNECT_BASE_DELAY * (1L shl reconnectCount.coerceAtMost(5))
            Log.i(TAG, "Reconnecting in ${delay}ms (attempt ${reconnectCount + 1})")
            delay(delay)
            reconnectCount++
            val ok = initialize(deviceId, currentGeohash, currentRole)
            // Reset the counter on success so future disconnects start fresh
            if (ok) reconnectCount = 0
        }
    }

    private fun broadcastAvailability() {
        scope.launch {
            sendMessage(JSONObject().apply {
                put("type",      "location_update")
                put("device_id", deviceId)
                put("old_geohash", currentGeohash)
                put("new_geohash", currentGeohash)
                put("role",      currentRole)
                put("timestamp", System.currentTimeMillis())
            })
        }
    }

    private fun performHandshake(): Boolean = try {
        val hs = JSONObject().apply {
            put("type",             "handshake")
            put("protocol_version", PROTOCOL_VERSION)
            put("client_name",      CLIENT_NAME)
            put("device_id",        deviceId)
            put("geohash",          currentGeohash)
            put("role",             currentRole)
            put("timestamp",        System.currentTimeMillis())
        }
        writer?.println(hs.toString())
        val resp = reader?.readLine() ?: return false
        val json = JSONObject(resp)
        json.optString("type") == "handshake_ack"
    } catch (e: Exception) { Log.e(TAG, "Handshake failed", e); false }

    private fun sendMessage(json: JSONObject): Boolean = try {
        writer?.println(json.toString()); true
    } catch (e: Exception) { Log.e(TAG, "Send failed: ${e.message}"); false }

    private suspend fun sendMessageSuspend(json: JSONObject): Boolean =
        withContext(Dispatchers.IO) { sendMessage(json) }

    private fun cleanupConnection() {
        connected.set(false); state.set(State.DISCONNECTED)
        initializing.set(false)
        heartbeatJob?.cancel(); heartbeatJob = null
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null; reader = null; socket = null
    }

    private fun isServiceAvailable(): Boolean = try {
        Socket().use { it.connect(java.net.InetSocketAddress(SERVICE_HOST, SERVICE_PORT), 3000); true }
    } catch (_: Exception) { false }

    private fun notifyConnectionChange(isConnected: Boolean) {
        peerStatusListeners.values.forEach { it.onConnectionStateChanged(isConnected) }
    }

    /**
     * Send a raw JSON string directly to the local I2PKabuService TCP socket.
     * Used by the XMR wallet flow to relay multisig and partial-TX messages
     * over I2P without going through the typed ride-request helpers.
     *
     * Dispatches to the IO scope — safe to call from the main thread.
     */
    fun sendRawMessage(json: String) {
        scope.launch(Dispatchers.IO) {
            try { writer?.println(json) }
            catch (e: Exception) { Log.e(TAG, "sendRawMessage failed: ${e.message}") }
        }
    }
}
