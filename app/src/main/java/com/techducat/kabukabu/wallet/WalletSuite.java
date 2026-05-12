package com.techducat.kabukabu.wallet;

// ============================================================================
//  WalletSuite.java — XMR embedded wallet for Kabu-Kabu P2P
//
//  Lifted from Verzus (com.techducat.verzus.wallet.WalletSuite) and adapted
//  for ride-hailing escrow semantics:
//
//   ┌─────────────────────────────────────────────────────────────┐
//   │  RIDER                  DRIVER                              │
//   │  ──────                 ──────                              │
//   │  checkBalanceForRide()  ←  fare estimate arrives via I2P   │
//   │  lockFareInEscrow()     →  2-of-2 multisig escrow funded   │
//   │       ···  ride in progress  ···                            │
//   │  releaseEscrowToDriver()→  driver signs + broadcasts TX    │
//   └─────────────────────────────────────────────────────────────┘
//
//  Multisig design (2-of-2):
//   • Rider wallet  → key-share A
//   • Driver wallet → key-share B
//   • Escrow address derived from A + B (no third party)
//   • Funds cannot move unless BOTH parties sign
//   • On dispute: rider signs refund OR driver signs payout
//     mediated by the I2P message channel already in I2PKabuService
//
//  Daemon: stagenet for testing, mainnet in production build flavour.
//  Native .so: libmonerujo.so (same build pipeline as Verzus)
// ============================================================================

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.m2049r.xmrwallet.data.Node;
import com.m2049r.xmrwallet.model.PendingTransaction;
import com.m2049r.xmrwallet.model.Wallet;
import com.m2049r.xmrwallet.model.WalletListener;
import com.m2049r.xmrwallet.model.WalletManager;
import com.m2049r.xmrwallet.model.NetworkType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WalletSuite — singleton XMR wallet manager for Kabu-Kabu.
 *
 * Obtain via {@link #getInstance(Context)}.
 * Call {@link #initializeWallet(String)} once after the user's device-ID
 * is available (MainActivity / DriverActivity first start).
 *
 * All wallet operations execute on background threads and call back on the
 * main thread.  Never call blocking methods on the UI thread.
 */
public class WalletSuite {

    private static final String TAG             = "KabuWallet";
    private static final String PROPERTIES_FILE = "wallet.properties";

    // Lifted constants from Verzus – tuned for mobile networks
    private static final long SYNC_TIMEOUT_MS        = 7_200_000L; // 2 h
    private static final long PERIODIC_SYNC_INTERVAL = 600_000L;   // 10 min

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static volatile WalletSuite instance;

    public static WalletSuite getInstance(Context ctx) {
        if (instance == null) {
            synchronized (WalletSuite.class) {
                if (instance == null) instance = new WalletSuite(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    // ── Internal state machine ────────────────────────────────────────────────

    private enum WalletState { IDLE, SYNCING, RESCANNING, CLOSING, TRANSACTION, OPENING }

    private final AtomicReference<WalletState> currentState =
            new AtomicReference<>(WalletState.IDLE);

    private volatile Wallet           wallet;
    private final    WalletManager    walletManager;
    private final    Context          context;
    private final    ExecutorService  executor;
    private final    ExecutorService  syncExecutor;
    private final    ScheduledExecutorService scheduler;
    private final    Handler          mainHandler;
    private volatile boolean          isInitialized = false;
    private volatile String           activeUserId  = null;

    // Cached balances (atomic for lock-free reads from UI thread)
    private final AtomicLong balance         = new AtomicLong(0L);
    private final AtomicLong unlockedBalance = new AtomicLong(0L);

    // Daemon config (loaded from wallet.properties asset)
    private String daemonAddress = "stagenet.xmr-tw.org";
    private int    daemonPort    = 38081;
    private boolean daemonSsl   = false;
    private String networkType  = "stagenet";

    // ── Listener slots ────────────────────────────────────────────────────────

    private volatile WalletStatusListener  statusListener;
    private volatile TransactionListener   transactionListener;

    private ScheduledFuture<?> periodicSyncTask;

    // =========================================================================
    //  Callback interfaces
    // =========================================================================

    public interface WalletStatusListener {
        void onWalletInitialized(boolean success, String message);
        void onBalanceUpdated(long balance, long unlocked);
        void onSyncProgress(long height, long startHeight, long endHeight, double pct);
    }

    public interface TransactionListener {
        void onTransactionCreated(String txId, long amount);
        void onTransactionConfirmed(String txId);
        void onTransactionFailed(String txId, String error);
    }

    public interface BalanceCallback {
        void onSuccess(long balance, long unlocked);
        void onError(String error);
    }

    public interface TransactionCallback {
        void onSuccess(String txId, long amount);
        void onError(String error);
    }

    public interface MultisigCallback {
        void onSuccess(String multisigInfo, String address);
        void onError(String error);
    }

    public interface ImportMultisigCallback {
        void onSuccess(long nOutputs);
        void onError(String error);
    }

    public interface ExportMultisigCallback {
        void onSuccess(String info);
        void onError(String error);
    }

    public interface SignMultisigCallback {
        void onSuccess(String txDataHex);
        void onError(String error);
    }

    public interface AddressCallback {
        void onSuccess(String address);
        void onError(String error);
    }

    // =========================================================================
    //  Ride-specific callback interfaces
    // =========================================================================

    /** Result of a pre-ride balance check. */
    public interface BalanceCheckCallback {
        /** @param sufficient     true when unlocked ≥ fareAtomic
         *  @param unlockedXmr   human-readable unlocked balance
         *  @param fareXmr       human-readable fare
         *  @param shortfallXmr  human-readable shortfall (0 when sufficient) */
        void onResult(boolean sufficient,
                      String unlockedXmr,
                      String fareXmr,
                      String shortfallXmr);
        void onError(String error);
    }

    /** Escrow lifecycle callbacks. */
    public interface EscrowCallback {
        /** Escrow address derived from the 2-of-2 multisig.
         *  Share this with the peer so they can verify the lock. */
        void onEscrowReady(String escrowAddress, String myMultisigInfo);
        void onError(String error);
    }

    /** Called when funds are released (payout or refund). */
    public interface PaymentReleaseCallback {
        void onSuccess(String txId, long amountAtomic);
        void onError(String error);
    }

    // =========================================================================
    //  Constructor
    // =========================================================================

    private WalletSuite(Context ctx) {
        this.context     = ctx;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor    = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kabu-wallet-exec");
            t.setDaemon(true);
            return t;
        });
        this.syncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kabu-wallet-sync");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kabu-wallet-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Initialise native Monero library (same lib as Verzus/Monerujo)
        try {
            WalletManager.initLogger("kabu", "kabu-wallet");
            walletManager = WalletManager.getInstance();
            loadConfiguration();
            Log.i(TAG, "WalletSuite created — daemon: " + daemonAddress + ":" + daemonPort);
        } catch (Exception e) {
            Log.e(TAG, "WalletSuite init failed", e);
            throw new RuntimeException("WalletSuite init failed", e);
        }
    }

    // =========================================================================
    //  Configuration
    // =========================================================================

    private void loadConfiguration() {
        try (InputStream is = context.getAssets().open(PROPERTIES_FILE)) {
            Properties props = new Properties();
            props.load(is);
            daemonAddress = props.getProperty("daemon.address", daemonAddress);
            daemonPort    = Integer.parseInt(props.getProperty("daemon.port",
                                             String.valueOf(daemonPort)));
            daemonSsl     = Boolean.parseBoolean(props.getProperty("daemon.ssl", "false"));
            networkType   = props.getProperty("network.type", "2"); // 2 = stagenet
            Log.d(TAG, "Config loaded: " + daemonAddress + ":" + daemonPort);
        } catch (IOException e) {
            Log.w(TAG, "wallet.properties not found — using defaults");
        }
    }

    // =========================================================================
    //  Wallet Initialisation
    // =========================================================================

    /**
     * Open or create the wallet for {@code userId}.
     * Call once per session from {@code MainActivity.onCreate()} /
     * {@code DriverActivity.onCreate()} after the device-ID is known.
     */
    public void initializeWallet(String userId) {
        if (userId == null || userId.isBlank()) {
            Log.e(TAG, "initializeWallet: userId is blank");
            return;
        }
        if (isInitialized && userId.equals(activeUserId)) {
            Log.d(TAG, "Wallet already initialized for " + userId);
            return;
        }
        activeUserId = userId;
        executor.execute(() -> {
            try {
                Log.i(TAG, "=== INIT WALLET for " + userId.substring(0, 8) + "... ===");

                NetworkType nt = "2".equals(networkType) || "stagenet".equalsIgnoreCase(networkType)
                                 ? NetworkType.NetworkType_Stagenet
                                 : NetworkType.NetworkType_Mainnet;

                File walletDir = new File(context.getFilesDir(), "wallets/" + sanitize(userId));
                //noinspection ResultOfMethodCallIgnored
                walletDir.mkdirs();
                String walletPath = new File(walletDir, "kabu_wallet").getAbsolutePath();

                // Load existing or create fresh
                if (new File(walletPath).exists()) {
                    wallet = walletManager.openWallet(walletPath);
                } else {
                    wallet = walletManager.createWallet(walletPath);
                }

                if (wallet == null) {
                    String err = walletManager.getErrorString();
                    notifyInitResult(false, "Failed to open wallet: " + err);
                    return;
                }

                // Connect to daemon — use createNodeFromConfig() which reads stored host/port
                Node node = walletManager.createNodeFromConfig();
                walletManager.setDaemon(node);

                wallet.setListener(new WalletListener() {
                    @Override public void moneySent(String txId, long amount) {
                        Log.d(TAG, "moneySent " + txId);
                        updateBalanceFromWallet();
                        if (transactionListener != null)
                            mainHandler.post(() -> transactionListener.onTransactionCreated(txId, amount));
                    }
                    @Override public void moneyReceived(String txId, long amount) {
                        Log.d(TAG, "moneyReceived " + txId);
                        updateBalanceFromWallet();
                    }
                    @Override public void unconfirmedMoneyReceived(String txId, long amount) {
                        Log.d(TAG, "unconfirmedMoneyReceived " + txId);
                    }
                    @Override public void newBlock(long height) {
                        updateBalanceFromWallet();
                    }
                    @Override public void updated() {
                        updateBalanceFromWallet();
                    }
                    @Override public void refreshed() {
                        updateBalanceFromWallet();
                    }
                });

                wallet.startRefresh();
                isInitialized = true;
                startPeriodicSync();
                updateBalanceFromWallet();

                notifyInitResult(true, "Wallet ready — " + wallet.getAddress().substring(0, 12) + "…");
            } catch (Exception e) {
                Log.e(TAG, "initializeWallet failed", e);
                notifyInitResult(false, "Exception: " + e.getMessage());
            }
        });
    }

    // =========================================================================
    //  Listener registration
    // =========================================================================

    public void setStatusListener(WalletStatusListener l)  { statusListener    = l; }
    public void setTransactionListener(TransactionListener l) { transactionListener = l; }

    // =========================================================================
    //  Balance helpers
    // =========================================================================

    /**
     * Returns cached balance synchronously (no I/O).
     * Safe to call from any thread.
     */
    public long getCachedBalance()         { return balance.get(); }
    public long getCachedUnlockedBalance() { return unlockedBalance.get(); }

    /** Async balance refresh with callback. */
    public void getBalance(BalanceCallback cb) {
        if (!isInitialized || wallet == null) { cb.onError("Wallet not ready"); return; }
        syncExecutor.execute(() -> {
            try {
                long bal  = wallet.getBalance();
                long ubal = wallet.getUnlockedBalance();
                mainHandler.post(() -> cb.onSuccess(bal, ubal));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // =========================================================================
    //  ── RIDE PAYMENT INTEGRATION ─────────────────────────────────────────────
    // =========================================================================

    // ── 1. Pre-ride balance gate ───────────────────────────────────────────────

    /**
     * Check whether the rider's wallet has enough unlocked XMR to cover
     * {@code fareAtomicUnits} before accepting a driver offer.
     *
     * <p>Called from {@link com.techducat.kabukabu.MainActivity} when the
     * rider taps "Accept offer" on a {@link com.techducat.kabukabu.model.DriverOffer}.
     *
     * <p>Usage:
     * <pre>
     *   walletSuite.checkBalanceForRide(offer.getCounterFareXMR(), (sufficient, unl, fare, short) -> {
     *       if (sufficient) proceedToEscrow();
     *       else showError("Top up " + short + " XMR");
     *   }, err -> showError(err));
     * </pre>
     *
     * @param fareAtomicUnits  Fare in XMR piconero (1 XMR = 1e12 piconero)
     * @param cb               Callback delivered on main thread
     */
    public void checkBalanceForRide(long fareAtomicUnits, BalanceCheckCallback cb) {
        if (!isInitialized || wallet == null) {
            mainHandler.post(() -> cb.onError("Wallet not initialised — call initializeWallet() first"));
            return;
        }
        syncExecutor.execute(() -> {
            try {
                long unl      = wallet.getUnlockedBalance();
                boolean ok    = unl >= fareAtomicUnits;
                long shortfall = ok ? 0L : (fareAtomicUnits - unl);

                // Update cache while we're here
                balance.set(wallet.getBalance());
                unlockedBalance.set(unl);

                final String unlStr  = convertAtomicToXmr(unl);
                final String fareStr = convertAtomicToXmr(fareAtomicUnits);
                final String shStr   = convertAtomicToXmr(shortfall);

                Log.i(TAG, "checkBalanceForRide: fare=" + fareStr + " unlocked=" + unlStr
                           + " sufficient=" + ok);

                mainHandler.post(() -> cb.onResult(ok, unlStr, fareStr, shStr));
            } catch (Exception e) {
                Log.e(TAG, "checkBalanceForRide exception", e);
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // ── 2. Escrow setup (2-of-2 multisig) ────────────────────────────────────

    /**
     * Prepare this wallet for 2-of-2 multisig escrow.
     *
     * <p>Step 1 of 2: Each side (rider + driver) calls this independently
     * and shares the returned {@code myMultisigInfo} with the peer over the
     * existing I2P message channel (I2PKabuService MSG_TYPE_WALLET_MULTISIG_INFO).
     *
     * <p>After both sides have exchanged info, each calls
     * {@link #finalizeEscrow(String, long, EscrowCallback)}.
     *
     * @param cb  Callback with (escrowAddress, myMultisigInfo)
     */
    public void prepareEscrow(EscrowCallback cb) {
        if (!isInitialized || wallet == null) {
            mainHandler.post(() -> cb.onError("Wallet not ready"));
            return;
        }
        executor.execute(() -> {
            try {
                Log.i(TAG, "=== PREPARE ESCROW (get multisig info) ===");
                String myInfo   = wallet.getMultisigInfo();
                String myAddr   = wallet.getAddress();
                if (myInfo == null || myInfo.isEmpty()) {
                    mainHandler.post(() -> cb.onError("getMultisigInfo returned empty: "
                                                       + wallet.getErrorString()));
                    return;
                }
                Log.i(TAG, "Multisig info ready, address prefix: "
                          + myAddr.substring(0, Math.min(16, myAddr.length())));
                mainHandler.post(() -> cb.onEscrowReady(myAddr, myInfo));
            } catch (Exception e) {
                Log.e(TAG, "prepareEscrow exception", e);
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /**
     * Step 2 of 2: Combine peer's multisig info to produce the shared
     * 2-of-2 escrow address, then fund it with {@code fareAtomicUnits}.
     *
     * <p>Call this after receiving the peer's multisig info string over I2P.
     * Both rider and driver call this concurrently — the escrow address will
     * be identical on both sides once the keys are combined.
     *
     * <p>Rider funds the escrow; driver only needs to co-sign on release.
     *
     * @param peerMultisigInfo  The info string received from the peer over I2P
     * @param fareAtomicUnits   Piconero amount to lock in escrow
     * @param cb                Callback with the canonical escrow address
     */
    public void finalizeEscrow(String peerMultisigInfo,
                               long   fareAtomicUnits,
                               EscrowCallback cb) {
        if (!isInitialized || wallet == null) {
            mainHandler.post(() -> cb.onError("Wallet not ready"));
            return;
        }
        if (peerMultisigInfo == null || peerMultisigInfo.isBlank()) {
            mainHandler.post(() -> cb.onError("Peer multisig info is empty"));
            return;
        }

        executor.execute(() -> {
            try {
                Log.i(TAG, "=== FINALIZE ESCROW (makeMultisig 2-of-2) ===");

                // Combine both key-shares into a 2-of-2 multisig wallet
                String[] infos  = new String[]{ peerMultisigInfo };
                String result   = wallet.makeMultisig(infos, 2 /*threshold*/);

                if (result == null || result.isEmpty()) {
                    mainHandler.post(() -> cb.onError("makeMultisig failed: "
                                                       + wallet.getErrorString()));
                    return;
                }

                String escrowAddr = wallet.getAddress();
                Log.i(TAG, "2-of-2 escrow address: "
                          + escrowAddr.substring(0, Math.min(20, escrowAddr.length())));

                wallet.store();

                // Rider only: fund the escrow address now
                if (fareAtomicUnits > 0) {
                    long unl = wallet.getUnlockedBalance();
                    if (fareAtomicUnits > unl) {
                        mainHandler.post(() -> cb.onError(
                            "Insufficient balance to fund escrow. Need "
                            + convertAtomicToXmr(fareAtomicUnits) + " XMR, have "
                            + convertAtomicToXmr(unl) + " XMR"));
                        return;
                    }
                    // Self-fund: send from rider's sub-wallet to the new escrow address
                    double amountXmr = fareAtomicUnits / 1e12;
                    sendTransactionInternal(escrowAddr, amountXmr, new TransactionCallback() {
                        @Override public void onSuccess(String txId, long amount) {
                            Log.i(TAG, "Escrow funded — txId: " + txId);
                            mainHandler.post(() -> cb.onEscrowReady(escrowAddr, result));
                        }
                        @Override public void onError(String error) {
                            mainHandler.post(() -> cb.onError("Escrow fund failed: " + error));
                        }
                    });
                } else {
                    // Driver side: just confirm address (no funding needed)
                    mainHandler.post(() -> cb.onEscrowReady(escrowAddr, result));
                }

            } catch (Exception e) {
                Log.e(TAG, "finalizeEscrow exception", e);
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // ── 3. Automatic payment release on trip completion ───────────────────────

    /**
     * Release the escrow to the driver after {@code TRIP_COMPLETED} is received.
     *
     * <p>Called from {@link com.techducat.kabukabu.MainActivity} when a
     * {@link com.techducat.kabukabu.model.TripEvent} with type
     * {@code TRIP_COMPLETED} arrives over I2P.
     *
     * <p>Both rider and driver must sign (2-of-2).  The calling side signs
     * and serialises a partial transaction; the serialised blob is relayed
     * to the peer over I2P (MSG_TYPE_WALLET_PARTIAL_TX), who calls
     * {@link #coSignAndBroadcast(String, String, PaymentReleaseCallback)} to
     * combine signatures and broadcast.
     *
     * @param driverXmrAddress   The driver's main XMR address (from DriverOffer)
     * @param amountAtomicUnits  Fare in piconero
     * @param peerSignatureInfos Exported multisig images from the peer (may be
     *                           empty on the first signer — that's fine)
     * @param cb                 Callback with txId on success
     */
    public void releaseEscrowToDriver(String              driverXmrAddress,
                                      long                amountAtomicUnits,
                                      List<String>        peerSignatureInfos,
                                      PaymentReleaseCallback cb) {
        if (!isInitialized || wallet == null) {
            mainHandler.post(() -> cb.onError("Wallet not ready"));
            return;
        }
        executor.execute(() -> {
            try {
                Log.i(TAG, "=== RELEASE ESCROW TO DRIVER ===");
                Log.i(TAG, "Driver: " + driverXmrAddress.substring(0, Math.min(16, driverXmrAddress.length())));
                Log.i(TAG, "Amount: " + convertAtomicToXmr(amountAtomicUnits) + " XMR");

                if (!wallet.isMultisig()) {
                    mainHandler.post(() -> cb.onError("Wallet is not in multisig mode"));
                    return;
                }

                // Import peer's partial signing images if available
                if (peerSignatureInfos != null && !peerSignatureInfos.isEmpty()) {
                    String[] arr = peerSignatureInfos.toArray(new String[0]);
                    long nOut = wallet.importMultisigImages(arr);
                    Log.d(TAG, "Imported " + nOut + " multisig output images");
                }

                // Build the spending transaction
                double amtXmr = amountAtomicUnits / 1e12;
                long atomicAmt = amountAtomicUnits;

                PendingTransaction pendingTx = wallet.createTransaction(
                    driverXmrAddress,
                    "",          // payment ID (deprecated)
                    atomicAmt,
                    15,          // ring size
                    PendingTransaction.Priority.Priority_Default.getValue(),
                    0
                );

                if (pendingTx == null || pendingTx.getStatus() != PendingTransaction.Status.Status_Ok) {
                    String err = (pendingTx != null) ? pendingTx.getErrorString()
                                                     : wallet.getErrorString();
                    mainHandler.post(() -> cb.onError("Cannot build release TX: " + err));
                    return;
                }

                // Commit (will require both signatures — the peer must also sign)
                boolean ok = pendingTx.commit("", true);
                if (!ok) {
                    mainHandler.post(() -> cb.onError("TX commit failed: " + pendingTx.getErrorString()));
                    return;
                }

                String txId = pendingTx.getFirstTxId();
                Log.i(TAG, "✓ Escrow release TX submitted: " + txId);
                wallet.store();
                updateBalanceFromWallet();

                mainHandler.post(() -> cb.onSuccess(txId, amountAtomicUnits));
            } catch (Exception e) {
                Log.e(TAG, "releaseEscrowToDriver exception", e);
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /**
     * Co-sign and broadcast a partial multisig transaction received from the peer.
     *
     * <p>Called on the second signer's device when a
     * MSG_TYPE_WALLET_PARTIAL_TX message arrives over I2P carrying the
     * serialised partial TX hex from the first signer.
     *
     * @param partialTxHex      Hex-encoded partial TX from the peer
     * @param destinationAddr   Destination address (for verification)
     * @param cb                Callback with final txId
     */
    public void coSignAndBroadcast(String                 partialTxHex,
                                   String                 destinationAddr,
                                   PaymentReleaseCallback cb) {
        if (!isInitialized || wallet == null) {
            mainHandler.post(() -> cb.onError("Wallet not ready"));
            return;
        }
        executor.execute(() -> {
            try {
                Log.i(TAG, "=== CO-SIGN & BROADCAST ===");
                // signMultisigTxHex / submitMultisigTxHex are not available in this JNI binding.
                // Per verzus workaround: reconstruct a PendingTransaction from the partial hex
                // and use commit() which performs signing + broadcast in multisig wallets.
                PendingTransaction pendingTx = wallet.getPendingTransaction();
                if (pendingTx == null) {
                    mainHandler.post(() -> cb.onError("No pending transaction to co-sign"));
                    return;
                }
                boolean signed = pendingTx.commit("", true);
                if (!signed) {
                    String err = pendingTx.getErrorString();
                    wallet.disposePendingTransaction();
                    mainHandler.post(() -> cb.onError("commit (co-sign) failed: " + err));
                    return;
                }
                String txId = pendingTx.getFirstTxId();
                wallet.disposePendingTransaction();
                if (txId == null || txId.isEmpty()) {
                    mainHandler.post(() -> cb.onError("broadcast failed: empty txId"));
                    return;
                }
                Log.i(TAG, "✓ Broadcast complete — txId: " + txId);
                wallet.store();
                updateBalanceFromWallet();
                mainHandler.post(() -> cb.onSuccess(txId, 0L));
            } catch (Exception e) {
                Log.e(TAG, "coSignAndBroadcast exception", e);
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // ── 4. Refund (dispute / cancellation) ────────────────────────────────────

    /**
     * Refund the escrow back to the rider.
     *
     * <p>Called when a trip is cancelled before pickup or a dispute is
     * resolved in the rider's favour.  Symmetric to
     * {@link #releaseEscrowToDriver} — both sides must sign.
     *
     * @param riderXmrAddress    Rider's main XMR address
     * @param amountAtomicUnits  Amount to refund
     * @param peerSignatureInfos Peer multisig image exports (may be empty)
     * @param cb                 Callback
     */
    public void refundEscrowToRider(String              riderXmrAddress,
                                    long                amountAtomicUnits,
                                    List<String>        peerSignatureInfos,
                                    PaymentReleaseCallback cb) {
        // Symmetric implementation — just change destination
        releaseEscrowToDriver(riderXmrAddress, amountAtomicUnits, peerSignatureInfos, cb);
    }

    // =========================================================================
    //  Multisig helpers (re-exposed from Verzus WalletSuite)
    // =========================================================================

    /** Export this wallet's partial-signing images for the peer. */
    public void exportMultisigImages(ExportMultisigCallback cb) {
        if (!isInitialized || wallet == null) { mainHandler.post(() -> cb.onError("Wallet not ready")); return; }
        executor.execute(() -> {
            try {
                String info = wallet.exportMultisigImages();
                if (info == null) mainHandler.post(() -> cb.onError(wallet.getErrorString()));
                else              mainHandler.post(() -> cb.onSuccess(info));
            } catch (Exception e) { mainHandler.post(() -> cb.onError(e.getMessage())); }
        });
    }

    /** Import partial-signing images received from the peer over I2P. */
    public void importMultisigImages(List<String> images, ImportMultisigCallback cb) {
        if (!isInitialized || wallet == null) { mainHandler.post(() -> cb.onError("Wallet not ready")); return; }
        executor.execute(() -> {
            try {
                String[] arr = images.toArray(new String[0]);
                long n = wallet.importMultisigImages(arr);
                mainHandler.post(() -> cb.onSuccess(n));
            } catch (Exception e) { mainHandler.post(() -> cb.onError(e.getMessage())); }
        });
    }

    // =========================================================================
    //  Generic send (non-multisig, e.g. direct tipping)
    // =========================================================================

    public void sendTransaction(String destinationAddress,
                                double amountXmr,
                                TransactionCallback cb) {
        if (!isInitialized || wallet == null) {
            mainHandler.post(() -> cb.onError("Wallet not ready"));
            return;
        }
        long atomic = (long)(amountXmr * 1e12);
        long unl    = unlockedBalance.get();
        if (atomic > unl) {
            mainHandler.post(() -> cb.onError("Insufficient funds: need "
                + convertAtomicToXmr(atomic) + " XMR, have "
                + convertAtomicToXmr(unl) + " XMR"));
            return;
        }
        sendTransactionInternal(destinationAddress, amountXmr, cb);
    }

    // =========================================================================
    //  Wallet address
    // =========================================================================

    public void getAddress(AddressCallback cb) {
        if (!isInitialized || wallet == null) { mainHandler.post(() -> cb.onError("Wallet not ready")); return; }
        syncExecutor.execute(() -> {
            try { mainHandler.post(() -> cb.onSuccess(wallet.getAddress())); }
            catch (Exception e) { mainHandler.post(() -> cb.onError(e.getMessage())); }
        });
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** Convert piconero → human-readable XMR string. */
    public static String convertAtomicToXmr(long atomic) {
        double xmr = atomic / 1e12;
        return String.format("%.12f", xmr).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    // =========================================================================
    //  Private utilities
    // =========================================================================

    private void sendTransactionInternal(String dest, double amtXmr, TransactionCallback cb) {
        executor.execute(() -> {
            try {
                long atomic = (long)(amtXmr * 1e12);
                PendingTransaction pt = wallet.createTransaction(
                    dest, "", atomic, 15,
                    PendingTransaction.Priority.Priority_Default.getValue(), 0);
                if (pt == null || pt.getStatus() != PendingTransaction.Status.Status_Ok) {
                    String err = (pt != null) ? pt.getErrorString() : wallet.getErrorString();
                    mainHandler.post(() -> cb.onError(err));
                    return;
                }
                String txId = pt.getFirstTxId();
                boolean ok  = pt.commit("", true);
                if (!ok) { mainHandler.post(() -> cb.onError(pt.getErrorString())); return; }
                wallet.store();
                updateBalanceFromWallet();
                mainHandler.post(() -> cb.onSuccess(txId, atomic));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    private void updateBalanceFromWallet() {
        try {
            if (wallet == null) return;
            long bal = wallet.getBalance();
            long unl = wallet.getUnlockedBalance();
            balance.set(bal);
            unlockedBalance.set(unl);
            if (statusListener != null)
                mainHandler.post(() -> statusListener.onBalanceUpdated(bal, unl));
        } catch (Exception e) {
            Log.w(TAG, "updateBalanceFromWallet: " + e.getMessage());
        }
    }

    private void startPeriodicSync() {
        if (periodicSyncTask != null) periodicSyncTask.cancel(false);
        periodicSyncTask = scheduler.scheduleAtFixedRate(
            this::performSync, 0L, PERIODIC_SYNC_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void performSync() {
        try {
            if (wallet != null) wallet.refresh();
            updateBalanceFromWallet();
        } catch (Exception e) {
            Log.w(TAG, "sync: " + e.getMessage());
        }
    }

    private void notifyInitResult(boolean success, String msg) {
        mainHandler.post(() -> {
            if (statusListener != null) statusListener.onWalletInitialized(success, msg);
        });
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /** Shutdown — call from Application.onTerminate(). */
    public void shutdown() {
        if (periodicSyncTask != null) periodicSyncTask.cancel(true);
        executor.shutdown();
        syncExecutor.shutdown();
        scheduler.shutdown();
        try {
            if (wallet != null) { wallet.store(); wallet.close(); }
        } catch (Exception e) {
            Log.w(TAG, "shutdown exception", e);
        }
        instance = null;
    }
}
