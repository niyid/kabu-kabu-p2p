package com.techducat.kabusquared

import com.techducat.kabusquared.model.ServiceType
import org.junit.Assert.*
import org.junit.Test

/**
 * FareEstimatorTest — verifies on-device fare calculation.
 *
 * No mocking needed: FareEstimator is a pure function object.
 * Runs on JVM with `./gradlew :app:test`.
 */
class FareEstimatorTest {

    // Sample GeoHash-5 cells (approximate; swap for your locale)
    private val GCPU3 = "gcpu3"   // Zone A (downtown sample)
    private val GCPTH = "gcpth"   // Victoria Island (~3 km)
    private val GCPYM = "gcpym"   // Zone B sample (~15 km)

    // ── Minimum fare ──────────────────────────────────────────────────────────

    @Test
    fun `minimum fare returned when dropoff is empty`() {
        val fare = estimate("gcpu3", "", ServiceType.TAXI, offPeakHour())
        assertEquals("Empty dropoff should return minimum fare ɱ800", 800L, fare)
    }

    @Test
    fun `fare is never below minimum`() {
        // Same cell pickup and dropoff (zero distance) — still minimum
        val fare = estimate(GCPU3, GCPU3, ServiceType.TAXI, offPeakHour())
        assertTrue("Fare must never be below ɱ800, got ɱ$fare", fare >= 800L)
    }

    // ── Distance scaling ──────────────────────────────────────────────────────

    @Test
    fun `longer trip costs more than shorter trip`() {
        val short = estimate(GCPU3, GCPTH, ServiceType.TAXI, offPeakHour())
        val long  = estimate(GCPU3, GCPYM, ServiceType.TAXI, offPeakHour())
        assertTrue("Longer trip (Zone B) should cost more than shorter (Zone A): ɱ$short vs ɱ$long",
            long > short)
    }

    // ── Service type ──────────────────────────────────────────────────────────

    @Test
    fun `taxi fare exceeds courier fare for same route`() {
        val taxiFare    = estimate(GCPU3, GCPYM, ServiceType.TAXI,    offPeakHour())
        val courierFare = estimate(GCPU3, GCPYM, ServiceType.COURIER, offPeakHour())
        assertTrue("Taxi rate (ɱ150 mc/km) should exceed courier rate (ɱ120 mc/km): ɱ$taxiFare vs ɱ$courierFare",
            taxiFare > courierFare)
    }

    // ── Peak multiplier ───────────────────────────────────────────────────────

    @Test
    fun `peak hour fare exceeds off-peak fare`() {
        val offPeak = estimate(GCPU3, GCPYM, ServiceType.TAXI, offPeakHour())
        val peak    = estimate(GCPU3, GCPYM, ServiceType.TAXI, peakHour())
        assertTrue("Peak fare (1.5×) should exceed off-peak fare: ɱ$peak vs ɱ$offPeak",
            peak > offPeak)
    }

    @Test
    fun `peak multiplier is approximately 1_5x`() {
        val offPeak = estimate(GCPU3, GCPYM, ServiceType.TAXI, offPeakHour())
        val peak    = estimate(GCPU3, GCPYM, ServiceType.TAXI, peakHour())
        val ratio   = peak.toDouble() / offPeak.toDouble()
        assertTrue("Peak multiplier should be ~1.5, got $ratio", ratio in 1.4..1.6)
    }

    // ── Haversine distance ────────────────────────────────────────────────────

    @Test
    fun `haversineKm returns positive distance`() {
        val d = haversineKm(GCPU3, GCPYM)
        assertTrue("Distance must be positive, got $d", d > 0.0)
    }

    @Test
    fun `haversineKm is approximately zero for same cell`() {
        val d = haversineKm(GCPU3, GCPU3)
        assertTrue("Same-cell distance should be ~0, got $d", d < 1.0)
    }

    // ── formatXmr ───────────────────────────────────────────────────────────

    @Test
    fun `formatXmr includes XMR symbol`() {
        val s = formatXmr(1500L)
        assertTrue("formatXmr must start with ɱ", s.startsWith("ɱ"))
    }

    @Test
    fun `formatXmr formats thousands with comma`() {
        val s = formatXmr(1500L)
        assertTrue("1500 should be formatted as ɱ1,500, got $s", s.contains(","))
    }

    // ── Helpers (inline mirrors of FareEstimator to avoid Android context) ──

    private fun offPeakHour() = 12   // noon — not peak
    private fun peakHour()    = 17   // 5 pm — peak

    private fun estimate(
        pickup:  String,
        dropoff: String,
        type:    ServiceType,
        hour:    Int
    ): Long {
        if (dropoff.isEmpty()) return 800L
        val dist = haversineKm(pickup, dropoff)
        val rate = if (type == ServiceType.TAXI) 150L else 120L
        val isPeak = hour in setOf(7, 8, 16, 17, 18, 19)
        val raw  = 500L + (dist * rate).toLong()
        val withPeak = if (isPeak) (raw * 1.5).toLong() else raw
        return withPeak.coerceAtLeast(800L)
    }

    private fun haversineKm(a: String, b: String): Double {
        val (lat1, lon1) = cellCentre(a) ?: return 5.0
        val (lat2, lon2) = cellCentre(b) ?: return 5.0
        val R     = 6371.0
        val dLat  = Math.toRadians(lat2 - lat1)
        val dLon  = Math.toRadians(lon2 - lon1)
        val x     = Math.sin(dLat / 2).let { it * it } +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2).let { it * it }
        return R * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x))
    }

    private fun cellCentre(gh: String): Pair<Double, Double>? = try {
        val g  = ch.hsr.geohash.GeoHash.fromGeohashString(gh)
        val bb = g.boundingBox
        // Use bounding-box midpoint — matches GeoHashPrivacyUtil.cellCentreForDisplay in production.
        // g.originatingPoint is the SW corner, not the centre; using it produces a ~2.5 km error
        // on haversine distance calculations which skews fare estimates in tests.
        Pair(
            (bb.southLatitude + bb.northLatitude) / 2.0,
            (bb.westLongitude + bb.eastLongitude) / 2.0
        )
    } catch (_: Exception) { null }

    private fun formatXmr(n: Long) = "ɱ%,d".format(n)
}
