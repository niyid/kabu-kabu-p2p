package com.techducat.kabukabu.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.techducat.kabukabu.R
import com.techducat.kabukabu.model.DriverOffer
import com.techducat.kabukabu.model.RideRequest

/**
 * OfferAdapter — displays incoming driver offers to a rider.
 *
 * Privacy: Only anonymous data is shown:
 *  - Vehicle type, ETA minutes, star rating, pickup zone (geohash cell)
 *  - Driver real name and phone are NEVER available or displayed.
 */
class OfferAdapter(
    private val offers: List<DriverOffer>,
    private val onAccept: (DriverOffer) -> Unit
) : RecyclerView.Adapter<OfferAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvVehicle:  TextView = view.findViewById(R.id.tvVehicleType)
        val tvEta:      TextView = view.findViewById(R.id.tvEta)
        val tvRating:   TextView = view.findViewById(R.id.tvRating)
        val tvFare:     TextView = view.findViewById(R.id.tvFare)
        val tvZone:     TextView = view.findViewById(R.id.tvDriverZone)
        val btnAccept:  Button   = view.findViewById(R.id.btnAcceptOffer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_driver_offer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val offer = offers[position]
        val ctx   = holder.itemView.context

        holder.tvVehicle.text  = offer.vehicleType
        holder.tvEta.text      = ctx.getString(R.string.eta_minutes, offer.etaMinutes)
        holder.tvRating.text   = ctx.getString(R.string.rating_stars, offer.ratingScore)
        holder.tvFare.text     = ctx.getString(R.string.fare_ngn,
            offer.counterFareNGN ?: 0L)
        // Show only first 4 chars of driver's I2P destination as a short anonymous ID
        val shortId = offer.driverI2pDest.take(8).ifEmpty { offer.driverId.take(8) }
        holder.tvZone.text     = ctx.getString(R.string.driver_anon_id, shortId)
        holder.btnAccept.setOnClickListener { onAccept(offer) }
    }

    override fun getItemCount() = offers.size
}

/**
 * RequestAdapter — displays open ride requests to a driver.
 *
 * Privacy: Only anonymous data is shown:
 *  - Pickup zone (geohash cell), service type, fare estimate, optional note
 *  - Rider real name and phone are NEVER available or displayed.
 */
class RequestAdapter(
    private val requests: List<RideRequest>,
    private val onOffer:  (RideRequest) -> Unit
) : RecyclerView.Adapter<RequestAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvZone:    TextView = view.findViewById(R.id.tvPickupZone)
        val tvType:    TextView = view.findViewById(R.id.tvServiceType)
        val tvFare:    TextView = view.findViewById(R.id.tvFareEstimate)
        val tvNote:    TextView = view.findViewById(R.id.tvNote)
        val btnOffer:  Button   = view.findViewById(R.id.btnMakeOffer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ride_request, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val req = requests[position]
        val ctx = holder.itemView.context

        holder.tvZone.text  = ctx.getString(R.string.pickup_zone, req.pickupGeohash)
        holder.tvType.text  = req.serviceType.name
        holder.tvFare.text  = ctx.getString(R.string.fare_ngn, req.fareEstimateNGN)
        holder.tvNote.text  = req.noteForDriver.ifEmpty { ctx.getString(R.string.no_note) }
        holder.btnOffer.setOnClickListener { onOffer(req) }
    }

    override fun getItemCount() = requests.size
}
