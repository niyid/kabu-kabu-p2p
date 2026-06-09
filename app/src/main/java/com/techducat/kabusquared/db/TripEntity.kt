package com.techducat.kabusquared.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * TripEntity — on-device trip record (Room / SQLite).
 *
 * Privacy design:
 *  - [pickupGeohash] / [dropoffGeohash]: GeoHash-5 cells, not raw GPS.
 *  - [riderId] / [driverId]: SHA-256 hashes, never plaintext phone numbers.
 *  - This table is NEVER synced to a cloud database or central server.
 *  - TTL: rows are purged after [TRIP_RETENTION_DAYS] days by a periodic
 *    cleanup coroutine in I2PKabuService.
 *  - Users can wipe the table at any time via Settings → "Clear trip history".
 */
@Entity(
    tableName = "trips",
    indices = [
        Index(value = ["status"]),
        Index(value = ["timestamp"]),
        Index(value = ["expires_at"])
    ]
)
data class TripEntity(

    @PrimaryKey
    @ColumnInfo(name = "trip_id")
    val tripId: String,

    /** Role on this device: "rider" | "driver" | "courier_sender" | "courier_runner" */
    @ColumnInfo(name = "local_role")
    val localRole: String,

    /** SHA-256(phone) of the other party */
    @ColumnInfo(name = "peer_id")
    val peerId: String,

    /** GeoHash-5 pickup zone */
    @ColumnInfo(name = "pickup_geohash")
    val pickupGeohash: String,

    /** GeoHash-5 dropoff zone */
    @ColumnInfo(name = "dropoff_geohash")
    val dropoffGeohash: String,

    @ColumnInfo(name = "service_type")
    val serviceType: String,    // "TAXI" | "COURIER"

    @ColumnInfo(name = "fare_xmr")
    val fareXmr: Long,

    @ColumnInfo(name = "status")
    val status: String,         // see RequestStatus enum

    @ColumnInfo(name = "note")
    val note: String = "",

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    /** Row is deleted after this epoch millis */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,

    /** True if this device initiated the request */
    @ColumnInfo(name = "is_local_origin")
    val isLocalOrigin: Boolean = true
) {
    companion object {
        const val TRIP_RETENTION_DAYS = 30L
        val TRIP_RETENTION_MS = TRIP_RETENTION_DAYS * 24 * 60 * 60 * 1000L
    }
}

/**
 * PeerReviewEntity — locally stored star ratings for peers.
 *
 * Privacy: Reviews are stored on-device and gossiped peer-to-peer over I2P.
 * No central review server exists. A driver's score is the local weighted
 * average of reviews received from peers in the same GeoHash zone.
 */
@Entity(
    tableName = "peer_reviews",
    indices = [Index(value = ["reviewed_peer_id"])]
)
data class PeerReviewEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "trip_id")
    val tripId: String,

    /** SHA-256(phone) of the peer being reviewed */
    @ColumnInfo(name = "reviewed_peer_id")
    val reviewedPeerId: String,

    @ColumnInfo(name = "stars")
    val stars: Int,     // 1–5

    @ColumnInfo(name = "comment")
    val comment: String = "",

    @ColumnInfo(name = "timestamp")
    val timestamp: Long
)
