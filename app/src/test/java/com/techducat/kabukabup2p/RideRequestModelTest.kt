package com.techducat.kabukabup2p

import com.techducat.kabukabup2p.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * RideRequestModelTest — validates domain model invariants.
 *
 * Privacy-specific tests:
 *  - RideRequest never contains raw lat/lng.
 *  - riderId is always 64-char hex (SHA-256 output), not a phone number.
 *  - pickupGeohash / dropoffGeohash are exactly 5 chars.
 */
class RideRequestModelTest {

    private val sha256PhoneHash =
        "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3" // sha256("hello")

    // ── RideRequest ───────────────────────────────────────────────────────────

    @Test
    fun `RideRequest riderId must be 64-char hex SHA-256`() {
        val req = makeRideRequest()
        assertEquals("riderId must be a 64-char SHA-256 hex string", 64, req.riderId.length)
        assertTrue("riderId must be lowercase hex", req.riderId.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `RideRequest pickupGeohash must be 5 chars`() {
        val req = makeRideRequest()
        assertEquals("pickupGeohash must be exactly 5 chars", 5, req.pickupGeohash.length)
    }

    @Test
    fun `RideRequest dropoffGeohash must be 5 chars when provided`() {
        val req = makeRideRequest()
        if (req.dropoffGeohash.isNotEmpty())
            assertEquals("dropoffGeohash must be exactly 5 chars", 5, req.dropoffGeohash.length)
    }

    @Test
    fun `RideRequest default status is OPEN`() {
        val req = makeRideRequest()
        assertEquals(RequestStatus.OPEN, req.status)
    }

    @Test
    fun `RideRequest default TTL is 10 minutes`() {
        val req = makeRideRequest()
        assertEquals(10 * 60 * 1000L, req.ttlMs)
    }

    @Test
    fun `RideRequest has no phone number fields`() {
        // Verify model data class doesn't expose phone fields
        val fields = RideRequest::class.java.declaredFields.map { it.name }
        assertFalse("RideRequest must not have a 'phone' field", "phone" in fields)
        assertFalse("RideRequest must not have a 'phoneNumber' field", "phoneNumber" in fields)
    }

    @Test
    fun `RideRequest has no GPS coordinate fields`() {
        val fields = RideRequest::class.java.declaredFields.map { it.name }
        assertFalse("RideRequest must not have a 'latitude' field",  "latitude"  in fields)
        assertFalse("RideRequest must not have a 'longitude' field", "longitude" in fields)
        assertFalse("RideRequest must not have a 'lat' field",       "lat"       in fields)
        assertFalse("RideRequest must not have a 'lng' field",       "lng"       in fields)
    }

    // ── DriverOffer ───────────────────────────────────────────────────────────

    @Test
    fun `DriverOffer driverId must be 64-char hex`() {
        val offer = makeDriverOffer()
        assertEquals("driverId must be a 64-char SHA-256 hex string", 64, offer.driverId.length)
    }

    @Test
    fun `DriverOffer has no GPS coordinate fields`() {
        val fields = DriverOffer::class.java.declaredFields.map { it.name }
        assertFalse("DriverOffer must not have a 'latitude' field",  "latitude"  in fields)
        assertFalse("DriverOffer must not have a 'longitude' field", "longitude" in fields)
    }

    @Test
    fun `DriverOffer ratingScore is in range 0 to 5`() {
        val offer = makeDriverOffer()
        assertTrue("ratingScore must be 0–5", offer.ratingScore in 0f..5f)
    }

    // ── TripEvent ─────────────────────────────────────────────────────────────

    @Test
    fun `TripEvent currentGeohash must be 5 chars`() {
        val event = makeTripEvent()
        assertEquals("currentGeohash must be exactly 5 chars", 5, event.currentGeohash.length)
    }

    @Test
    fun `TripEvent has no GPS coordinate fields`() {
        val fields = TripEvent::class.java.declaredFields.map { it.name }
        assertFalse("TripEvent must not have a 'latitude' field",  "latitude"  in fields)
        assertFalse("TripEvent must not have a 'longitude' field", "longitude" in fields)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeRideRequest() = RideRequest(
        requestId       = UUID.randomUUID().toString(),
        riderId         = sha256PhoneHash,
        pickupGeohash   = "gcpu3",
        dropoffGeohash  = "gcpym",
        serviceType     = ServiceType.TAXI,
        fareEstimateNGN = 1200L,
        noteForDriver   = "Please ring bell",
        timestamp       = System.currentTimeMillis()
    )

    private fun makeDriverOffer() = DriverOffer(
        offerId       = UUID.randomUUID().toString(),
        requestId     = UUID.randomUUID().toString(),
        driverId      = sha256PhoneHash,
        driverI2pDest = "i2p://dest/abc123",
        etaMinutes    = 5,
        vehicleType   = "Car",
        ratingScore   = 4.8f,
        counterFareNGN = null,
        timestamp     = System.currentTimeMillis()
    )

    private fun makeTripEvent() = TripEvent(
        tripId         = UUID.randomUUID().toString(),
        driverId       = sha256PhoneHash,
        eventType      = TripEventType.DRIVER_EN_ROUTE,
        currentGeohash = "gcpu3",
        messageText    = "",
        timestamp      = System.currentTimeMillis()
    )
}
