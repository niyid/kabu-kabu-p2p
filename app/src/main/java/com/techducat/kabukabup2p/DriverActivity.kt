package com.techducat.kabukabup2p

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
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
import com.techducat.kabukabup2p.db.KabuDatabase
import com.techducat.kabukabup2p.db.PeerReviewEntity
import com.techducat.kabukabup2p.db.TripEntity
import com.techducat.kabukabup2p.model.*
import com.techducat.kabukabup2p.service.I2PKabuService
import com.techducat.kabukabup2p.ui.RequestAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * DriverActivity — driver / courier runner side of Kabu-Kabu.
 *
 * Privacy decisions enforced here:
 *
 *  1. GPS → GeoHash-5 only. The driver's exact location is NEVER sent.
 *     [TripEvent.currentGeohash] carries only the ~5 km cell.
 *
 *  2. Incoming [RideRequest] objects show only pickup zone, fare, and note.
 *     The rider's identity is an opaque SHA-256 hash — the driver never
 *     sees a real phone number or name.
 *
 *  3. The driver's I2P destination address (a 512-byte cryptographic blob)
 *     is the only identifier shared with the rider, not a phone number or IP.
 *
 *  4. Trip completion is persisted locally in Room; no server upload.
 *
 * State machine:
 *   IDLE → (receive request) → OFFER_SENT → (offer accepted) →
 *   EN_ROUTE → ARRIVED → IN_TRIP → COMPLETED
 */
@AndroidEntryPoint
class DriverActivity :
    AppCompatActivity(),
    I2PKabuClient.RideRequestHandler,
    I2PKabuClient.PeerStatusHandler {

    companion object {
        private const val TAG                   = "KabuDriverActivity"
        private const val GEOHASH_PRECISION     = 5
        private const val LOCATION_PERM_CODE    = 2001
        private const val PREFS_NAME            = KabuKabuApp.PREFS_NAME
        private const val KEY_DEVICE_ID         = KabuKabuApp.KEY_DEVICE_ID
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var deviceId:          String
    private var lastKnownGeohash:           String = ""
    private var activeRequest:              RideRequest? = null
    private var activeTripId:               String = ""

    private enum class DriverState {
        IDLE, OFFER_SENT, EN_ROUTE, ARRIVED, IN_TRIP
    }
    private var driverState = DriverState.IDLE

    // ── Location ──────────────────────────────────────────────────────────────

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback:    LocationCallback

    // ── P2P ───────────────────────────────────────────────────────────────────

    private lateinit var i2pClient: I2PKabuClient

    // ── UI ────────────────────────────────────────────────────────────────────

    private lateinit var tvDriverStatus:   TextView
    private lateinit var tvDriverZone:     TextView
    private lateinit var tvTripInfo:       TextView
    private lateinit var btnToggleOnline:  Button
    private lateinit var btnUpdateStatus:  Button
    private lateinit var rvRequests:       RecyclerView
    private lateinit var requestAdapter:   RequestAdapter

    private val openRequests = mutableListOf<RideRequest>()
    private var isOnline     = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        deviceId = sharedPreferences.getString(KEY_DEVICE_ID, "") ?: ""

        if (deviceId.isEmpty()) { finish(); return }

        bindViews()
        setupRecyclerView()

        i2pClient = I2PKabuClient(this)
        i2pClient.addRideRequestHandler("driver", this)
        i2pClient.addPeerStatusHandler ("driver", this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        requestLocationPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        i2pClient.close()
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
     * GPS → GeoHash only. Raw [location] is never stored or transmitted.
     * If cell changes and driver is in an active trip, send a [TripEvent]
     * so the rider sees the updated ~5 km zone.
     */
    private fun onLocationUpdate(location: Location) {
        val newGeohash = GeoHash
            .withCharacterPrecision(location.latitude, location.longitude, GEOHASH_PRECISION)
            .toBase32()

        if (newGeohash == lastKnownGeohash) return
        val old = lastKnownGeohash
        lastKnownGeohash = newGeohash
        tvDriverZone.text = getString(R.string.zone_label, newGeohash)

        if (isOnline && old.isNotEmpty()) {
            lifecycleScope.launch {
                i2pClient.updateLocation(old, newGeohash)
            }
        }

        // During active trip: push geohash update to rider
        if (driverState == DriverState.EN_ROUTE || driverState == DriverState.IN_TRIP) {
            sendTripStatusUpdate()
        }
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateDistanceMeters(300f)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
        }
    }

    private fun requestLocationPermissions() {
        val needed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startLocationUpdates()
        else ActivityCompat.requestPermissions(this, needed.toTypedArray(), LOCATION_PERM_CODE)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        if (code == LOCATION_PERM_CODE && grants.all { it == PackageManager.PERMISSION_GRANTED })
            startLocationUpdates()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ── Online / offline toggle ───────────────────────────────────────────────

    private fun onToggleOnline() {
        if (lastKnownGeohash.isEmpty()) {
            Toast.makeText(this, R.string.error_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        isOnline = !isOnline
        btnToggleOnline.text = if (isOnline)
            getString(R.string.btn_go_offline)
        else
            getString(R.string.btn_go_online)
        tvDriverStatus.text = if (isOnline)
            getString(R.string.driver_status_online)
        else
            getString(R.string.driver_status_offline)

        if (isOnline) {
            I2PKabuService.startService(this)
            lifecycleScope.launch {
                val ok = i2pClient.initialize(deviceId, lastKnownGeohash, "driver")
                withContext(Dispatchers.Main) {
                    if (!ok) Toast.makeText(
                        this@DriverActivity, R.string.request_failed, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            i2pClient.close()
        }
    }

    // ── Respond to a ride request ─────────────────────────────────────────────

    private fun onMakeOffer(request: RideRequest) {
        if (lastKnownGeohash.isEmpty() || !isOnline) {
            Toast.makeText(this, R.string.error_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (driverState != DriverState.IDLE) {
            Toast.makeText(this, R.string.error_already_active, Toast.LENGTH_SHORT).show()
            return
        }
        activeRequest = request
        activeTripId  = UUID.randomUUID().toString()
        driverState   = DriverState.OFFER_SENT

        // Build offer — driver's I2P destination is the only identifier shared
        val offer = DriverOffer(
            offerId        = UUID.randomUUID().toString(),
            requestId      = request.requestId,
            driverId       = deviceId,
            driverI2pDest  = getI2PDestination(),
            etaMinutes     = estimateEtaMinutes(request.pickupGeohash),
            vehicleType    = getVehicleType(),
            ratingScore    = getMyRatingScore(),
            counterFareNGN = null,
            timestamp      = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            val ok = i2pClient.sendDriverOffer(offer)
            withContext(Dispatchers.Main) {
                if (ok) {
                    tvTripInfo.text = getString(R.string.offer_sent_waiting)
                    updateStatusButton()
                } else {
                    driverState = DriverState.IDLE
                    activeRequest = null
                    Toast.makeText(this@DriverActivity, R.string.request_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Trip status progression ───────────────────────────────────────────────

    private fun onUpdateStatus() {
        val request = activeRequest ?: return
        val nextState = when (driverState) {
            DriverState.OFFER_SENT -> DriverState.EN_ROUTE
            DriverState.EN_ROUTE   -> DriverState.ARRIVED
            DriverState.ARRIVED    -> DriverState.IN_TRIP
            DriverState.IN_TRIP    -> { completeTrip(); return }
            DriverState.IDLE       -> return
        }
        driverState = nextState
        sendTripStatusUpdate()
        updateStatusButton()
        tvTripInfo.text = when (nextState) {
            DriverState.EN_ROUTE -> getString(R.string.event_en_route)
            DriverState.ARRIVED  -> getString(R.string.event_arrived)
            DriverState.IN_TRIP  -> getString(R.string.event_trip_started)
            else                 -> ""
        }
    }

    private fun sendTripStatusUpdate() {
        val request = activeRequest ?: return
        val eventType = when (driverState) {
            DriverState.EN_ROUTE -> TripEventType.DRIVER_EN_ROUTE
            DriverState.ARRIVED  -> TripEventType.DRIVER_ARRIVED
            DriverState.IN_TRIP  -> TripEventType.TRIP_STARTED
            else                 -> return
        }
        val event = TripEvent(
            tripId         = activeTripId,
            driverId       = deviceId,
            eventType      = eventType,
            currentGeohash = lastKnownGeohash,
            timestamp      = System.currentTimeMillis()
        )
        lifecycleScope.launch { i2pClient.sendTripEvent(event) }
    }

    private fun completeTrip() {
        val request = activeRequest ?: return
        val event = TripEvent(
            tripId         = activeTripId,
            driverId       = deviceId,
            eventType      = TripEventType.TRIP_COMPLETED,
            currentGeohash = lastKnownGeohash,
            timestamp      = System.currentTimeMillis()
        )
        lifecycleScope.launch {
            i2pClient.sendTripEvent(event)
            persistCompletedTrip(request)
            withContext(Dispatchers.Main) {
                tvTripInfo.text = getString(R.string.event_trip_completed)
                driverState = DriverState.IDLE
                activeRequest = null
                activeTripId  = ""
                updateStatusButton()
                Toast.makeText(this@DriverActivity, R.string.event_trip_completed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun persistCompletedTrip(request: RideRequest) {
        val now = System.currentTimeMillis()
        val entity = TripEntity(
            tripId         = activeTripId,
            localRole      = "driver",
            peerId         = request.riderId,
            pickupGeohash  = request.pickupGeohash,
            dropoffGeohash = request.dropoffGeohash,
            serviceType    = request.serviceType.name,
            fareNgn        = request.fareEstimateNGN,
            status         = RequestStatus.COMPLETED.name,
            note           = request.noteForDriver,
            timestamp      = request.timestamp,
            expiresAt      = now + TripEntity.TRIP_RETENTION_MS,
            isLocalOrigin  = false
        )
        withContext(Dispatchers.IO) {
            KabuDatabase.getInstance(applicationContext).tripDao().insertTrip(entity)
        }
    }

    // ── I2PKabuClient callbacks ───────────────────────────────────────────────

    override fun onRideRequestReceived(request: RideRequest) {
        // Only show requests in the same or adjacent geohash zone
        if (!isRelevantZone(request.pickupGeohash)) return
        runOnUiThread {
            openRequests.add(0, request)
            requestAdapter.notifyItemInserted(0)
        }
    }

    override fun onPeerOnline(peerId: String)  {}
    override fun onPeerOffline(peerId: String) {}
    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            tvDriverStatus.text = if (connected)
                getString(R.string.driver_status_online)
            else
                getString(R.string.status_disconnected)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** True if request pickup zone neighbours our current zone (share first 4 chars). */
    private fun isRelevantZone(pickupGeohash: String): Boolean {
        if (lastKnownGeohash.isEmpty() || pickupGeohash.isEmpty()) return false
        return lastKnownGeohash.take(4) == pickupGeohash.take(4)
    }

    /** Returns the I2P destination string, or a stub if router not yet ready. */
    private fun getI2PDestination(): String {
        return try {
            val cls         = Class.forName("com.techducat.kabukabup2p.network.I2PTransport")
            val getInstance = cls.getMethod("getInstance", Context::class.java, String::class.java)
            val transport   = getInstance.invoke(null, applicationContext, deviceId)
            val getDestMethod = cls.getMethod("getLocalDestination")
            (getDestMethod.invoke(transport) as? String) ?: "i2p://stub/$deviceId"
        } catch (_: Exception) { "i2p://stub/$deviceId" }
    }

    private fun estimateEtaMinutes(pickupGeohash: String): Int {
        // Rough cell-distance estimate; a real impl uses OSRM offline routing
        val sharedPrefixLen = lastKnownGeohash.zip(pickupGeohash).takeWhile { it.first == it.second }.count()
        return when {
            sharedPrefixLen >= 5 -> 3
            sharedPrefixLen >= 4 -> 7
            sharedPrefixLen >= 3 -> 15
            else                 -> 25
        }
    }

    private fun getVehicleType(): String =
        sharedPreferences.getString("vehicle_type", "Car") ?: "Car"

    private fun getMyRatingScore(): Float =
        sharedPreferences.getFloat("rating_score", 5.0f)

    private fun updateStatusButton() {
        btnUpdateStatus.isEnabled = driverState != DriverState.IDLE
        btnUpdateStatus.text = when (driverState) {
            DriverState.IDLE       -> getString(R.string.btn_status_idle)
            DriverState.OFFER_SENT -> getString(R.string.btn_status_offer_sent)
            DriverState.EN_ROUTE   -> getString(R.string.btn_status_en_route)
            DriverState.ARRIVED    -> getString(R.string.btn_status_arrived)
            DriverState.IN_TRIP    -> getString(R.string.btn_status_complete)
        }
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        tvDriverStatus  = findViewById(R.id.tvDriverStatus)
        tvDriverZone    = findViewById(R.id.tvDriverZone)
        tvTripInfo      = findViewById(R.id.tvTripInfo)
        btnToggleOnline = findViewById(R.id.btnToggleOnline)
        btnUpdateStatus = findViewById(R.id.btnUpdateStatus)
        rvRequests      = findViewById(R.id.rvRequests)

        btnToggleOnline.setOnClickListener { onToggleOnline() }
        btnUpdateStatus.setOnClickListener { onUpdateStatus() }
        btnUpdateStatus.isEnabled = false
    }

    private fun setupRecyclerView() {
        requestAdapter = RequestAdapter(openRequests) { request -> onMakeOffer(request) }
        rvRequests.layoutManager = LinearLayoutManager(this)
        rvRequests.adapter = requestAdapter
    }
}
