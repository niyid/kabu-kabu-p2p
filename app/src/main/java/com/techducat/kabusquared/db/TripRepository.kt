package com.techducat.kabusquared.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TripRepository — single source of truth for on-device trip data.
 *
 * All persistence is local. This class has no network calls,
 * no remote data source, and no cloud sync. It is the explicit
 * enforcement point of the "data stays on device" guarantee.
 */
@Singleton
class TripRepository @Inject constructor(
    private val tripDao: TripDao
) {

    // ── Trips ─────────────────────────────────────────────────────────────────

    /** Live stream of all trips, newest first. */
    fun allTripsFlow(): Flow<List<TripEntity>> = tripDao.getAllTripsFlow()

    suspend fun getTrip(tripId: String): TripEntity? =
        withContext(Dispatchers.IO) { tripDao.getTripById(tripId) }

    suspend fun saveTrip(trip: TripEntity) =
        withContext(Dispatchers.IO) { tripDao.insertTrip(trip) }

    suspend fun updateTrip(trip: TripEntity) =
        withContext(Dispatchers.IO) { tripDao.updateTrip(trip) }

    suspend fun getActiveTrip(): TripEntity? =
        withContext(Dispatchers.IO) { tripDao.getActiveTrip() }

    /**
     * Hard-delete all trip records.
     * Called from Settings → "Clear trip history".
     * After this call, no location data remains in the on-device DB.
     */
    suspend fun clearAllTrips() = withContext(Dispatchers.IO) {
        tripDao.deleteAllTrips()
        tripDao.deleteAllReviews()
    }

    /** Remove rows past their retention TTL. Called periodically by I2PKabuService. */
    suspend fun cleanupExpired(nowMs: Long): Int =
        withContext(Dispatchers.IO) { tripDao.cleanupExpired(nowMs) }

    // ── Peer reviews ──────────────────────────────────────────────────────────

    suspend fun saveReview(review: PeerReviewEntity) =
        withContext(Dispatchers.IO) { tripDao.insertReview(review) }

    suspend fun getRatingForPeer(peerId: String): Float =
        withContext(Dispatchers.IO) {
            tripDao.getAverageStarsForPeer(peerId) ?: 5.0f
        }

    suspend fun getReviewsForPeer(peerId: String): List<PeerReviewEntity> =
        withContext(Dispatchers.IO) { tripDao.getReviewsForPeer(peerId) }
}
