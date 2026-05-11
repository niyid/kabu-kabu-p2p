package com.techducat.kabukabu

import android.os.Bundle
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.techducat.kabukabu.db.TripEntity
import com.techducat.kabukabu.db.TripRepository
import com.techducat.kabukabu.network.FareEstimator
import com.techducat.kabukabu.network.GeoHashPrivacyUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * TripHistoryActivity — displays on-device trip records.
 *
 * Privacy notes:
 *  - Data shown is the on-device Room DB only. No server fetch.
 *  - Peer IDs shown as first 8 chars of the SHA-256 hash (anonymous).
 *  - Locations shown as GeoHash display labels (zone codes), not addresses.
 *  - "Clear history" permanently deletes all rows from the local DB.
 */
@AndroidEntryPoint
class TripHistoryActivity : AppCompatActivity() {

    @Inject lateinit var repository: TripRepository

    private lateinit var rvHistory:   RecyclerView
    private lateinit var tvEmpty:     TextView
    private lateinit var historyAdapter: TripHistoryAdapter

    private val trips = mutableListOf<TripEntity>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_history)

        rvHistory = findViewById(R.id.rvTripHistory)
        tvEmpty   = findViewById(R.id.tvEmptyHistory)

        historyAdapter = TripHistoryAdapter(trips)
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter

        supportActionBar?.apply {
            title = getString(R.string.title_trip_history)
            setDisplayHomeAsUpEnabled(true)
        }

        observeTrips()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_history -> { confirmClearHistory(); true }
            android.R.id.home         -> { finish(); true }
            else                      -> super.onOptionsItemSelected(item)
        }
    }

    private fun observeTrips() {
        lifecycleScope.launch {
            repository.allTripsFlow().collect { list ->
                trips.clear()
                trips.addAll(list)
                historyAdapter.notifyDataSetChanged()
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                rvHistory.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_clear_title)
            .setMessage(R.string.confirm_clear_body)
            .setPositiveButton(R.string.clear) { _, _ ->
                lifecycleScope.launch {
                    repository.clearAllTrips()
                    Toast.makeText(
                        this@TripHistoryActivity,
                        R.string.history_cleared,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class TripHistoryAdapter(
        private val data: List<TripEntity>
    ) : RecyclerView.Adapter<TripHistoryAdapter.VH>() {

        private val sdf = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvDate:    TextView = view.findViewById(R.id.tvTripDate)
            val tvPickup:  TextView = view.findViewById(R.id.tvTripPickup)
            val tvDropoff: TextView = view.findViewById(R.id.tvTripDropoff)
            val tvFare:    TextView = view.findViewById(R.id.tvTripFare)
            val tvStatus:  TextView = view.findViewById(R.id.tvTripStatus)
            val tvRole:    TextView = view.findViewById(R.id.tvTripRole)
            val tvPeer:    TextView = view.findViewById(R.id.tvTripPeer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_trip_history, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val trip = data[position]
            val ctx  = holder.itemView.context

            holder.tvDate.text    = sdf.format(Date(trip.timestamp))
            holder.tvPickup.text  = GeoHashPrivacyUtil.displayLabel(trip.pickupGeohash)
            holder.tvDropoff.text = if (trip.dropoffGeohash.isNotEmpty())
                                        GeoHashPrivacyUtil.displayLabel(trip.dropoffGeohash)
                                    else ctx.getString(R.string.unknown_zone)
            holder.tvFare.text    = FareEstimator.formatNaira(trip.fareNgn)
            holder.tvStatus.text  = trip.status
            holder.tvRole.text    = trip.localRole.replaceFirstChar { it.uppercaseChar() }
            // Show only first 8 hex chars of the SHA-256 peer ID — anonymous but identifiable
            holder.tvPeer.text    = if (trip.peerId.isNotEmpty())
                                        ctx.getString(R.string.peer_short_id, trip.peerId.take(8))
                                    else ctx.getString(R.string.peer_unknown)
        }

        override fun getItemCount() = data.size
    }
}
