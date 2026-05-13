package com.techducat.kabukabu.network

import ch.hsr.geohash.GeoHash
import ch.hsr.geohash.WGS84Point
import com.techducat.kabukabu.BuildConfig

/**
 * GeoHashPrivacyUtil — location anonymisation helpers.
 *
 * Central place for all coordinate → geohash conversions so that
 * raw GPS values are guaranteed never to leak into any message or DB row.
 *
 * GeoHash precision chosen:
 *   Precision 5 → ~4.9 km × 4.9 km cell
 *   Precision 4 → ~39 km × 20 km cell  (neighbourhood discovery)
 *   Precision 3 → ~156 km × 156 km     (city-level routing fallback)
 *
 * The precision used for actual matching is [MATCH_PRECISION] = 5.
 * [DISCOVERY_PRECISION] = 4 is used to find peers in adjacent zones.
 */
object GeoHashPrivacyUtil {

    /** ~5 km cell — used in all transmitted RideRequest / TripEvent messages */
    const val MATCH_PRECISION:     Int = BuildConfig.GEOHASH_PRECISION   // 5

    /** ~20 km neighbourhood — used for peer discovery queries */
    const val DISCOVERY_PRECISION: Int = 4

    /**
     * Convert raw GPS coordinates to a GeoHash-5 string.
     * This is the ONLY function that should ever receive raw lat/lng
     * from the GPS sensor. After this call, discard the raw values.
     */
    fun toMatchCell(latitude: Double, longitude: Double): String =
        GeoHash.withCharacterPrecision(latitude, longitude, MATCH_PRECISION).toBase32()

    /**
     * Wider cell used for peer-discovery broadcasts.
     * Dropping to precision-4 means ~20 km coverage, catching drivers
     * in adjacent GeoHash-5 cells who can still reach the pickup zone.
     */
    fun toDiscoveryCell(latitude: Double, longitude: Double): String =
        GeoHash.withCharacterPrecision(latitude, longitude, DISCOVERY_PRECISION).toBase32()

    /**
     * Returns the GeoHash-5 strings for all 9 neighbours of [geohash]
     * (the cell itself + 8 surrounding cells). Used so a rider's request
     * is visible to drivers in adjacent cells.
     */
    fun neighbourCells(geohash: String): List<String> {
        return try {
            val gh        = GeoHash.fromGeohashString(geohash)
            val adjacent  = gh.adjacent
            buildList {
                add(geohash)
                adjacent?.forEach { add(it.toBase32()) }
            }.distinct()
        } catch (_: Exception) {
            listOf(geohash)
        }
    }

    /**
     * Approximate cell-centre coordinates for a GeoHash string.
     * Used ONLY for map display (OsmDroid tile centring), never for
     * any message sent over the network.
     */
    fun cellCentreForDisplay(geohash: String): Pair<Double, Double>? {
        return try {
            val gh = GeoHash.fromGeohashString(geohash)
            val pt: WGS84Point = gh.boundingBox.centerPoint
            Pair(pt.latitude, pt.longitude)
        } catch (_: Exception) { null }
    }

    /**
     * Returns true if two geohash cells are "nearby" — share at least
     * [minSharedChars] prefix characters.
     *   4 chars → within ~20 km
     *   3 chars → within ~80 km
     */
    fun isNearby(a: String, b: String, minSharedChars: Int = 4): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a.take(minSharedChars) == b.take(minSharedChars)
    }

    /**
     * Human-readable zone label suitable for display in the UI.
     * Shows only the first 4 characters (discovery precision) to avoid
     * giving users the impression they can derive an exact address.
     */
    fun displayLabel(geohash: String): String =
        if (geohash.length >= 4) "Zone ${geohash.take(4).uppercase()}" else "Zone —"
}
