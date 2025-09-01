package com.example.pkgenrich.activities

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.pkgenrich.utils.WifiDirectUtils

abstract class WifiActivity : AppCompatActivity() {
    private val TAG = "WifiActivity"
    private lateinit var wifiDirectUtils: WifiDirectUtils
    private var isGroupOwner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiDirectUtils = WifiDirectUtils(this)
        setupWifiDirectCallbacks()
    }

    private fun setupWifiDirectCallbacks() {
        wifiDirectUtils.setOnPeersAvailableListener { peers ->
            onPeersAvailable(peers)
        }

        wifiDirectUtils.setOnConnectionInfoAvailableListener { info ->
            onConnectionInfoAvailable(info)
        }

        wifiDirectUtils.setOnGroupCreatedListener { groupOwnerIp ->
            isGroupOwner = true
            onGroupCreated()
        }

        wifiDirectUtils.setOnGroupRemovedListener {
            isGroupOwner = false
            onGroupRemoved()
        }
    }

    fun initializeWifiDirect() {
        wifiDirectUtils.initialize()
    }

    fun startPeerDiscovery() {
        wifiDirectUtils.discoverPeers()
    }

    fun stopPeerDiscovery() {
        wifiDirectUtils.stopPeerDiscovery()
    }

    fun createGroup() {
        wifiDirectUtils.createGroup()
    }

    fun removeGroup() {
        wifiDirectUtils.removeGroup()
    }

    fun connectToDevice(device: WifiP2pDevice) {
        wifiDirectUtils.connectToDevice(device)
    }

    fun connectToDeviceById(deviceId: String) {
        wifiDirectUtils.connectToDeviceById(deviceId)
    }

    fun requestPeers() {
        wifiDirectUtils.requestPeers()
    }

    fun requestConnectionInfo() {
        wifiDirectUtils.requestConnectionInfo()
    }

    fun requestGroupInfo() {
        wifiDirectUtils.requestGroupInfo()
    }

    override fun onResume() {
        super.onResume()
        wifiDirectUtils.registerReceiver()
    }

    override fun onPause() {
        super.onPause()
        wifiDirectUtils.unregisterReceiver()
    }

    // Abstract methods to be implemented by subclasses
    abstract fun onPeersAvailable(peers: WifiP2pDeviceList)
    abstract fun onConnectionInfoAvailable(info: WifiP2pInfo)
    abstract fun onGroupCreated()
    abstract fun onGroupRemoved()
} 