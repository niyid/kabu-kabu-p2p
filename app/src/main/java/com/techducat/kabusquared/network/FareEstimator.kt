package com.techducat.kabusquared.network

import com.techducat.kabusquared.model.ServiceType
import kotlin.math.*

/**
 * FareEstimator — fully offline, on-device fare calculation.
 *
 * No pricing server is contacted. Fares are computed from:
 *   1. Approximate distance between two GeoHash-5 cells (Haversine formula
 *      applied to the cell centres).
 *   2. A locally-stored rate table of generic market rates.
 *   3. A time-of-day multiplier (peak hours).
 *
 * ## Fare units
 *
 * The values produced by [estimate] are in **display millicents** (abbreviated
 * as ɱ in the UI), where 1 XMR = 100 000 ɱ.  These are NOT Monero atomic
 * units (piconero, where 1 XMR = 1 000 000 000 000 pico).
 *
 * **IMPORTANT**: Before passing a fare estimate to any [WalletSuite] method
 * (which expects piconero), convert with:
 *
 *   val piconero = fareMillicents * 10_000_000L   // 1 ɱ = 10^7 piconero
 *
 * The [RideRequest.fareEstimateXMR], [DriverOffer.counterFareXMR], and the
 * `fare_xmr` I2P message fields all carry **millicents** as produced here.
 * [WalletSuite.convertAtomicToXmr] / [WalletSuite.checkBalanceForRide] work
 * in piconero — always convert at the wallet boundary.
 *
 * Privacy: all inputs and outputs stay on-device. The fare estimate
 * carried in [RideRequest.fareEstimateXMR] is computed here before
 * being broadcast, so no server ever learns how it was derived.
 *
 * Rate table (ɱ = display millicents; 1 XMR = 100 000 ɱ):
 *   Base flag-fall:     ɱ0.0050  (500 ɱ)
 *   Per km (taxi):      ɱ0.0015  (150 ɱ) / km
 *   Per km (courier):   ɱ0.0012  (120 ɱ) / km
 *   Peak multiplier:    1.5× (07:00–09:00, 16:00–20:00 WAT)
 *   Minimum fare:       ɱ0.0080  (800 ɱ)
 */
object FareEstimator {

    // ── Rate table ────────────────────────────────────────────────────────────

    private const val BASE_FARE_XMR          = 500L
    private const val TAXI_RATE_PER_KM       = 150L
    private const val COURIER_RATE_PER_KM    = 120L
    private const val PEAK_MULTIPLIER        = 1.5
    private const val MINIMUM_FARE_XMR       = 800L
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
     * @return                Estimated fare in XMR millicents (ɱ)
     */
    fun estimate(
        pickupGeohash:  String,
        dropoffGeohash: String,
        serviceType:    ServiceType,
        hourOfDayWAT:   Int = currentHourWAT()
    ): Long {
        // If no dropoff given, return minimum fare
        if (dropoffGeohash.isEmpty()) return MINIMUM_FARE_XMR

        val distanceKm = haversineKm(pickupGeohash, dropoffGeohash)
        val ratePerKm  = if (serviceType == ServiceType.TAXI) TAXI_RATE_PER_KM else COURIER_RATE_PER_KM
        val isPeak     = hourOfDayWAT in PEAK_HOURS

        val raw = BASE_FARE_XMR + (distanceKm * ratePerKm).toLong()
        val withPeak = if (isPeak) (raw * PEAK_MULTIPLIER).toLong() else raw

        return withPeak.coerceAtLeast(MINIMUM_FARE_XMR)
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
     * Format an XMR millicent amount for display: ɱ1,500
     */
    fun formatXmr(amountXmr: Long): String =
        "ɱ%,d".format(amountXmr)

    /**
     * Convert a display-millicent fare amount to Monero piconero (atomic units)
     * for use with [WalletSuite] JNI methods.
     *
     *   1 ɱ (display millicent) = 10^7 piconero
     *   1 XMR                   = 100 000 ɱ = 10^12 piconero
     */
    fun toAtomicUnits(fareMillicents: Long): Long = fareMillicents * 10_000_000L

    /**
     * Convert Monero piconero (atomic units) back to display millicents.
     */
    fun fromAtomicUnits(piconero: Long): Long = piconero / 10_000_000L

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun cellCentre(geohash: String): Pair<Double, Double>? =
        GeoHashPrivacyUtil.cellCentreForDisplay(geohash)

    private fun currentHourWAT(): Int {
        val utcHour = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .get(java.util.Calendar.HOUR_OF_DAY)
        return (utcHour + 1) % 24   // WAT = UTC+1
    }
}
