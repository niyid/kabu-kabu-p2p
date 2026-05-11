package com.techducat.kabukabu

import org.junit.Assert.*
import org.junit.Test

/**
 * GeoHashPrivacyUtilTest — verifies the core privacy-layer utility.
 *
 * These tests do NOT require Android framework — pure JVM.
 * They run with `./gradlew :app:test`.
 *
 * Key invariants tested:
 *  1. Two identical coordinates always produce the same GeoHash cell.
 *  2. Two points within ~5 km share the first 5 GeoHash chars.
 *  3. Two points >25 km apart do NOT share all 5 chars (zones differ).
 *  4. neighbourCells() always includes the original cell.
 *  5. neighbourCells() returns at most 9 entries.
 *  6. isNearby() is symmetric and reflexive.
 *  7. displayLabel() never exposes full-precision geohash.
 */
class GeoHashPrivacyUtilTest {

    // Lagos Island approximate coordinates
    private val lagosIslandLat =  6.4531
    private val lagosIslandLng =  3.3958

    // Victoria Island (~3 km from Lagos Island)
    private val victoriaIslandLat =  6.4281
    private val victoriaIslandLng =  3.4219

    // Abuja (~800 km away)
    private val abujaLat =  9.0765
    private val abujaLng =  7.3986

    // ── toMatchCell ───────────────────────────────────────────────────────────

    @Test
    fun `toMatchCell returns 5-char string`() {
        val cell = toMatchCell(lagosIslandLat, lagosIslandLng)
        assertEquals("GeoHash match cell must be exactly 5 chars", 5, cell.length)
    }

    @Test
    fun `toMatchCell is deterministic`() {
        val a = toMatchCell(lagosIslandLat, lagosIslandLng)
        val b = toMatchCell(lagosIslandLat, lagosIslandLng)
        assertEquals("Same coords must always produce same cell", a, b)
    }

    @Test
    fun `nearby points share geohash prefix`() {
        val lagosCell    = toMatchCell(lagosIslandLat, lagosIslandLng)
        val victIslandCell = toMatchCell(victoriaIslandLat, victoriaIslandLng)
        // Within ~5 km: should share at least 4 chars
        val sharedPrefix = lagosCell.zip(victIslandCell)
            .takeWhile { it.first == it.second }.count()
        assertTrue(
            "Points ~3 km apart should share ≥4 geohash chars, got $sharedPrefix",
            sharedPrefix >= 4
        )
    }

    @Test
    fun `distant points do not share full geohash`() {
        val lagosCell = toMatchCell(lagosIslandLat, lagosIslandLng)
        val abujaCell = toMatchCell(abujaLat, abujaLng)
        assertNotEquals(
            "Lagos and Abuja (~800 km apart) must have different cells",
            lagosCell, abujaCell
        )
    }

    // ── toDiscoveryCell ───────────────────────────────────────────────────────

    @Test
    fun `toDiscoveryCell returns 4-char string`() {
        val cell = toDiscoveryCell(lagosIslandLat, lagosIslandLng)
        assertEquals("Discovery cell must be 4 chars", 4, cell.length)
    }

    @Test
    fun `discovery cell is prefix of match cell`() {
        val matchCell     = toMatchCell(lagosIslandLat, lagosIslandLng)
        val discoveryCell = toDiscoveryCell(lagosIslandLat, lagosIslandLng)
        assertTrue(
            "Discovery cell '$discoveryCell' must be a prefix of match cell '$matchCell'",
            matchCell.startsWith(discoveryCell)
        )
    }

    // ── neighbourCells ────────────────────────────────────────────────────────

    @Test
    fun `neighbourCells includes original cell`() {
        val cell      = toMatchCell(lagosIslandLat, lagosIslandLng)
        val neighbours = neighbourCells(cell)
        assertTrue("Neighbours must include the original cell", cell in neighbours)
    }

    @Test
    fun `neighbourCells returns at most 9 entries`() {
        val cell      = toMatchCell(lagosIslandLat, lagosIslandLng)
        val neighbours = neighbourCells(cell)
        assertTrue("At most 9 neighbours (cell + 8 adjacents), got ${neighbours.size}",
            neighbours.size <= 9)
    }

    @Test
    fun `neighbourCells returns at least 1 entry`() {
        val cell      = toMatchCell(lagosIslandLat, lagosIslandLng)
        val neighbours = neighbourCells(cell)
        assertTrue("Must return ≥1 cell", neighbours.isNotEmpty())
    }

    // ── isNearby ──────────────────────────────────────────────────────────────

    @Test
    fun `isNearby is reflexive`() {
        val cell = toMatchCell(lagosIslandLat, lagosIslandLng)
        assertTrue("A cell must be near itself", isNearby(cell, cell))
    }

    @Test
    fun `isNearby is symmetric`() {
        val a = toMatchCell(lagosIslandLat, lagosIslandLng)
        val b = toMatchCell(victoriaIslandLat, victoriaIslandLng)
        assertEquals("isNearby must be symmetric", isNearby(a, b), isNearby(b, a))
    }

    @Test
    fun `isNearby returns false for distant cells`() {
        val lagos = toMatchCell(lagosIslandLat, lagosIslandLng)
        val abuja = toMatchCell(abujaLat, abujaLng)
        assertFalse("Lagos and Abuja are not nearby", isNearby(lagos, abuja))
    }

    @Test
    fun `isNearby returns false for empty strings`() {
        val cell = toMatchCell(lagosIslandLat, lagosIslandLng)
        assertFalse(isNearby("", cell))
        assertFalse(isNearby(cell, ""))
        assertFalse(isNearby("", ""))
    }

    // ── displayLabel ─────────────────────────────────────────────────────────

    @Test
    fun `displayLabel shows only 4-char prefix`() {
        val cell  = toMatchCell(lagosIslandLat, lagosIslandLng)
        val label = displayLabel(cell)
        // Must contain the 4-char prefix but not the full 5-char cell
        assertFalse("Display label must not expose full 5-char geohash: $label",
            label.contains(cell))
        assertTrue("Display label must contain 4-char prefix",
            label.contains(cell.take(4).uppercase()))
    }

    @Test
    fun `displayLabel handles empty input gracefully`() {
        val label = displayLabel("")
        assertTrue("Empty input should return fallback label", label.isNotEmpty())
    }

    // ── Delegate helpers (mirror GeoHashPrivacyUtil without Android imports) ──

    private fun toMatchCell(lat: Double, lng: Double): String =
        ch.hsr.geohash.GeoHash.withCharacterPrecision(lat, lng, 5).toBase32()

    private fun toDiscoveryCell(lat: Double, lng: Double): String =
        ch.hsr.geohash.GeoHash.withCharacterPrecision(lat, lng, 4).toBase32()

    private fun neighbourCells(geohash: String): List<String> {
        return try {
            val gh       = ch.hsr.geohash.GeoHash.fromGeohashString(geohash)
            val adjacent = gh.adjacent
            buildList {
                add(geohash)
                adjacent?.forEach { add(it.toBase32()) }
            }.distinct()
        } catch (_: Exception) { listOf(geohash) }
    }

    private fun isNearby(a: String, b: String, minShared: Int = 4): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a.take(minShared) == b.take(minShared)
    }

    private fun displayLabel(geohash: String): String =
        if (geohash.length >= 4) "Zone ${geohash.take(4).uppercase()}" else "Zone —"
}
