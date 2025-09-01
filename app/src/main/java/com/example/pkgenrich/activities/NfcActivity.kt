package com.example.pkgenrich.activities

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.pkgenrich.utils.NfcUtils

abstract class NfcActivity : AppCompatActivity() {
    private val TAG = "NfcActivity"
    private lateinit var nfcUtils: NfcUtils
    private var isWaitingForNfc = false
    private var isServer = false
    private var peerDeviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcUtils = NfcUtils(this)
        setupNfcCallbacks()
    }

    private fun setupNfcCallbacks() {
        nfcUtils.setOnNfcMessageReceivedListener { payload ->
            handleNfcPayload(payload)
        }
    }

    fun startNfcDiscovery() {
        if (!nfcUtils.isNfcEnabled()) {
            Toast.makeText(this, "Please enable NFC", Toast.LENGTH_LONG).show()
            return
        }

        isWaitingForNfc = true
        nfcUtils.enableReaderMode { tag ->
            handleDiscoveredTag(tag)
        }
    }

    fun stopNfcDiscovery() {
        isWaitingForNfc = false
        nfcUtils.disableReaderMode()
    }

    private fun handleDiscoveredTag(tag: Tag) {
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                if (isWaitingForNfc) {
                    // Server writes its MAC address
                    val deviceId = getDeviceIdentifier()
                    nfcUtils.writeDeviceInfoToTag(tag, deviceId)
                    isServer = true
                    onRoleDetermined(true, deviceId)
                } else {
                    // Client reads server's MAC address
                    val serverId = nfcUtils.readDeviceInfoFromTag(tag)
                    if (serverId != null) {
                        peerDeviceId = serverId
                        isServer = false
                        onRoleDetermined(false, serverId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling NFC tag", e)
            Toast.makeText(this, "Error handling NFC tag: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleNfcPayload(payload: String) {
        if (payload.startsWith("WIFI_DIRECT:")) {
            val serverIp = payload.substringAfter("WIFI_DIRECT:")
            onWifiDirectInfoReceived(serverIp)
        }
    }

    private fun getDeviceIdentifier(): String {
        return Build.MANUFACTURER + Build.MODEL + Build.SERIAL
    }

    override fun onResume() {
        super.onResume()
        nfcUtils.enableForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcUtils.disableForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        nfcUtils.handleNfcIntent(intent)
    }

    // Abstract methods to be implemented by subclasses
    abstract fun onRoleDetermined(isServer: Boolean, deviceId: String)
    abstract fun onWifiDirectInfoReceived(serverIp: String)
} 