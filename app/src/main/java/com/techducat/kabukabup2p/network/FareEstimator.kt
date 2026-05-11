package com.techducat.kabukabup2p.network

import com.techducat.kabukabup2p.model.ServiceType
import kotlin.math.*

/**
 * FareEstimator — fully offline, on-device fare calculation.
 *
 * No pricing server is contacted. Fares are computed from:
 *   1. Approximate distance between two GeoHash-5 cells (Haversine formula
 *      applied to the cell centres).
 *   2. A locally-stored rate table for Lagos / Nigerian market rates.
 *   3. A time-of-day multiplier (peak hours).
 *
 * Privacy: all inputs and outputs stay on-device. The fare estimate
 * carried in [RideRequest.fareEstimateNGN] is computed here before
 * being broadcast, so the server (which doesn't exist) never needs
 * to know how it was derived.
 *
 * Rate table (as of Q1 2025, Lagos):
 *   Base flag-fall:     ₦500
 *   Per km (taxi):      ₦150 / km
 *   Per km (courier):   ₦120 / km  (lighter load, smaller vehicle)
 *   Peak multiplier:    1.5× (07:00–09:00, 16:00–20:00 WAT)
 *   Minimum fare:       ₦800
 */
object FareEstimator {

    // ── Rate table ────────────────────────────────────────────────────────────

    private const val BASE_FARE_NGN          = 500L
    private const val TAXI_RATE_PER_KM       = 150L
    private const val COURIER_RATE_PER_KM    = 120L
    private const val PEAK_MULTIPLIER        = 1.5
    private const val MINIMUM_FARE_NGN       = 800L
    private const val EARTH_RADIUS_KM        = 6371.0

    // Peak hours in WAT (UTC+1): 07–09 morning, 16–20 evening
    private val PEAK_HOURS = setOf(7, 8, 16, 17, 18, 19)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Estimate fare between two GeoHash-5 cells.
     *
     * @param pickupGeohash   GeoHash-5 string of pickup zone
     * @param dropoffGeohash  GeoHash-5 string of dropoff zone (empty = unknown)
     * @param serviceType     TAXI or COURIER
     * @param hourOfDayWAT    Current hour in West Africa Time (0–23); default = now
     * @return                Estimated fare in Nigerian Naira (₦)
     */
    fun estimate(
        pickupGeohash:  String,
        dropoffGeohash: String,
        serviceType:    ServiceType,
        hourOfDayWAT:   Int = currentHourWAT()
    ): Long {
        // If no dropoff given, return minimum fare
        if (dropoffGeohash.isEmpty()) return MINIMUM_FARE_NGN

        val distanceKm = haversineKm(pickupGeohash, dropoffGeohash)
        val ratePerKm  = if (serviceType == ServiceType.TAXI) TAXI_RATE_PER_KM else COURIER_RATE_PER_KM
        val isPeak     = hourOfDayWAT in PEAK_HOURS

        val raw = BASE_FARE_NGN + (distanceKm * ratePerKm).toLong()
        val withPeak = if (isPeak) (raw * PEAK_MULTIPLIER).toLong() else raw

        return withPeak.coerceAtLeast(MINIMUM_FARE_NGN)
    }

    /**
     * Approximate distance in km between two GeoHash cell centres.
     * Uses Haversine formula on the cell-centre lat/lng.
     */
    fun haversineKm(geohashA: String, geohashB: String): Double {
        val (lat1, lon1) = cellCentre(geohashA) ?: return 5.0  // default ~1 cell width
        val (lat2, lon2) = cellCentre(geohashB) ?: return 5.0

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) +
                   cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                   sin(dLon / 2).pow(2)
        val c    = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    /**
     * Format a Naira amount for display: ₦1,500
     */
    fun formatNaira(amountNGN: Long): String =
        "₦%,d".format(amountNGN)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun cellCentre(geohash: String): Pair<Double, Double>? =
        GeoHashPrivacyUtil.cellCentreForDisplay(geohash)

    private fun currentHourWAT(): Int {
        val utcHour = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .get(java.util.Calendar.HOUR_OF_DAY)
        return (utcHour + 1) % 24   // WAT = UTC+1
    }
}
