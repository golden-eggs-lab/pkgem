package com.example.pkgenrich.utils

import android.Manifest
import android.content.*
import android.net.NetworkInfo
import android.net.wifi.p2p.*
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.pkgenrich.MainActivity
import android.widget.Toast

class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    private var isGroupOwner = false
    private var groupOwnerAddress: String? = null
    private var onGroupCreated: ((String) -> Unit)? = null
    private var onGroupJoined: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onPeersAvailable: ((WifiP2pDeviceList) -> Unit)? = null

    fun setOnGroupCreatedListener(listener: (String) -> Unit) {
        onGroupCreated = listener
    }

    fun setOnGroupJoinedListener(listener: (String) -> Unit) {
        onGroupJoined = listener
    }

    fun setOnErrorListener(listener: (String) -> Unit) {
        onError = listener
    }

    fun setOnPeersAvailableListener(listener: (WifiP2pDeviceList) -> Unit) {
        onPeersAvailable = listener
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Log.d("WifiDirect", "Wifi P2P is enabled")
                } else {
                    Log.e("WifiDirect", "Wifi P2P is not enabled")
                    onError?.invoke("WifiDirect is not enabled")
                }
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d("WifiDirect", "Peers changed")
                if (hasRequiredPermissions(context)) {
                    manager.requestPeers(channel, object : WifiP2pManager.PeerListListener {
                        override fun onPeersAvailable(peers: WifiP2pDeviceList) {
                            onPeersAvailable?.invoke(peers)
                        }
                    })
                } else {
                    Log.e("WifiDirect", "Missing required permissions")
                    onError?.invoke("Missing required permissions for WifiDirect")
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                if (networkInfo?.isConnected == true) {
                    Log.d("WifiDirect", "Connection established")
                    manager.requestConnectionInfo(channel, object : WifiP2pManager.ConnectionInfoListener {
                        override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
                            onConnectionInfoAvailable(info)
                        }
                    })
                } else {
                    Log.d("WifiDirect", "Connection lost")
                }
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                Log.d("WifiDirect", "Device changed: ${device?.deviceName}")
            }
        }
    }

    fun createGroup() {
        try {
            Log.d("WifiDirect", "Creating WifiDirect group")
            
            // first stop previous discovery
            manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WifiDirect", "Stopped peer discovery")
                    createGroupAfterStopDiscovery()
                }
                override fun onFailure(reason: Int) {
                    Log.e("WifiDirect", "Failed to stop peer discovery: $reason")
                    // even if stop discovery fails, try to create group
                    createGroupAfterStopDiscovery()
                }
            })
        } catch (e: Exception) {
            Log.e("WifiDirect", "Error in createGroup", e)
            onError?.invoke("Failed to create WifiDirect group: ${e.message}")
        }
    }

    private fun createGroupAfterStopDiscovery() {
        try {
            manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WifiDirect", "Group creation initiated successfully")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Creating WifiDirect group...", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(reason: Int) {
                    val errorMessage = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "WifiDirect is not supported on this device"
                        WifiP2pManager.BUSY -> "WifiDirect is busy, please try again"
                        WifiP2pManager.ERROR -> "Internal error occurred"
                        else -> "Failed to create group: $reason"
                    }
                    Log.e("WifiDirect", "Failed to create group: $errorMessage")
                    onError?.invoke(errorMessage)
                }
            })
        } catch (e: Exception) {
            Log.e("WifiDirect", "Error in createGroupAfterStopDiscovery", e)
            onError?.invoke("Failed to create WifiDirect group: ${e.message}")
        }
    }

    fun discoverPeers() {
        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WifiDirect", "Peer discovery initiated")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Searching for devices...", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(reason: Int) {
                    val errorMessage = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "WifiDirect is not supported on this device"
                        WifiP2pManager.BUSY -> "WifiDirect is busy, please try again"
                        WifiP2pManager.ERROR -> "Internal error occurred"
                        else -> "Failed to discover peers: $reason"
                    }
                    Log.e("WifiDirect", "Failed to discover peers: $errorMessage")
                    onError?.invoke(errorMessage)
                }
            })
        } catch (e: Exception) {
            Log.e("WifiDirect", "Error in discoverPeers", e)
            onError?.invoke("Failed to discover peers: ${e.message}")
        }
    }

    fun connectToDevice(device: WifiP2pDevice) {
        try {
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
            }
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WifiDirect", "Connection request sent to ${device.deviceName}")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Connecting to ${device.deviceName}...", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(reason: Int) {
                    val errorMessage = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "WifiDirect is not supported on this device"
                        WifiP2pManager.BUSY -> "WifiDirect is busy, please try again"
                        WifiP2pManager.ERROR -> "Internal error occurred"
                        else -> "Failed to connect: $reason"
                    }
                    Log.e("WifiDirect", "Failed to connect: $errorMessage")
                    onError?.invoke(errorMessage)
                }
            })
        } catch (e: Exception) {
            Log.e("WifiDirect", "Error in connectToDevice", e)
            onError?.invoke("Failed to connect to device: ${e.message}")
        }
    }

    fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        isGroupOwner = info.isGroupOwner
        if (info.groupFormed) {
            if (info.groupOwnerAddress != null) {
                groupOwnerAddress = info.groupOwnerAddress.hostAddress
                Log.d("WifiDirect", "Group formed. Group Owner IP: $groupOwnerAddress")
                
                if (isGroupOwner) {
                    onGroupCreated?.invoke(groupOwnerAddress!!)
                } else {
                    onGroupJoined?.invoke(groupOwnerAddress!!)
                }
            } else {
                Log.e("WifiDirect", "Group formed but group owner IP is null")
                onError?.invoke("Failed to get group owner IP address")
            }
        }
    }

    fun onPeersAvailable(peers: WifiP2pDeviceList) {
        Log.d("WifiDirect", "Peers available: ${peers.deviceList.size}")
        if (peers.deviceList.isEmpty()) {
            activity.runOnUiThread {
                Toast.makeText(activity, "No devices found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun getGroupOwnerAddress(): String? = groupOwnerAddress
    fun isGroupOwner(): Boolean = isGroupOwner

    private fun hasRequiredPermissions(context: Context): Boolean {
        // implement the logic to check if the required permissions are granted
        return true // Placeholder return, actual implementation needed
    }
}