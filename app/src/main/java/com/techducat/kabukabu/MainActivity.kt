package com.techducat.kabukabu

import android.Manifest
import android.app.AlertDialog
import android.content.Context
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 */
@AndroidEntryPoint
class MainActivity :
    AppCompatActivity(),
    I2PKabuClient.RideRequestHandler,
    I2PKabuClient.DriverOfferHandler,
    I2PKabuClient.TripEventHandler,
    I2PKabuClient.PeerStatusHandler {

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

    // ── Location ──────────────────────────────────────────────────────────────

    private lateinit var fusedLocationClient:  FusedLocationProviderClient
    private lateinit var locationCallback:     LocationCallback

    // ── P2P client ────────────────────────────────────────────────────────────

    private lateinit var i2pClient: I2PKabuClient

    // ── UI ────────────────────────────────────────────────────────────────────

    private lateinit var tvStatus:         TextView
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
        } else {
            showPhoneRegistrationDialog()
        }
    }

    private fun registerDevice(phoneNumber: String) {
        deviceId = sha256(phoneNumber)
        sharedPreferences.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        isRegistered = true
        tvStatus.text = getString(R.string.status_registered)
        startI2PService()
        requestLocationPermissions()
    }

    private fun startI2PService() {
        I2PKabuService.startService(this)
        lifecycleScope.launch {
            // Wait briefly for service to bind
            kotlinx.coroutines.delay(2000)
            if (lastKnownGeohash.isNotEmpty()) {
                val ok = i2pClient.initialize(deviceId, lastKnownGeohash, currentRole)
                Log.i(TAG, "I2PKabuClient initialized: $ok")
            }
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

            if (isRegistered && old.isNotEmpty()) {
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
        // Dropoff geohash is left empty at this stage; driver will confirm pick-up first.
        val request = RideRequest(
            requestId       = UUID.randomUUID().toString(),
            riderId         = deviceId,
            pickupGeohash   = lastKnownGeohash,
            dropoffGeohash  = "",   // rider describes destination in note until map tap is added
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

    /** Simple fare estimate based on geohash cell size. Real implementation uses osrm offline. */
    private fun estimateFare(): Long = 500L   // ɱ500 base (mc) — placeholder

    // ── I2PKabuClient callbacks ───────────────────────────────────────────────

    override fun onRideRequestReceived(request: RideRequest) {
        // Only drivers see incoming ride requests (MainActivity is rider mode)
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
            val msg = when (event.eventType) {
                TripEventType.DRIVER_EN_ROUTE  -> getString(R.string.event_en_route)
                TripEventType.DRIVER_ARRIVED   -> getString(R.string.event_arrived)
                TripEventType.TRIP_STARTED     -> getString(R.string.event_trip_started)
                TripEventType.TRIP_COMPLETED   -> getString(R.string.event_trip_completed)
                TripEventType.TRIP_CANCELLED   -> getString(R.string.event_trip_cancelled)
            }
            tvStatus.text = msg
        }
    }

    override fun onPeerOnline(peerId: String)  { Log.d(TAG, "Peer online: $peerId") }
    override fun onPeerOffline(peerId: String) { Log.d(TAG, "Peer offline: $peerId") }
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
        tvGeohash      = findViewById(R.id.tvGeohash)
        tvRole         = findViewById(R.id.tvRole)
        btnRequestRide = findViewById(R.id.btnRequestRide)
        btnSwitchRole  = findViewById(R.id.btnSwitchRole)
        etNote         = findViewById(R.id.etNote)
        rvOffers       = findViewById(R.id.rvOffers)

        btnRequestRide.setOnClickListener { onRequestRideTapped() }
        btnSwitchRole.setOnClickListener  {
            currentRole = if (currentRole == "rider") "driver" else "rider"
            sharedPreferences.edit().putString(KEY_ROLE, currentRole).apply()
            tvRole.text = currentRole.replaceFirstChar { it.uppercaseChar() }
        }
        tvRole.text = currentRole.replaceFirstChar { it.uppercaseChar() }
    }

    private fun setupRecyclerView() {
        offerAdapter = OfferAdapter(receivedOffers) { offer ->
            lifecycleScope.launch {
                i2pClient.acceptOffer(offer.offerId, offer.requestId)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.offer_accepted, Toast.LENGTH_SHORT).show()
                }
            }
        }
        rvOffers.layoutManager = LinearLayoutManager(this)
        rvOffers.adapter = offerAdapter
    }
}
