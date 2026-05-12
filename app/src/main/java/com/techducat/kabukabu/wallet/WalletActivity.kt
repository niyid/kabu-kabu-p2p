package com.techducat.kabukabu.wallet

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.techducat.kabukabu.KabuKabuApp
import com.techducat.kabukabu.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * WalletActivity — Kabu-Kabu embedded XMR wallet screen.
 *
 * Provides:
 *  • Balance display (total + unlocked)
 *  • Receive address with copy button
 *  • Manual balance refresh
 *  • Status indicator (syncing / ready)
 *
 * The escrow and payment flows are headless — they are triggered
 * automatically by ride events in MainActivity / DriverActivity
 * via RideWalletManager.  This activity only surfaces account info.
 *
 * Launch from the ⋮ menu → "Wallet" in MainActivity.
 */
@AndroidEntryPoint
class WalletActivity : AppCompatActivity(), WalletSuite.WalletStatusListener {

    companion object { private const val TAG = "WalletActivity" }

    private lateinit var tvBalance:      TextView
    private lateinit var tvUnlocked:     TextView
    private lateinit var tvAddress:      TextView
    private lateinit var tvStatus:       TextView
    private lateinit var btnRefresh:     Button
    private lateinit var btnCopyAddress: Button
    private lateinit var progressBar:    ProgressBar

    private lateinit var walletSuite: WalletSuite
    private lateinit var deviceId:    String

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet)
        supportActionBar?.title = "XMR Wallet"

        val prefs = getSharedPreferences(KabuKabuApp.PREFS_NAME, MODE_PRIVATE)
        deviceId  = prefs.getString(KabuKabuApp.KEY_DEVICE_ID, "") ?: ""

        bindViews()

        walletSuite = WalletSuite.getInstance(this)
        walletSuite.setStatusListener(this)

        // Initialize if not already done
        if (deviceId.isNotEmpty()) {
            walletSuite.initializeWallet(deviceId)
        } else {
            tvStatus.text = "Device not registered — complete setup first"
        }

        btnRefresh.setOnClickListener { refreshBalance() }
        btnCopyAddress.setOnClickListener { copyAddress() }
    }

    override fun onDestroy() {
        super.onDestroy()
        walletSuite.setStatusListener(null)
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        tvBalance      = findViewById(R.id.tv_xmr_balance)
        tvUnlocked     = findViewById(R.id.tv_xmr_unlocked)
        tvAddress      = findViewById(R.id.tv_xmr_address)
        tvStatus       = findViewById(R.id.tv_wallet_status)
        btnRefresh     = findViewById(R.id.btn_wallet_refresh)
        btnCopyAddress = findViewById(R.id.btn_copy_address)
        progressBar    = findViewById(R.id.wallet_progress)
    }

    // ── WalletStatusListener ──────────────────────────────────────────────────

    override fun onWalletInitialized(success: Boolean, message: String) {
        tvStatus.text = message
        progressBar.visibility = if (success) View.GONE else View.VISIBLE
        if (success) refreshBalance()
    }

    override fun onBalanceUpdated(balance: Long, unlocked: Long) {
        tvBalance.text  = "${WalletSuite.convertAtomicToXmr(balance)} XMR"
        tvUnlocked.text = "${WalletSuite.convertAtomicToXmr(unlocked)} XMR available"
        progressBar.visibility = View.GONE
    }

    override fun onSyncProgress(height: Long, startHeight: Long, endHeight: Long, pct: Double) {
        val pctStr = "%.1f".format(pct)
        tvStatus.text = "Syncing… $pctStr% (block $height / $endHeight)"
        progressBar.visibility = View.VISIBLE
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun refreshBalance() {
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Refreshing…"
        walletSuite.getBalance(object : WalletSuite.BalanceCallback {
            override fun onSuccess(balance: Long, unlocked: Long) {
                tvBalance.text  = "${WalletSuite.convertAtomicToXmr(balance)} XMR"
                tvUnlocked.text = "${WalletSuite.convertAtomicToXmr(unlocked)} XMR available"
                tvStatus.text   = "Ready"
                progressBar.visibility = View.GONE
            }
            override fun onError(error: String) {
                tvStatus.text = "Error: $error"
                progressBar.visibility = View.GONE
            }
        })

        walletSuite.getAddress(object : WalletSuite.AddressCallback {
            override fun onSuccess(address: String) {
                tvAddress.text = address
            }
            override fun onError(error: String) {
                tvAddress.text = "Address unavailable"
            }
        })
    }

    private fun copyAddress() {
        val address = tvAddress.text.toString()
        if (address.isNotEmpty() && address != "Address unavailable") {
            val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clip.setPrimaryClip(
                android.content.ClipData.newPlainText("XMR Address", address)
            )
            Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
        }
    }
}
