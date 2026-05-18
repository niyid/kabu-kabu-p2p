package com.techducat.kabukabu.model

/**
 * RideRequest — the unit of information broadcast over the I2P network.
 *
 * Privacy invariants:
 *  - [pickupGeohash] and [dropoffGeohash] are GeoHash-5 cells (~5 km).
 *    Exact coordinates are NEVER included.
 *  - [riderId] is a SHA-256 hash of the rider's phone number.
 *  - No name, address, or contact info is transmitted in this object.
 *  - [fareEstimateXMR] is computed on-device from the geohash distance
 *    and a locally-stored rate table; no pricing server is contacted.
 *
 * Flow:
 *   Rider creates RideRequest → I2PKabuClient broadcasts over I2P →
 *   Drivers in adjacent GeoHash-5 cells receive it → Driver accepts →
 *   Encrypted direct I2P tunnel opened between rider & driver devices.
 */
data class RideRequest(
    val requestId:        String,          // UUID generated on device
    val riderId:          String,          // SHA-256(phoneNumber)
    val pickupGeohash:    String,          // GeoHash precision 5
    val dropoffGeohash:   String,          // GeoHash precision 5
    val serviceType:      ServiceType,     // TAXI | COURIER
    val fareEstimateXMR:  Long,            // display millicents (ɱ); convert to piconero before WalletSuite calls
    val noteForDriver:    String,          // Optional free text (package desc, etc.)
    val timestamp:        Long,            // Unix millis (sender clock)
    val ttlMs:            Long = 10 * 60 * 1000L,  // Expire after 10 min
    val status:           RequestStatus = RequestStatus.OPEN
)

enum class ServiceType { TAXI, COURIER }

enum class RequestStatus { OPEN, ACCEPTED, IN_PROGRESS, COMPLETED, CANCELLED }

/**
 * DriverOffer — sent by a driver in response to a [RideRequest].
 *
 * Privacy: Only the driver's I2P destination address (opaque 512-byte blob)
 * is shared, never a real phone number or GPS coordinate.
 */
data class DriverOffer(
    val offerId:          String,
    val requestId:        String,          // links back to the RideRequest
    val driverId:         String,          // SHA-256(phoneNumber)
    val driverI2pDest:    String,          // I2P destination for direct tunnel
    val etaMinutes:       Int,             // Estimated minutes to pickup geohash
    val vehicleType:      String,          // "Keke", "Car", "Bike", "Truck" etc.
    val ratingScore:      Float,           // 0–5, computed from local peer reviews
    val counterFareXMR:   Long?,           // null = accept rider's estimate (display millicents ɱ; convert before WalletSuite)
    val timestamp:        Long
)

/**
 * TripEvent — ephemeral status update during an active trip.
 *
 * Sent over the encrypted device-to-device I2P tunnel only;
 * never goes through any broker or relay.
 *
 * The [currentGeohash] is updated as the driver moves, giving the rider
 * a ~5 km accuracy view — precise enough to track progress, too coarse
 * to build a surveillance-grade location trail.
 */
data class TripEvent(
    val tripId:           String,
    val driverId:         String,
    val eventType:        TripEventType,
    val currentGeohash:   String,          // Driver's current GeoHash-5 cell
    val messageText:      String = "",     // Optional human message
    val timestamp:        Long
)

enum class TripEventType {
    DRIVER_EN_ROUTE,
    DRIVER_ARRIVED,
    TRIP_STARTED,
    TRIP_COMPLETED,
    TRIP_CANCELLED
}
