package com.techducat.kabukabup2p.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    // ── Trips ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity)

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("SELECT * FROM trips ORDER BY timestamp DESC")
    fun getAllTripsFlow(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE trip_id = :tripId LIMIT 1")
    suspend fun getTripById(tripId: String): TripEntity?

    @Query("SELECT * FROM trips WHERE status = :status ORDER BY timestamp DESC")
    suspend fun getTripsByStatus(status: String): List<TripEntity>

    @Query("SELECT * FROM trips WHERE status IN ('OPEN','IN_PROGRESS') ORDER BY timestamp DESC LIMIT 1")
    suspend fun getActiveTrip(): TripEntity?

    @Query("DELETE FROM trips WHERE expires_at < :nowMs")
    suspend fun cleanupExpired(nowMs: Long): Int

    @Query("DELETE FROM trips")
    suspend fun deleteAllTrips()

    // ── Peer Reviews ─────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReview(review: PeerReviewEntity)

    @Query("SELECT * FROM peer_reviews WHERE reviewed_peer_id = :peerId ORDER BY timestamp DESC")
    suspend fun getReviewsForPeer(peerId: String): List<PeerReviewEntity>

    @Query("SELECT AVG(stars) FROM peer_reviews WHERE reviewed_peer_id = :peerId")
    suspend fun getAverageStarsForPeer(peerId: String): Float?

    @Query("DELETE FROM peer_reviews")
    suspend fun deleteAllReviews()
}
