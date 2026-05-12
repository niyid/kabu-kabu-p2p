package com.techducat.kabukabu

// ============================================================================
//  MainActivityWalletPatch.kt
//
//  Shows exactly which lines to add / change in the existing MainActivity.kt
//  to wire in the XMR wallet check-before-ride and escrow flows.
//
//  Search for each "// PATCH:" comment and apply the change at that location.
// ============================================================================

import android.app.AlertDialog
import android.util.Log
import com.techducat.kabukabu.model.DriverOffer
import com.techducat.kabukabu.model.TripEvent
import com.techducat.kabukabu.model.TripEventType
import com.techducat.kabukabu.wallet.RideWalletManager
import com.techducat.kabukabu.wallet.WalletSuite

// ─────────────────────────────────────────────────────────────────────────────
//  PATCH 1 — Add to MainActivity class body (new field)
//
//  private lateinit var rideWalletManager: RideWalletManager
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
//  PATCH 2 — Add at the end of initAfterConsent() in MainActivity
//
//  rideWalletManager = RideWalletManager(this)
//  WalletSuite.getInstance(this).apply {
//      setStatusListener(object : WalletSuite.WalletStatusListener {
//          override fun onWalletInitialized(success: Boolean, message: String) {
//              Log.i("KabuWallet", "Wallet init: success=$success $message")
//          }
//          override fun onBalanceUpdated(balance: Long, unlocked: Long) {
//              Log.d("KabuWallet", "Balance updated: ${WalletSuite.convertAtomicToXmr(unlocked)} XMR")
//          }
//          override fun onSyncProgress(height: Long, start: Long, end: Long, pct: Double) {}
//      })
//      initializeWallet(deviceId)
//  }
//  rideWalletManager.setPaymentListener(walletPaymentListener)
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
//  PATCH 3 — Replace the existing onDriverOfferAccepted() handler body
//            (wherever the rider accepts a DriverOffer)
//
//  Before (existing code — taps directly accept and proceeds):
//      i2pClient.acceptOffer(offer)
//
//  After (wallet-gated accept):
// ─────────────────────────────────────────────────────────────────────────────
private fun exampleOnDriverOfferAccepted_PATCHED(offer: DriverOffer) {
    // Gate: check XMR balance before accepting
    rideWalletManager.checkBalanceBeforeAccepting(offer)
    // The result is delivered to walletPaymentListener.onBalanceCheckResult()
    // which either proceeds to escrow or shows a top-up dialog.
}

// ─────────────────────────────────────────────────────────────────────────────
//  PATCH 4 — Add walletPaymentListener to MainActivity
// ─────────────────────────────────────────────────────────────────────────────
private val walletPaymentListener = object : RideWalletManager.RidePaymentListener {

    override fun onBalanceCheckResult(
        sufficient: Boolean,
        unlockedXmr: String,
        fareXmr: String,
        shortfallXmr: String
    ) {
        if (sufficient) {
            // Proceed to escrow setup
            startEscrowSetup()
        } else {
            // Show top-up dialog
            AlertDialog.Builder(this@MainActivityWalletPatch_CONTEXT)
                .setTitle("Insufficient XMR")
                .setMessage(
                    "Fare: $fareXmr XMR\n" +
                    "Your balance: $unlockedXmr XMR\n\n" +
                    "Please top up $shortfallXmr XMR to your wallet before booking."
                )
                .setPositiveButton("Open Wallet") { _, _ ->
                    startActivity(
                        android.content.Intent(
                            this@MainActivityWalletPatch_CONTEXT,
                            com.techducat.kabukabu.wallet.WalletActivity::class.java
                        )
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onEscrowReady(escrowAddress: String) {
        // Driver-side: escrow address confirmed — show to driver for verification
        Log.i("KabuMain", "Escrow ready: ${escrowAddress.take(16)}…")
        runOnUiThread {
            tvStatus?.text = "Escrow locked — ride approved"
        }
    }

    override fun onEscrowFunded(escrowAddress: String) {
        // Rider-side: fare is locked in escrow — safe to proceed with ride
        Log.i("KabuMain", "Escrow funded at ${escrowAddress.take(16)}…")
        runOnUiThread {
            tvStatus?.text = "Fare locked in escrow — driver notified"
        }
        // Now relay the fact over I2P so driver's DriverActivity knows
        // i2pClient.sendWalletEscrowReady(currentTripId, escrowAddress)
    }

    override fun onPaymentReleased(txId: String) {
        Log.i("KabuMain", "Payment released — txId: $txId")
        runOnUiThread {
            tvStatus?.text = "Payment complete ✓"
            AlertDialog.Builder(this@MainActivityWalletPatch_CONTEXT)
                .setTitle("Ride Paid")
                .setMessage("XMR payment sent to driver.\nTransaction: ${txId.take(16)}…")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onRefundComplete(txId: String) {
        Log.i("KabuMain", "Refund complete — txId: $txId")
        runOnUiThread {
            tvStatus?.text = "Refund processed ✓"
        }
    }

    override fun onPaymentError(phase: String, error: String) {
        Log.e("KabuMain", "Payment error in $phase: $error")
        runOnUiThread {
            tvStatus?.text = "Payment error: $error"
            AlertDialog.Builder(this@MainActivityWalletPatch_CONTEXT)
                .setTitle("Wallet Error")
                .setMessage("Phase: $phase\n\n$error")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PATCH 5 — Add startEscrowSetup() to MainActivity
// ─────────────────────────────────────────────────────────────────────────────
private fun startEscrowSetup() {
    // Step 1: prepare our side and share multisig info with driver over I2P
    rideWalletManager.prepareLocalEscrow(
        onReady = { myInfo, myAddress ->
            Log.i("KabuMain", "Escrow prepared — sending multisig info to driver")
            // Build the I2P wallet_multisig_info message
            val walletMsg = org.json.JSONObject().apply {
                put("type",     "wallet_multisig_info")
                put("info",     myInfo)
                put("address",  myAddress)
                put("fare_xmr", currentFareAtomicUnits)   // stored when offer was selected
                put("ride_id",  currentRideId)
                put("is_rider", true)
            }
            i2pClient.sendRawMessage(walletMsg.toString())
        },
        onError = { error ->
            runOnUiThread { tvStatus?.text = "Escrow setup failed: $error" }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  PATCH 6 — In onTripEvent() handler, add the TRIP_COMPLETED case
//
//  Find the existing when (event.eventType) block and add:
// ─────────────────────────────────────────────────────────────────────────────
private fun exampleOnTripEvent_PATCHED(event: TripEvent) {
    when (event.eventType) {
        TripEventType.TRIP_COMPLETED -> {
            // Automatically release escrow to driver
            rideWalletManager.onTripCompleted(
                event            = event,
                isRider          = true,             // this is MainActivity (rider)
                driverXmrAddress = currentDriverXmrAddress,  // stored from DriverOffer
                fareAtomicUnits  = currentFareAtomicUnits,
                peerPartialTxHex = null,             // we are the first signer
                onNeedPeerSignature = { partialTxHex ->
                    // Relay our partial signature to driver over I2P
                    val msg = org.json.JSONObject().apply {
                        put("type",           "wallet_partial_tx")
                        put("partial_tx_hex", partialTxHex)
                        put("driver_address", currentDriverXmrAddress)
                        put("fare_xmr",       currentFareAtomicUnits)
                        put("ride_id",        currentRideId)
                    }
                    i2pClient.sendRawMessage(msg.toString())
                }
            )
        }
        TripEventType.TRIP_CANCELLED -> {
            // Refund escrow back to rider
            rideWalletManager.refundToRider(
                riderXmrAddress  = myXmrAddress,     // from WalletSuite.getAddress()
                fareAtomicUnits  = currentFareAtomicUnits,
                peerSignatureInfos = emptyList()
            )
        }
        else -> { /* existing handlers unchanged */ }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Placeholder fields referenced above — these must exist in MainActivity
//
//  private var currentFareAtomicUnits: Long = 0L
//  private var currentRideId: String = ""
//  private var currentDriverXmrAddress: String = ""
//  private var myXmrAddress: String = ""
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
//  PATCH 7 — Populate currentDriverXmrAddress when a DriverOffer arrives
//
//  In onDriverOffer() callback:
//      // The driver's XMR address is piggy-backed in DriverOffer.driverI2pDest
//      // as a JSON string: {"i2pDest":"...","xmrAddress":"4..."}
//      // Parse it during the offer display step.
//
//  In onRideRequestAccepted() callback (the driver accepted):
//      currentFareAtomicUnits = acceptedOffer.counterFareXMR ?: request.fareEstimateXMR
//      currentRideId          = request.requestId
// ─────────────────────────────────────────────────────────────────────────────

// Dummy references to suppress "unresolved" errors in this patch file
private lateinit var rideWalletManager: RideWalletManager
private var currentFareAtomicUnits: Long = 0L
private var currentRideId: String = ""
private var currentDriverXmrAddress: String = ""
private var myXmrAddress: String = ""
private val tvStatus: android.widget.TextView? = null
private lateinit var i2pClient: I2PKabuClient
private val MainActivityWalletPatch_CONTEXT get() = this

private fun runOnUiThread(block: () -> Unit) {}
private fun startActivity(intent: android.content.Intent) {}

// Extension on I2PKabuClient — add this method to I2PKabuClient.kt:
//
//   fun sendRawMessage(json: String) {
//       // Write the JSON string to the local TCP socket (same as other sends)
//       localSocket?.getOutputStream()?.write((json + "\n").toByteArray())
//   }
