package com.techducat.kabukabu

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ch.hsr.geohash.GeoHash
import com.google.android.gms.location.*
import com.techducat.kabukabu.model.*
import com.techducat.kabukabu.service.I2PKabuService
import com.techducat.kabukabu.ui.OfferAdapter
import com.techducat.kabukabu.ui.RequestAdapter
import com.techducat.kabukabu.wallet.RideWalletManager
import com.techducat.kabukabu.wallet.WalletActivity
import com.techducat.kabukabu.wallet.WalletSuite
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * MainActivity — rider / sender interface for Kabu-Kabu.
 *
 * Privacy decisions enforced here:
 *
 *  1. GPS is read only to compute a GeoHash-5 cell (~5 km radius).
 *     [lastKnownGeohash] is the only location value used in any message.
 *     The raw [Location] object is discarded immediately after hashing.
 *
 *  2. Phone numbers are SHA-256-hashed on first registration.
 *     The plaintext number is NEVER stored to SharedPreferences or
 *     transmitted over the network.
 *
 *  3. [i2pClient] talks to the local I2PKabuService (127.0.0.1:8881),
 *     which routes everything through the embedded I2P router.
 *     There is no WebSocket endpoint, no REST API, no central server.
 *
 *  4. Incoming driver offers show only the driver's vehicle type, ETA,
 *     rating score, and geohash zone — never their real name or phone.
 *
 *  5. XMR payment uses 2-of-2 multisig escrow via Monerujo JNI.
 *     Balance is checked before accepting an offer; escrow is funded
 *     atomically and only released when both parties co-sign on trip
 *     completion.  No central custodian.
 */
@AndroidEntryPoint
class MainActivity :
    AppCompatActivity(),
    I2PKabuClient.RideRequestHandler,
    I2PKabuClient.DriverOfferHandler,
    I2PKabuClient.TripEventHandler,
    I2PKabuClient.PeerStatusHandler,
    I2PKabuClient.I2PStateHandler {

    companion object {
        private const val TAG = "KabuMainActivity"
        private const val GEOHASH_PRECISION            = 5     // ~5 km cell
        private const val LOCATION_PERMISSION_CODE     = 1001
        private const val PREFS_NAME                   = KabuKabuApp.PREFS_NAME
        private const val KEY_DEVICE_ID                = KabuKabuApp.KEY_DEVICE_ID
        private const val KEY_ROLE                     = KabuKabuApp.KEY_ROLE
        private const val KEY_POLICY_ACCEPTED          = KabuKabuApp.KEY_POLICY_ACCEPTED
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var deviceId:          String
    private var lastKnownGeohash:           String = ""
    private var policyAccepted:             Boolean = false
    private var isRegistered:               Boolean = false
    private var currentRole:                String = "rider"

    // ── XMR / wallet state ────────────────────────────────────────────────────

    private lateinit var rideWalletManager:     RideWalletManager
    private var currentFareAtomicUnits:         Long   = 0L
    private var currentRideId:                  String = ""
    private var currentDriverXmrAddress:        String = ""
    private var myXmrAddress:                   String = ""
    private var pendingAcceptOffer:             DriverOffer? = null

    // ── Location ──────────────────────────────────────────────────────────────

    private lateinit var fusedLocationClient:  FusedLocationProviderClient
    private lateinit var locationCallback:     LocationCallback

    // ── P2P client ────────────────────────────────────────────────────────────

    private lateinit var i2pClient: I2PKabuClient

    // ── UI ────────────────────────────────────────────────────────────────────

    private lateinit var tvStatus:         TextView
    private lateinit var layoutI2PStatus:  android.view.View
    private lateinit var progressI2P:      android.widget.ProgressBar
    private lateinit var tvI2PStatus:      TextView
    private lateinit var tvGeohash:        TextView
    private lateinit var tvRole:           TextView
    private lateinit var btnRequestRide:   Button
    private lateinit var btnSwitchRole:    Button
    private lateinit var etNote:           EditText
    private lateinit var rvOffers:         RecyclerView
    private lateinit var offerAdapter:     OfferAdapter

    private val receivedOffers = mutableListOf<DriverOffer>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        policyAccepted    = sharedPreferences.getBoolean(KEY_POLICY_ACCEPTED, false)
        currentRole       = sharedPreferences.getString(KEY_ROLE, "rider") ?: "rider"

        bindViews()
        setupRecyclerView()

        i2pClient = I2PKabuClient(this)
        i2pClient.addRideRequestHandler ("main", this)
        i2pClient.addDriverOfferHandler ("main", this)
        i2pClient.addTripEventHandler   ("main", this)
        i2pClient.addPeerStatusHandler  ("main", this)
        i2pClient.addI2PStateHandler    ("main", this)
        i2pClient.addWalletMessageHandler("main", walletMessageDispatcher)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()

        if (!policyAccepted) showPrivacyDialog() else initAfterConsent()
    }

    override fun onResume() {
        super.onResume()
        if (policyAccepted && isRegistered) startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        i2pClient.close()
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    private fun initAfterConsent() {
        val savedId = sharedPreferences.getString(KEY_DEVICE_ID, "") ?: ""
        if (savedId.isNotEmpty()) {
            deviceId     = savedId
            isRegistered = true
            tvStatus.text = getString(R.string.status_registered)
            requestLocationPermissions()
            startI2PService()
            initWallet()   // deviceId is set — safe to proceed
        } else {
            showPhoneRegistrationDialog()
            // initWallet() will be called by registerDevice() once the user submits
        }
    }

    private fun initWallet() {
        rideWalletManager = RideWalletManager(this)
        WalletSuite.getInstance(this).apply {
            setStatusListener(object : WalletSuite.WalletStatusListener {
                override fun onWalletInitialized(success: Boolean, message: String) {
                    Log.i(TAG, "Wallet init: success=$success $message")
                    if (success) {
                        getAddress(object : WalletSuite.AddressCallback {
                            override fun onSuccess(address: String) {
                                myXmrAddress = address
                            }
                            override fun onError(error: String) {
                                Log.e(TAG, "getAddress failed: $error")
                            }
                        })
                    }
                }
                override fun onBalanceUpdated(balance: Long, unlocked: Long) {
                    Log.d(TAG, "Balance: ${WalletSuite.convertAtomicToXmr(unlocked)} XMR unlocked")
                }
                override fun onSyncProgress(height: Long, start: Long, end: Long, pct: Double) {}
            })
            initializeWallet(deviceId)
        }
        rideWalletManager.setPaymentListener(walletPaymentListener)
    }

    private fun registerDevice(phoneNumber: String) {
        deviceId = sha256(phoneNumber)
        sharedPreferences.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        isRegistered = true
        tvStatus.text = getString(R.string.status_registered)
        startI2PService()
        requestLocationPermissions()
        initWallet()   // deviceId is now set — safe to call
    }

    private fun startI2PService() {
        I2PKabuService.startService(this)
        lifecycleScope.launch {
            // Wait briefly for service to bind, then attempt to connect.
            // If location hasn't been granted yet lastKnownGeohash will be
            // empty here — onLocationUpdate() calls initClientIfReady() once
            // the first fix arrives, so we don't silently drop the init.
            kotlinx.coroutines.delay(2000)
            initClientIfReady()
        }
    }

    /**
     * Connect the I2P client to the service when both prerequisites are met:
     *   1. The device is registered (deviceId is non-empty).
     *   2. The first GPS fix has been converted to a geohash.
     * Safe to call multiple times — I2PKabuClient.initialize() is idempotent.
     */
    private fun initClientIfReady() {
        if (!isRegistered || lastKnownGeohash.isEmpty()) return
        lifecycleScope.launch {
            val ok = i2pClient.initialize(deviceId, lastKnownGeohash, currentRole)
            Log.i(TAG, "I2PKabuClient initialized: $ok")
        }
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocationUpdate(it) }
            }
        }
    }

    /**
     * The only place GPS is used.
     * Raw [location] is converted to a GeoHash-5 cell and then discarded.
     * The geohash (~5 km precision) is the only location value stored or sent.
     */
    private fun onLocationUpdate(location: Location) {
        val newGeohash = GeoHash
            .withCharacterPrecision(location.latitude, location.longitude, GEOHASH_PRECISION)
            .toBase32()

        if (newGeohash != lastKnownGeohash) {
            val old = lastKnownGeohash
            lastKnownGeohash = newGeohash
            tvGeohash.text = getString(R.string.zone_label, newGeohash)
            Log.d(TAG, "Geohash updated: $old → $newGeohash")

            // Bug 24: if this is the first fix and the client hasn't connected
            // yet (because startI2PService ran before location was available),
            // connect now.
            if (old.isEmpty()) {
                initClientIfReady()
            } else if (isRegistered) {
                lifecycleScope.launch {
                    i2pClient.updateLocation(old, newGeohash)
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
            .setMinUpdateDistanceMeters(500f)   // Only update on significant movement
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
        }
    }

    private fun requestLocationPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startLocationUpdates()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), LOCATION_PERMISSION_CODE)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        if (code == LOCATION_PERMISSION_CODE &&
            grants.all { it == PackageManager.PERMISSION_GRANTED })
            startLocationUpdates()
    }

    // ── Ride request ──────────────────────────────────────────────────────────

    private fun onRequestRideTapped() {
        if (!isRegistered || lastKnownGeohash.isEmpty()) {
            Toast.makeText(this, R.string.error_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val note = etNote.text.toString().trim()
        val request = RideRequest(
            requestId       = UUID.randomUUID().toString(),
            riderId         = deviceId,
            pickupGeohash   = lastKnownGeohash,
            dropoffGeohash  = "",
            serviceType     = if (currentRole == "courier_sender") ServiceType.COURIER else ServiceType.TAXI,
            fareEstimateXMR = estimateFare(),
            noteForDriver   = note,
            timestamp       = System.currentTimeMillis()
        )
        lifecycleScope.launch {
            val ok = i2pClient.broadcastRideRequest(request)
            withContext(Dispatchers.Main) {
                val msg = if (ok) R.string.request_sent else R.string.request_failed
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Estimate fare using the on-device FareEstimator (geohash distance + rate table). */
    private fun estimateFare(): Long =
        com.techducat.kabukabu.network.FareEstimator.estimate(
            pickupGeohash  = lastKnownGeohash,
            dropoffGeohash = "",              // dropoff unknown at request time
            serviceType    = if (currentRole == "courier_sender")
                                 com.techducat.kabukabu.model.ServiceType.COURIER
                             else
                                 com.techducat.kabukabu.model.ServiceType.TAXI
        )

    // ── XMR wallet — offer acceptance gate ────────────────────────────────────

    /**
     * Called when the rider taps "Accept" on a DriverOffer.
     * Balance is checked first; the offer is only sent if sufficient XMR
     * is available to cover the fare.
     */
    private fun onDriverOfferAccepted(offer: DriverOffer) {
        pendingAcceptOffer      = offer
        currentFareAtomicUnits  = offer.counterFareXMR ?: 0L
        currentRideId           = offer.requestId
        // currentDriverXmrAddress is NOT in DriverOffer — it arrives later
        // via the driver's "wallet_multisig_info" message over I2P

        // Gate: check balance before proceeding to escrow
        rideWalletManager.checkBalanceBeforeAccepting(offer)
        // Result delivered to walletPaymentListener.onBalanceCheckResult()
    }

    // ── XMR wallet — escrow setup ─────────────────────────────────────────────

    private fun startEscrowSetup() {
        rideWalletManager.prepareLocalEscrow(
            onReady = { myInfo, myAddress ->
                Log.i(TAG, "Escrow prepared — relaying multisig info to driver over I2P")
                val walletMsg = JSONObject().apply {
                    put("type",      "wallet_multisig_info")
                    put("info",      myInfo)
                    put("address",   myAddress)
                    put("fare_xmr",  currentFareAtomicUnits)
                    put("ride_id",   currentRideId)
                    put("is_rider",  true)
                }
                i2pClient.sendRawMessage(walletMsg.toString())
            },
            onError = { error ->
                runOnUiThread { tvStatus.text = "Escrow setup failed: $error" }
            }
        )
    }

    // ── XMR wallet — inbound wallet protocol dispatcher ──────────────────────

    /**
     * Routes wallet protocol messages forwarded by [I2PKabuClient] to the correct
     * handler on the main thread.
     *
     * Message sources:
     *  - wallet_multisig_info  → driver sent their multisig info; finalize escrow on our side.
     *  - wallet_escrow_ready   → escrow address confirmed; update UI.
     *  - wallet_partial_tx     → driver sent partial TX for us to co-sign (rider side only).
     *  - wallet_tx_confirmed   → payment or refund broadcast confirmed.
     */
    private val walletMessageDispatcher = object : I2PKabuClient.WalletMessageHandler {
        override fun onWalletMessage(type: String, payload: org.json.JSONObject) {
            // All handling must happen on the main thread.
            runOnUiThread {
                when (type) {
                    "wallet_multisig_info" -> {
                        // Peer (driver) sent their multisig info → finalise our side of the escrow
                        val peerInfo    = payload.optString("info", "")
                        val fareAtomic  = payload.optLong("fare_xmr", 0L)
                        val isRider     = payload.optBoolean("is_rider", false)
                        // is_rider flag identifies the SENDER (driver sends is_rider=false)
                        // Rider device should fund the escrow (fareAtomic > 0);
                        // driver device receives is_rider=true (sent by rider) → no funding.
                        val fundAmt = if (isRider) 0L else fareAtomic
                        if (peerInfo.isNotEmpty()) {
                            rideWalletManager.finalizeEscrowWithPeer(peerInfo, fundAmt)
                        }
                    }
                    "wallet_escrow_ready" -> {
                        val escrowAddr = payload.optString("escrow_address", "")
                        if (escrowAddr.isNotEmpty()) {
                            tvStatus.text = "Escrow ready — waiting for driver"
                            Log.i(TAG, "Escrow address confirmed: ${escrowAddr.take(16)}…")
                        }
                    }
                    "wallet_partial_tx" -> {
                        // Driver sent their partial TX → rider must co-sign and broadcast
                        val partialTxHex   = payload.optString("partial_tx_hex", "")
                        val driverAddress  = payload.optString("driver_address", "")
                        val fareAtomic     = payload.optLong("fare_xmr", currentFareAtomicUnits)
                        if (partialTxHex.isNotEmpty() && driverAddress.isNotEmpty()) {
                            Log.i(TAG, "Received partial TX from driver — co-signing")
                            WalletSuite.getInstance(this@MainActivity).coSignAndBroadcast(
                                partialTxHex,
                                driverAddress,
                                object : WalletSuite.PaymentReleaseCallback {
                                    override fun onSuccess(txId: String, amountAtomic: Long) {
                                        runOnUiThread {
                                            tvStatus.text = "Payment sent ✓"
                                            walletPaymentListener.onPaymentReleased(txId)
                                        }
                                    }
                                    override fun onError(error: String) {
                                        runOnUiThread { walletPaymentListener.onPaymentError("cosign", error) }
                                    }
                                }
                            )
                        }
                    }
                    "wallet_tx_confirmed" -> {
                        val txId   = payload.optString("tx_id", "")
                        val refund = payload.optBoolean("refund", false)
                        if (refund) walletPaymentListener.onRefundComplete(txId)
                        else        walletPaymentListener.onPaymentReleased(txId)
                    }
                }
            }
        }
    }

    // ── XMR wallet — payment listener ────────────────────────────────────────

    private val walletPaymentListener = object : RideWalletManager.RidePaymentListener {

        override fun onBalanceCheckResult(
            sufficient:   Boolean,
            unlockedXmr:  String,
            fareXmr:      String,
            shortfallXmr: String
        ) {
            if (sufficient) {
                // Balance OK — accept the offer and set up escrow
                val offer = pendingAcceptOffer ?: return
                lifecycleScope.launch {
                    i2pClient.acceptOffer(offer.offerId, offer.requestId)
                }
                startEscrowSetup()
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Insufficient XMR")
                    .setMessage(
                        "Fare: $fareXmr XMR\n" +
                        "Your balance: $unlockedXmr XMR\n\n" +
                        "Please top up $shortfallXmr XMR before booking."
                    )
                    .setPositiveButton("Open Wallet") { _, _ ->
                        startActivity(Intent(this@MainActivity, WalletActivity::class.java))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        override fun onEscrowReady(escrowAddress: String) {
            Log.i(TAG, "Escrow ready: ${escrowAddress.take(16)}…")
            runOnUiThread { tvStatus.text = "Escrow locked — ride approved" }
        }

        override fun onEscrowFunded(escrowAddress: String) {
            Log.i(TAG, "Escrow funded: ${escrowAddress.take(16)}…")
            runOnUiThread { tvStatus.text = "Fare locked in escrow — driver notified" }
            val msg = JSONObject().apply {
                put("type",          "wallet_escrow_ready")
                put("escrow_address", escrowAddress)
                put("ride_id",       currentRideId)
                put("funded",        true)
            }
            i2pClient.sendRawMessage(msg.toString())
        }

        override fun onPaymentReleased(txId: String) {
            Log.i(TAG, "Payment released — txId: $txId")
            runOnUiThread {
                tvStatus.text = "Payment complete ✓"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Ride Paid")
                    .setMessage("XMR payment sent.\nTransaction: ${txId.take(16)}…")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        override fun onRefundComplete(txId: String) {
            Log.i(TAG, "Refund complete — txId: $txId")
            runOnUiThread { tvStatus.text = "Refund processed ✓" }
        }

        override fun onPaymentError(phase: String, error: String) {
            Log.e(TAG, "Payment error in $phase: $error")
            runOnUiThread {
                tvStatus.text = "Payment error: $error"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Wallet Error")
                    .setMessage("Phase: $phase\n\n$error")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ── I2PKabuClient callbacks ───────────────────────────────────────────────

    override fun onRideRequestReceived(request: RideRequest) {
        Log.d(TAG, "Ride request received (rider mode — ignoring): ${request.requestId}")
    }

    override fun onDriverOfferReceived(offer: DriverOffer) {
        runOnUiThread {
            receivedOffers.add(0, offer)
            offerAdapter.notifyItemInserted(0)
            Toast.makeText(this, R.string.new_offer_received, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTripEventReceived(event: TripEvent) {
        runOnUiThread {
            when (event.eventType) {
                TripEventType.TRIP_COMPLETED -> {
                    tvStatus.text = getString(R.string.event_trip_completed)
                    // Release escrow to driver — rider is first signer
                    rideWalletManager.onTripCompleted(
                        event               = event,
                        isRider             = true,
                        driverXmrAddress    = currentDriverXmrAddress,
                        fareAtomicUnits     = currentFareAtomicUnits,
                        peerPartialTxHex    = null,
                        onNeedPeerSignature = { partialTxHex ->
                            val msg = JSONObject().apply {
                                put("type",           "wallet_partial_tx")
                                put("partial_tx_hex", partialTxHex)
                                put("driver_address", currentDriverXmrAddress)
                                put("fare_xmr",       currentFareAtomicUnits)
                                put("ride_id",        currentRideId)
                            }
                            i2pClient.sendRawMessage(msg.toString())
                        }
                    )
                }
                TripEventType.TRIP_CANCELLED -> {
                    tvStatus.text = getString(R.string.event_trip_cancelled)

                    // Build the onNeedPeerSignature callback once — reused by both branches.
                    // The rider is the first signer on a refund: export partial images and send
                    // them to the driver over I2P (wallet_partial_tx with refund=true).
                    val onNeedSig: (String) -> Unit = { partialTxHex ->
                        val msg = JSONObject().apply {
                            put("type",           "wallet_partial_tx")
                            put("partial_tx_hex", partialTxHex)
                            put("driver_address", myXmrAddress)  // refund destination = rider
                            put("fare_xmr",       currentFareAtomicUnits)
                            put("ride_id",        currentRideId)
                            put("refund",         true)           // flag: this is a refund, not a payout
                        }
                        i2pClient.sendRawMessage(msg.toString())
                    }

                    // Refund escrow back to rider — guard against address not yet loaded
                    if (myXmrAddress.isNotEmpty()) {
                        rideWalletManager.refundToRider(
                            riderXmrAddress      = myXmrAddress,
                            fareAtomicUnits      = currentFareAtomicUnits,
                            peerSignatureInfos   = emptyList(),
                            onNeedPeerSignature  = onNeedSig
                        )
                    } else {
                        // Address still loading — fetch it then refund
                        WalletSuite.getInstance(this@MainActivity).getAddress(
                            object : WalletSuite.AddressCallback {
                                override fun onSuccess(address: String) {
                                    myXmrAddress = address
                                    rideWalletManager.refundToRider(
                                        riderXmrAddress     = address,
                                        fareAtomicUnits     = currentFareAtomicUnits,
                                        peerSignatureInfos  = emptyList(),
                                        onNeedPeerSignature = onNeedSig
                                    )
                                }
                                override fun onError(error: String) {
                                    Log.e(TAG, "Cannot refund — address unavailable: $error")
                                    tvStatus.text = "Refund failed: wallet address unavailable"
                                }
                            }
                        )
                    }
                }
                TripEventType.DRIVER_EN_ROUTE -> tvStatus.text = getString(R.string.event_en_route)
                TripEventType.DRIVER_ARRIVED  -> tvStatus.text = getString(R.string.event_arrived)
                TripEventType.TRIP_STARTED    -> tvStatus.text = getString(R.string.event_trip_started)
            }
        }
    }

    override fun onPeerOnline(peerId: String)  { Log.d(TAG, "Peer online: $peerId") }
    override fun onPeerOffline(peerId: String) { Log.d(TAG, "Peer offline: $peerId") }

    // ── I2PStateHandler ───────────────────────────────────────────────────────

    override fun onI2PStateChanged(state: String) {
        runOnUiThread { updateI2PStatusBanner(state) }
    }

    private fun updateI2PStatusBanner(state: String) {
        when (state) {
            I2PKabuService.I2P_STATE_BOOTSTRAPPING -> {
                layoutI2PStatus.visibility = android.view.View.VISIBLE
                progressI2P.visibility     = android.view.View.VISIBLE
                tvI2PStatus.text           = getString(R.string.i2p_status_bootstrapping)
            }
            I2PKabuService.I2P_STATE_BUILDING_TUNNELS -> {
                layoutI2PStatus.visibility = android.view.View.VISIBLE
                progressI2P.visibility     = android.view.View.VISIBLE
                tvI2PStatus.text           = getString(R.string.i2p_status_building_tunnels)
            }
            I2PKabuService.I2P_STATE_READY -> {
                // Show "ready" briefly then hide the banner
                progressI2P.visibility = android.view.View.GONE
                tvI2PStatus.text       = getString(R.string.i2p_status_ready)
                layoutI2PStatus.postDelayed({
                    layoutI2PStatus.animate().alpha(0f).setDuration(600).withEndAction {
                        layoutI2PStatus.visibility = android.view.View.GONE
                        layoutI2PStatus.alpha      = 1f
                    }.start()
                }, 2000)
            }
            I2PKabuService.I2P_STATE_ERROR -> {
                progressI2P.visibility = android.view.View.GONE
                tvI2PStatus.text       = getString(R.string.i2p_status_error)
                tvI2PStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this, android.R.color.holo_red_light)
                )
            }
        }
    }
    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            tvStatus.text = if (connected)
                getString(R.string.status_connected)
            else
                getString(R.string.status_disconnected)
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showPrivacyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.privacy_title)
            .setMessage(R.string.privacy_body)
            .setPositiveButton(R.string.accept) { _, _ ->
                policyAccepted = true
                sharedPreferences.edit().putBoolean(KEY_POLICY_ACCEPTED, true).apply()
                initAfterConsent()
            }
            .setNegativeButton(R.string.decline) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showPhoneRegistrationDialog() {
        val input = EditText(this).apply { hint = getString(R.string.phone_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.register_title)
            .setMessage(R.string.register_body)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val phone = input.text.toString().trim()
                if (phone.isNotEmpty()) registerDevice(phone)
            }
            .setCancelable(false)
            .show()
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun bindViews() {
        tvStatus       = findViewById(R.id.tvStatus)
        layoutI2PStatus = findViewById(R.id.layoutI2PStatus)
        progressI2P     = findViewById(R.id.progressI2P)
        tvI2PStatus     = findViewById(R.id.tvI2PStatus)
        tvGeohash      = findViewById(R.id.tvGeohash)
        tvRole         = findViewById(R.id.tvRole)
        btnRequestRide = findViewById(R.id.btnRequestRide)
        btnSwitchRole  = findViewById(R.id.btnSwitchRole)
        etNote         = findViewById(R.id.etNote)
        rvOffers       = findViewById(R.id.rvOffers)

        btnRequestRide.setOnClickListener { onRequestRideTapped() }
        btnSwitchRole.setOnClickListener  {
            if (currentRole == "rider") {
                // Persist role then launch the dedicated driver UI
                currentRole = "driver"
                sharedPreferences.edit().putString(KEY_ROLE, currentRole).apply()
                startActivity(Intent(this, DriverActivity::class.java))
            } else {
                currentRole = "rider"
                sharedPreferences.edit().putString(KEY_ROLE, currentRole).apply()
                tvRole.text = currentRole.replaceFirstChar { it.uppercaseChar() }
            }
        }
        tvRole.text = currentRole.replaceFirstChar { it.uppercaseChar() }
    }

    private fun setupRecyclerView() {
        offerAdapter = OfferAdapter(receivedOffers) { offer ->
            // Route through balance check before accepting
            onDriverOfferAccepted(offer)
        }
        rvOffers.layoutManager = LinearLayoutManager(this)
        rvOffers.adapter = offerAdapter
    }
}
