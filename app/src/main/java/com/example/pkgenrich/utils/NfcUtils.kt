package com.example.pkgenrich.utils

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.util.Log
import android.widget.Toast

class NfcUtils(private val activity: Activity) {
    private val TAG = "NfcUtils"
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var nfcPendingIntent: PendingIntent
    private lateinit var ndefFilters: Array<IntentFilter>
    private lateinit var techListsArray: Array<Array<String>>
    private var onNfcMessageReceived: ((String) -> Unit)? = null

    init {
        initializeNfc()
    }

    private fun initializeNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(activity)
        nfcAdapter?.let { adapter ->
            if (!adapter.isEnabled) {
                Log.w(TAG, "NFC is disabled")
                Toast.makeText(activity, "Please enable NFC", Toast.LENGTH_LONG).show()
                return@let
            }

            // Create a PendingIntent for NFC intents
            val intent = Intent(activity, activity.javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            nfcPendingIntent = PendingIntent.getActivity(
                activity, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            // Create intent filters for NDEF messages
            val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                try {
                    addDataType("application/com.example.pkgenrich")
                } catch (e: IntentFilter.MalformedMimeTypeException) {
                    Log.e(TAG, "Failed to set MIME type", e)
                    return@let
                }
            }
            ndefFilters = arrayOf(ndefFilter)

            // Set up tech lists
            techListsArray = arrayOf(arrayOf(Ndef::class.java.name))
        }
    }

    fun isNfcEnabled(): Boolean {
        return nfcAdapter?.isEnabled == true
    }

    fun enableReaderMode(onTagDiscovered: (Tag) -> Unit) {
        nfcAdapter?.enableReaderMode(
            activity,
            { tag: Tag ->
                Log.d(TAG, "Tag discovered: ${tag.id.joinToString("") { "%02x".format(it) }}")
                onTagDiscovered(tag)
            },
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NFC_BARCODE,
            null
        )
    }

    fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(activity)
    }

    fun writeDeviceInfoToTag(tag: Tag, deviceId: String) {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                val record = NdefRecord.createMime(
                    "application/com.example.pkgenrich",
                    deviceId.toByteArray()
                )
                val message = NdefMessage(arrayOf(record))
                ndef.writeNdefMessage(message)
                ndef.close()
                Log.d(TAG, "Successfully wrote device info to tag")
                activity.runOnUiThread {
                    Toast.makeText(activity, "Device info written to tag", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to tag", e)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Failed to write to tag: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Log.w(TAG, "Tag is not NDEF")
        }
    }

    fun readDeviceInfoFromTag(tag: Tag): String? {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                val ndefMessage = ndef.ndefMessage
                ndef.close()
                
                if (ndefMessage != null) {
                    val record = ndefMessage.records.firstOrNull()
                    if (record != null) {
                        return String(record.payload)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read from tag", e)
            }
        }
        return null
    }

    fun setOnNfcMessageReceivedListener(listener: (String) -> Unit) {
        onNfcMessageReceived = listener
    }

    fun handleNfcIntent(intent: Intent) {
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {
            val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            rawMessages?.firstOrNull()?.let { message ->
                val ndefMessage = message as NdefMessage
                val record = ndefMessage.records.firstOrNull()
                record?.let {
                    val payload = String(it.payload)
                    onNfcMessageReceived?.invoke(payload)
                }
            }
        }
    }

    fun enableForegroundDispatch() {
        nfcAdapter?.enableForegroundDispatch(
            activity,
            nfcPendingIntent,
            ndefFilters,
            techListsArray
        )
    }

    fun disableForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(activity)
    }
}
