package com.techducat.kabukabu.wallet

import android.content.Context
import android.util.Log
import com.techducat.kabukabu.model.DriverOffer
import com.techducat.kabukabu.model.RideRequest
import com.techducat.kabukabu.model.RequestStatus
import com.techducat.kabukabu.model.TripEvent
import com.techducat.kabukabu.model.TripEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * RideWalletManager — Kotlin façade that connects Kabu-Kabu's P2P ride flow
 * to the embedded XMR wallet (WalletSuite).
 *
 * ## Lifecycle of a paid ride
 *
 * ```
 *  RIDER SIDE                              DRIVER SIDE
 *  ──────────────────────────────────────────────────────────────────
 *  1. checkBalance(fare)
 *     ↓ sufficient
 *  2. prepareEscrow()                      prepareEscrow()
 *     ↓ share multisig info over I2P  ←→  ↓ share multisig info
 *  3. finalizeEscrow(driverInfo, fare)     finalizeEscrow(riderInfo, 0)
 *     ↓ rider funds escrow address         ↓ driver confirms address
 *  4.  ··· ride in progress ···
 *  5. on TRIP_COMPLETED:
 *     exportPartialSignature()  ──────►  coSignAndBroadcast(partial)
 *                                         → TX broadcast, driver paid
 * ```
 *
 * ## I2P message types added to I2PKabuService
 *
 * | Constant                      | Direction      | Payload               |
 * |-------------------------------|----------------|-----------------------|
 * | MSG_TYPE_WALLET_MULTISIG_INFO | bidirectional  | JSON {info, address}  |
 * | MSG_TYPE_WALLET_ESCROW_READY  | bidirectional  | JSON {escrowAddress}  |
 * | MSG_TYPE_WALLET_PARTIAL_TX    | rider→driver   | JSON {partialTxHex}   |
 * | MSG_TYPE_WALLET_TX_CONFIRMED  | driver→rider   | JSON {txId}           |
 *
 * All messages travel over the existing encrypted I2P tunnel between
 * the rider and driver devices — no new network infrastructure needed.
 */
class RideWalletManager(private val context: Context) {

    companion object {
        private const val TAG = "RideWalletManager"

        // I2P message-type strings added to I2PKabuService dispatch
        const val MSG_TYPE_WALLET_MULTISIG_INFO = "wallet_multisig_info"
        const val MSG_TYPE_WALLET_ESCROW_READY  = "wallet_escrow_ready"
        const val MSG_TYPE_WALLET_PARTIAL_TX    = "wallet_partial_tx"
        const val MSG_TYPE_WALLET_TX_CONFIRMED  = "wallet_tx_confirmed"
    }

    private val walletSuite: WalletSuite = WalletSuite.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Listener ──────────────────────────────────────────────────────────────

    interface RidePaymentListener {
        /** Balance check result — called before the rider can accept an offer. */
        fun onBalanceCheckResult(sufficient: Boolean, unlockedXmr: String,
                                  fareXmr: String, shortfallXmr: String)
        /** Escrow address is ready — rider should send this address to driver over I2P. */
        fun onEscrowReady(escrowAddress: String)
        /** Escrow has been funded by the rider — driver is notified via I2P. */
        fun onEscrowFunded(escrowAddress: String)
        /** Payment released to driver — ride is fully settled. */
        fun onPaymentReleased(txId: String)
        /** Refund returned to rider. */
        fun onRefundComplete(txId: String)
        /** Any error in the payment pipeline. */
        fun onPaymentError(phase: String, error: String)
    }

    private var paymentListener: RidePaymentListener? = null

    fun setPaymentListener(l: RidePaymentListener) { paymentListener = l }

    // =========================================================================
    //  1.  Pre-ride balance gate
    // =========================================================================

    /**
     * Gate called from MainActivity when the rider taps "Accept offer".
     *
     * If the balance is insufficient the offer acceptance is blocked and
     * [RidePaymentListener.onBalanceCheckResult] is called with sufficient=false
     * so the UI can prompt the user to top up.
     *
     * @param offer  The [DriverOffer] the rider wants to accept
     */
    fun checkBalanceBeforeAccepting(offer: DriverOffer) {
        val fare = offer.counterFareXMR ?: 0L
        Log.i(TAG, "checkBalanceBeforeAccepting: fare=${WalletSuite.convertAtomicToXmr(fare)} XMR")

        walletSuite.checkBalanceForRide(fare, object : WalletSuite.BalanceCheckCallback {
            override fun onResult(
                sufficient: Boolean,
                unlockedXmr: String,
                fareXmr: String,
                shortfallXmr: String
            ) {
                Log.i(TAG, "Balance check: sufficient=$sufficient")
                paymentListener?.onBalanceCheckResult(sufficient, unlockedXmr, fareXmr, shortfallXmr)
            }

            override fun onError(error: String) {
                Log.e(TAG, "checkBalanceForRide error: $error")
                paymentListener?.onPaymentError("balance_check", error)
            }
        })
    }

    // =========================================================================
    //  2.  Escrow setup
    // =========================================================================

    /**
     * Prepare the local wallet for 2-of-2 multisig escrow.
     *
     * Returns the local multisig-info string that must be sent to the peer
     * via [I2PKabuService] (MSG_TYPE_WALLET_MULTISIG_INFO).
     *
     * @param onReady  (myMultisigInfo, myAddress) — relay both over I2P
     */
    fun prepareLocalEscrow(onReady: (myInfo: String, myAddress: String) -> Unit,
                           onError: (String) -> Unit) {
        walletSuite.prepareEscrow(object : WalletSuite.EscrowCallback {
            override fun onEscrowReady(escrowAddress: String, myMultisigInfo: String) {
                Log.i(TAG, "Local escrow prepared — sharing multisig info with peer")
                onReady(myMultisigInfo, escrowAddress)
            }
            override fun onError(error: String) {
                Log.e(TAG, "prepareLocalEscrow error: $error")
                onError(error)
                paymentListener?.onPaymentError("escrow_prepare", error)
            }
        })
    }

    /**
     * Finalise escrow after receiving the peer's multisig info over I2P.
     *
     * Rider:  pass fare > 0 → funds are locked into escrow automatically.
     * Driver: pass fare = 0 → just derives the shared escrow address.
     *
     * @param peerMultisigInfo  Info string received from the peer (I2P message)
     * @param fareAtomicUnits   Piconero amount (0 for driver-side call)
     */
    fun finalizeEscrowWithPeer(peerMultisigInfo: String, fareAtomicUnits: Long) {
        Log.i(TAG, "finalizeEscrowWithPeer: fare=${WalletSuite.convertAtomicToXmr(fareAtomicUnits)} XMR")
        walletSuite.finalizeEscrow(peerMultisigInfo, fareAtomicUnits,
            object : WalletSuite.EscrowCallback {
                override fun onEscrowReady(escrowAddress: String, myMultisigInfo: String) {
                    Log.i(TAG, "Escrow finalised at: ${escrowAddress.take(16)}…")
                    if (fareAtomicUnits > 0)
                        paymentListener?.onEscrowFunded(escrowAddress)
                    else
                        paymentListener?.onEscrowReady(escrowAddress)
                }
                override fun onError(error: String) {
                    Log.e(TAG, "finalizeEscrow error: $error")
                    paymentListener?.onPaymentError("escrow_finalize", error)
                }
            })
    }

    // =========================================================================
    //  3.  Automatic payment on TRIP_COMPLETED
    // =========================================================================

    /**
     * Called from MainActivity / DriverActivity when a
     * [TripEvent] with type [TripEventType.TRIP_COMPLETED] arrives.
     *
     * Rider side: exports partial signature and sends to driver over I2P.
     * Driver side: receives partial TX, co-signs, and broadcasts.
     *
     * @param event               The TRIP_COMPLETED event
     * @param isRider             True if this device is the rider
     * @param driverXmrAddress    Driver's XMR address (from DriverOffer)
     * @param fareAtomicUnits     Agreed fare in piconero
     * @param peerPartialTxHex    Partial TX hex from peer (null if first signer)
     * @param onNeedPeerSignature Called when the peer must also sign;
     *                            send the returned blob over I2P
     */
    fun onTripCompleted(
        event:              TripEvent,
        isRider:            Boolean,
        driverXmrAddress:   String,
        fareAtomicUnits:    Long,
        peerPartialTxHex:   String?,
        onNeedPeerSignature: (partialTxHex: String) -> Unit
    ) {
        Log.i(TAG, "onTripCompleted: isRider=$isRider tripId=${event.tripId}")

        if (peerPartialTxHex != null) {
            // Second signer — co-sign and broadcast
            walletSuite.coSignAndBroadcast(
                peerPartialTxHex,
                driverXmrAddress,
                object : WalletSuite.PaymentReleaseCallback {
                    override fun onSuccess(txId: String, amountAtomic: Long) {
                        Log.i(TAG, "✓ Payment broadcast — txId: $txId")
                        paymentListener?.onPaymentReleased(txId)
                    }
                    override fun onError(error: String) {
                        Log.e(TAG, "coSignAndBroadcast error: $error")
                        paymentListener?.onPaymentError("cosign_broadcast", error)
                    }
                })
        } else {
            // First signer (rider):
            //   Step 1 — export our partial signing images.
            //   Step 2 — relay the blob to the driver over I2P (onNeedPeerSignature callback).
            //   Step 3 — driver calls coSignAndBroadcast() which imports our images,
            //             builds a TX, and commits with both key-shares.
            //
            // We do NOT call releaseEscrowToDriver() here: that call tries to commit
            // unilaterally, which always fails in a 2-of-2 wallet and conflates
            // genuine errors (insufficient balance, wallet not in multisig mode) with
            // the expected "need peer signature" path.
            walletSuite.exportMultisigImages(object : WalletSuite.ExportMultisigCallback {
                override fun onSuccess(info: String) {
                    Log.i(TAG, "Partial signing image exported — relaying to driver for co-sign")
                    onNeedPeerSignature(info)
                }
                override fun onError(error: String) {
                    Log.e(TAG, "exportMultisigImages failed: $error")
                    paymentListener?.onPaymentError("export_partial", error)
                }
            })
        }
    }

    // =========================================================================
    //  4.  Dispute / cancellation refund
    // =========================================================================

    /**
     * Refund the escrowed fare to the rider when a trip is cancelled
     * (before pickup) or a dispute is resolved in the rider's favour.
     *
     * ## Two-phase signing (mirrors [onTripCompleted])
     *
     * In a 2-of-2 multisig wallet neither party can broadcast unilaterally.
     * The flow is:
     *   1. First signer (rider) calls this with `peerSignatureInfos = emptyList()`.
     *      The wallet exports its partial signing images and delivers them to
     *      [onNeedPeerSignature] for relay to the driver over I2P.
     *   2. Driver receives the blob, sends it back over I2P as `wallet_partial_tx`
     *      (reusing the existing message type, flag `refund=true`).
     *   3. Rider receives the driver's images in the `wallet_tx_confirmed` / partial
     *      response and calls this again with [peerSignatureInfos] populated.
     *      Now `refundEscrowToRider` can build and commit the TX.
     *
     * Callers that do not need the two-phase flow (e.g. already have peer images)
     * may pass a populated [peerSignatureInfos] to go directly to broadcast.
     *
     * @param riderXmrAddress        Rider's main XMR address
     * @param fareAtomicUnits        Amount to refund (piconero)
     * @param peerSignatureInfos     Driver's multisig image exports (empty on first call)
     * @param onNeedPeerSignature    Called when this device is the first signer;
     *                               relay the returned blob to the driver over I2P
     */
    fun refundToRider(
        riderXmrAddress: String,
        fareAtomicUnits: Long,
        peerSignatureInfos: List<String>,
        onNeedPeerSignature: ((partialTxHex: String) -> Unit)? = null
    ) {
        Log.i(TAG, "refundToRider: ${WalletSuite.convertAtomicToXmr(fareAtomicUnits)} XMR")

        if (peerSignatureInfos.isEmpty()) {
            // First signer — export our partial signing images and relay to the driver.
            // This is the same approach used in onTripCompleted for the payment direction.
            walletSuite.exportMultisigImages(object : WalletSuite.ExportMultisigCallback {
                override fun onSuccess(info: String) {
                    Log.i(TAG, "Refund: partial signing image exported — relaying to driver for co-sign")
                    onNeedPeerSignature?.invoke(info)
                        ?: Log.w(TAG, "refundToRider: no onNeedPeerSignature callback — peer will not co-sign")
                }
                override fun onError(error: String) {
                    Log.e(TAG, "refundToRider exportMultisigImages failed: $error")
                    paymentListener?.onPaymentError("refund_export", error)
                }
            })
        } else {
            // Second signer — peer images available; build TX and broadcast.
            walletSuite.refundEscrowToRider(
                riderXmrAddress,
                fareAtomicUnits,
                peerSignatureInfos,
                object : WalletSuite.PaymentReleaseCallback {
                    override fun onSuccess(txId: String, amountAtomic: Long) {
                        Log.i(TAG, "✓ Refund broadcast — txId: $txId")
                        paymentListener?.onRefundComplete(txId)
                    }
                    override fun onError(error: String) {
                        Log.e(TAG, "refundToRider error: $error")
                        paymentListener?.onPaymentError("refund", error)
                    }
                })
        }
    }

    // =========================================================================
    //  Utility
    // =========================================================================

    fun formatXmr(atomicUnits: Long): String = WalletSuite.convertAtomicToXmr(atomicUnits)

    fun getCachedBalance(): Long         = walletSuite.getCachedBalance()
    fun getCachedUnlocked(): Long        = walletSuite.getCachedUnlockedBalance()

    /** Refresh balance and deliver result on main thread. */
    fun refreshBalance(onResult: (balance: Long, unlocked: Long) -> Unit,
                       onError: (String) -> Unit) {
        walletSuite.getBalance(object : WalletSuite.BalanceCallback {
            override fun onSuccess(balance: Long, unlocked: Long) = onResult(balance, unlocked)
            override fun onError(error: String) = onError(error)
        })
    }
}
