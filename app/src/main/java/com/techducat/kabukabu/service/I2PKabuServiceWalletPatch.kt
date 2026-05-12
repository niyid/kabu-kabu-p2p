package com.techducat.kabukabu.service

// ============================================================================
//  I2PKabuServiceWalletPatch.kt
//
//  Patch file — add these constants, dispatch cases, and helpers to the
//  existing I2PKabuService.kt.  Do NOT create a new service; merge this
//  into I2PKabuService where indicated by the inline comments.
// ============================================================================

/**
 * ## HOW TO APPLY THIS PATCH
 *
 * ### Step 1 — Add message type constants
 *
 * In `I2PKabuService.kt` companion object, after the existing MSG_TYPE_* constants:
 *
 * ```kotlin
 * // ── XMR wallet message types ──────────────────────────────────────────
 * const val MSG_TYPE_WALLET_MULTISIG_INFO = "wallet_multisig_info"
 * const val MSG_TYPE_WALLET_ESCROW_READY  = "wallet_escrow_ready"
 * const val MSG_TYPE_WALLET_PARTIAL_TX    = "wallet_partial_tx"
 * const val MSG_TYPE_WALLET_TX_CONFIRMED  = "wallet_tx_confirmed"
 * const val MSG_TYPE_WALLET_BALANCE_REQ   = "wallet_balance_req"
 * const val MSG_TYPE_WALLET_REFUND_REQ    = "wallet_refund_req"
 * ```
 *
 * ### Step 2 — Add fields to I2PKabuService class body
 *
 * ```kotlin
 * // ── XMR wallet integration ────────────────────────────────────────────
 * private lateinit var rideWalletManager: com.techducat.kabukabu.wallet.RideWalletManager
 * ```
 *
 * ### Step 3 — Initialise in onCreate()
 *
 * ```kotlin
 * rideWalletManager = com.techducat.kabukabu.wallet.RideWalletManager(this)
 * val walletSuite = com.techducat.kabukabu.wallet.WalletSuite.getInstance(this)
 * walletSuite.initializeWallet(deviceId)
 * ```
 *
 * ### Step 4 — Add dispatch cases to handleIncomingMessage()
 *
 * Inside the `when (msgType)` block (or equivalent message dispatch):
 *
 * ```kotlin
 * MSG_TYPE_WALLET_MULTISIG_INFO -> handleWalletMultisigInfo(json, session)
 * MSG_TYPE_WALLET_PARTIAL_TX    -> handleWalletPartialTx(json, session)
 * MSG_TYPE_WALLET_REFUND_REQ    -> handleWalletRefundRequest(json, session)
 * ```
 *
 * ### Step 5 — Add the handler functions below to I2PKabuService
 *
 * (Paste the extension functions at the bottom of I2PKabuService.kt, or
 * copy them inline into the class body after removing the `I2PKabuService.`
 * receiver prefix.)
 */

import android.util.Log
import com.techducat.kabukabu.wallet.RideWalletManager
import com.techducat.kabukabu.wallet.WalletSuite
import org.json.JSONObject

private const val TAG = "KabuServiceWallet"

// ─────────────────────────────────────────────────────────────────────────────
//  Message handlers — paste into I2PKabuService class body
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Received from the peer: their multisig info so we can finalise the escrow.
 *
 * JSON schema: { "type": "wallet_multisig_info", "info": "...", "address": "...",
 *                "fare_xmr": 12345678900000, "ride_id": "uuid",
 *                "is_rider": true }
 */
fun I2PKabuService.handleWalletMultisigInfo(json: JSONObject, session: Any?) {
    val peerInfo     = json.optString("info", "")
    val fareAtomic   = json.optLong("fare_xmr", 0L)
    val isRider      = json.optBoolean("is_rider", false)
    val rideId       = json.optString("ride_id", "")

    Log.i(TAG, "handleWalletMultisigInfo: rideId=$rideId peerIsRider=$isRider")

    if (peerInfo.isEmpty()) {
        Log.w(TAG, "Empty multisig info — ignoring"); return
    }

    // We are the DRIVER if peer is the RIDER, and vice-versa
    val myFare = if (isRider) 0L else fareAtomic  // driver doesn't fund escrow

    val rwm = RideWalletManager(this)
    rwm.finalizeEscrowWithPeer(peerInfo, myFare)

    // Confirm escrow address back to peer so both sides can verify
    rwm.setPaymentListener(object : RideWalletManager.RidePaymentListener {
        override fun onEscrowReady(escrowAddress: String) {
            val reply = JSONObject().apply {
                put("type",          MSG_TYPE_WALLET_ESCROW_READY)
                put("escrow_address", escrowAddress)
                put("ride_id",       rideId)
            }
            broadcastToLocalClients(reply.toString())
        }
        override fun onEscrowFunded(escrowAddress: String) {
            val reply = JSONObject().apply {
                put("type",          MSG_TYPE_WALLET_ESCROW_READY)
                put("escrow_address", escrowAddress)
                put("ride_id",       rideId)
                put("funded",        true)
            }
            broadcastToLocalClients(reply.toString())
        }
        override fun onBalanceCheckResult(s: Boolean, u: String, f: String, sh: String) {}
        override fun onPaymentReleased(txId: String) {
            val msg = JSONObject().apply {
                put("type",    MSG_TYPE_WALLET_TX_CONFIRMED)
                put("tx_id",   txId)
                put("ride_id", rideId)
            }
            broadcastToLocalClients(msg.toString())
        }
        override fun onRefundComplete(txId: String) {
            val msg = JSONObject().apply {
                put("type",    MSG_TYPE_WALLET_TX_CONFIRMED)
                put("tx_id",   txId)
                put("ride_id", rideId)
                put("refund",  true)
            }
            broadcastToLocalClients(msg.toString())
        }
        override fun onPaymentError(phase: String, error: String) {
            Log.e(TAG, "Payment error in $phase: $error")
        }
    })
}

/**
 * Received from the first signer: partial TX hex to co-sign and broadcast.
 *
 * JSON schema: { "type": "wallet_partial_tx", "partial_tx_hex": "...",
 *                "driver_address": "...", "fare_xmr": 12345, "ride_id": "uuid" }
 */
fun I2PKabuService.handleWalletPartialTx(json: JSONObject, session: Any?) {
    val partialTxHex  = json.optString("partial_tx_hex", "")
    val driverAddress = json.optString("driver_address", "")
    val fareAtomic    = json.optLong("fare_xmr", 0L)
    val rideId        = json.optString("ride_id", "")

    Log.i(TAG, "handleWalletPartialTx: rideId=$rideId")

    if (partialTxHex.isEmpty() || driverAddress.isEmpty()) {
        Log.w(TAG, "Invalid wallet_partial_tx payload"); return
    }

    val rwm = RideWalletManager(this)
    rwm.setPaymentListener(object : RideWalletManager.RidePaymentListener {
        override fun onPaymentReleased(txId: String) {
            val msg = JSONObject().apply {
                put("type",    MSG_TYPE_WALLET_TX_CONFIRMED)
                put("tx_id",   txId)
                put("ride_id", rideId)
            }
            broadcastToLocalClients(msg.toString())
            Log.i(TAG, "✓ Payment complete for rideId=$rideId txId=$txId")
        }
        override fun onPaymentError(phase: String, error: String) {
            Log.e(TAG, "co-sign failed in $phase: $error")
        }
        override fun onEscrowReady(a: String) {}
        override fun onEscrowFunded(a: String) {}
        override fun onBalanceCheckResult(s: Boolean, u: String, f: String, sh: String) {}
        override fun onRefundComplete(txId: String) {}
    })

    // Co-sign and broadcast
    val walletSuite = WalletSuite.getInstance(this)
    walletSuite.coSignAndBroadcast(
        partialTxHex,
        driverAddress,
        object : WalletSuite.PaymentReleaseCallback {
            override fun onSuccess(txId: String, amountAtomic: Long) {
                val msg = JSONObject().apply {
                    put("type",    MSG_TYPE_WALLET_TX_CONFIRMED)
                    put("tx_id",   txId)
                    put("ride_id", rideId)
                }
                broadcastToLocalClients(msg.toString())
            }
            override fun onError(error: String) {
                Log.e(TAG, "coSignAndBroadcast error: $error")
            }
        })
}

/**
 * Received from the peer: a refund request (trip cancelled or dispute).
 *
 * JSON schema: { "type": "wallet_refund_req", "rider_address": "...",
 *                "fare_xmr": 12345, "ride_id": "uuid" }
 */
fun I2PKabuService.handleWalletRefundRequest(json: JSONObject, session: Any?) {
    val riderAddress = json.optString("rider_address", "")
    val fareAtomic   = json.optLong("fare_xmr", 0L)
    val rideId       = json.optString("ride_id", "")

    Log.i(TAG, "handleWalletRefundRequest: rideId=$rideId")
    if (riderAddress.isEmpty()) { Log.w(TAG, "Empty rider address"); return }

    val rwm = RideWalletManager(this)
    rwm.setPaymentListener(object : RideWalletManager.RidePaymentListener {
        override fun onRefundComplete(txId: String) {
            val msg = JSONObject().apply {
                put("type",    MSG_TYPE_WALLET_TX_CONFIRMED)
                put("tx_id",   txId)
                put("ride_id", rideId)
                put("refund",  true)
            }
            broadcastToLocalClients(msg.toString())
        }
        override fun onPaymentError(phase: String, error: String) {
            Log.e(TAG, "refund error in $phase: $error")
        }
        override fun onEscrowReady(a: String) {}
        override fun onEscrowFunded(a: String) {}
        override fun onBalanceCheckResult(s: Boolean, u: String, f: String, sh: String) {}
        override fun onPaymentReleased(txId: String) {}
    })
    rwm.refundToRider(riderAddress, fareAtomic, emptyList())
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers assumed to exist in I2PKabuService — include stubs if missing
// ─────────────────────────────────────────────────────────────────────────────

// These constants must also be in I2PKabuService companion object:
private const val MSG_TYPE_WALLET_MULTISIG_INFO = "wallet_multisig_info"
private const val MSG_TYPE_WALLET_ESCROW_READY  = "wallet_escrow_ready"
private const val MSG_TYPE_WALLET_PARTIAL_TX    = "wallet_partial_tx"
private const val MSG_TYPE_WALLET_TX_CONFIRMED  = "wallet_tx_confirmed"
private const val MSG_TYPE_WALLET_REFUND_REQ    = "wallet_refund_req"

// broadcastToLocalClients() already exists in I2PKabuService — no change needed.
