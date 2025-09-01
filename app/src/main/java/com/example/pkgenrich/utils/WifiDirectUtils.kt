package com.example.pkgenrich.utils

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pGroup
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class WifiDirectUtils(private val context: Context) {
    private val TAG = "WifiDirectUtils"
    private val manager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private val channel: WifiP2pManager.Channel by lazy {
        manager.initialize(context, context.mainLooper, null)
    }
    private var onPeersAvailable: ((WifiP2pDeviceList) -> Unit)? = null
    private var onConnectionInfoAvailable: ((WifiP2pInfo) -> Unit)? = null
    private var onGroupCreated: (() -> Unit)? = null
    private var onGroupRemoved: (() -> Unit)? = null
    private var receiver: BroadcastReceiver? = null
    private var onGroupCreatedListener: ((String) -> Unit)? = null
    private var onGroupJoinedListener: ((String) -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var onPeerListListener: ((List<WifiP2pDevice>) -> Unit)? = null

    companion object {
        const val GROUP_OWNER_IP = "192.168.49.1"
        const val GROUP_CREATION_DELAY = 3000L
        const val GROUP_REMOVAL_DELAY = 2000L
        const val PERMISSION_REQUEST_CODE = 1001
    }

    init {
        setupBroadcastReceiver()
    }

    private fun setupBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            Log.d(TAG, "WiFi P2P is enabled")
                        } else {
                            Log.d(TAG, "WiFi P2P is disabled")
                            Toast.makeText(context, "Please enable WiFi Direct", Toast.LENGTH_LONG).show()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeers()
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            requestConnectionInfo()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        // Device details changed
                    }
                }
            }
        }

        context.registerReceiver(receiver, intentFilter)
    }

    fun registerReceiver() {
        if (receiver != null) {
            setupBroadcastReceiver()
        }
    }

    fun unregisterReceiver() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    fun setOnPeersAvailableListener(listener: (WifiP2pDeviceList) -> Unit) {
        onPeersAvailable = listener
    }

    fun setOnConnectionInfoAvailableListener(listener: (WifiP2pInfo) -> Unit) {
        onConnectionInfoAvailable = listener
    }

    fun setOnGroupCreatedListener(listener: () -> Unit) {
        onGroupCreated = listener
    }

    fun setOnGroupRemovedListener(listener: () -> Unit) {
        onGroupRemoved = listener
    }

    fun setOnGroupCreatedListener(listener: (String) -> Unit) {
        onGroupCreatedListener = listener
    }

    fun setOnGroupJoinedListener(listener: (String) -> Unit) {
        onGroupJoinedListener = listener
    }

    fun setOnErrorListener(listener: (String) -> Unit) {
        onErrorListener = listener
    }

    fun setOnPeerListListener(listener: (List<WifiP2pDevice>) -> Unit) {
        onPeerListListener = listener
    }

    fun initialize() {
        // Initialize WifiP2pManager and Channel if not already initialized
        if (channel == null) {
            manager.initialize(context, context.mainLooper, null)
        }
        setupBroadcastReceiver()
    }

    private fun hasRequiredPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
        ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        if (!hasRequiredPermissions()) {
            if (context is android.app.Activity) {
                ActivityCompat.requestPermissions(
                    context as android.app.Activity,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                    ),
                    PERMISSION_REQUEST_CODE
                )
            } else {
                onErrorListener?.invoke("Required permissions not granted")
            }
        }
    }

    fun discoverPeers() {
        if (!hasRequiredPermissions()) {
            checkAndRequestPermissions()
            return
        }

        channel?.let { ch ->
            manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Peer discovery initiated")
                    Toast.makeText(context, "Searching for devices...", Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Peer discovery failed: $reason")
                    Toast.makeText(context, "Discovery failed: $reason", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    fun stopPeerDiscovery() {
        channel?.let { ch ->
            manager.stopPeerDiscovery(ch, null)
        }
    }

    fun createGroup() {
        channel?.let { ch ->
            manager.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group creation initiated")
                    Toast.makeText(context, "Creating WiFi Direct group...", Toast.LENGTH_SHORT).show()
                    onGroupCreated?.invoke()
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to create group: $reason")
                    Toast.makeText(context, "Failed to create group: $reason", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    fun removeGroup() {
        channel?.let { ch ->
            manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group removal initiated")
                    Toast.makeText(context, "Removing WiFi Direct group...", Toast.LENGTH_SHORT).show()
                    onGroupRemoved?.invoke()
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to remove group: $reason")
                    Toast.makeText(context, "Failed to remove group: $reason", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    fun connectToDevice(device: WifiP2pDevice) {
        if (!hasRequiredPermissions()) {
            checkAndRequestPermissions()
            return
        }

        channel?.let { ch ->
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
            }
            manager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connection request sent to ${device.deviceName}")
                    Toast.makeText(context, "Connecting to ${device.deviceName}...", Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to connect: $reason")
                    Toast.makeText(context, "Failed to connect: $reason", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    fun connectToDeviceById(deviceId: String) {
        channel.let { ch ->
            manager.requestPeers(ch) { peerList ->
                val device = peerList.deviceList.find { it.deviceAddress == deviceId }
                device?.let { connectToDevice(it) }
            }
        }
    }

    fun requestPeers() {
        channel.let { ch ->
            manager.requestPeers(ch) { peerList: WifiP2pDeviceList ->
                onPeersAvailable?.invoke(peerList)
            }
        }
    }

    fun requestConnectionInfo() {
        channel.let { ch ->
            manager.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                onConnectionInfoAvailable?.invoke(info)
            }
        }
    }

    fun requestGroupInfo() {
        channel?.let { ch ->
            manager.requestGroupInfo(ch) { group ->
                if (group != null) {
                    Log.d(TAG, "Group exists with ${group.clientList.size} clients")
                } else {
                    Log.d(TAG, "No group exists")
                }
            }
        }
    }

    fun checkAndRemoveExistingGroup() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                channel?.let { ch ->
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.NEARBY_WIFI_DEVICES
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        withContext(Dispatchers.Main) {
                            checkAndRequestPermissions()
                        }
                        return@let
                    }
                    manager.requestGroupInfo(ch) { group ->
                        if (group != null) {
                            Log.d(TAG, "Removing existing group")
                            removeGroup { success ->
                                if (success) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        delay(GROUP_REMOVAL_DELAY)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in checkAndRemoveExistingGroup", e)
            }
        }
    }

    fun checkAndCreateGroup() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                channel?.let { ch ->
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.NEARBY_WIFI_DEVICES
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        withContext(Dispatchers.Main) {
                            checkAndRequestPermissions()
                        }
                        return@let
                    }
                    manager.requestGroupInfo(ch) { group ->
                        if (group == null) {
                            Log.d(TAG, "Creating new group")
                            createGroup { success ->
                                if (success) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        delay(GROUP_CREATION_DELAY)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in checkAndCreateGroup", e)
            }
        }
    }

    fun createGroup(callback: (Boolean) -> Unit) {
        try {
            // Check if there's an existing group
            manager.requestGroupInfo(channel) { group ->
                if (group != null) {
                    manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG, "Existing group removed")
                            // Launch a new coroutine to handle the delay
                            CoroutineScope(Dispatchers.IO).launch {
                                delay(GROUP_REMOVAL_DELAY)
                                createNewGroup(callback)
                            }
                        }

                        override fun onFailure(reasonCode: Int) {
                            Log.e(TAG, "Failed to remove existing group: $reasonCode")
                            callback(false)
                        }
                    })
                } else {
                    // Launch a new coroutine to call the suspend function
                    CoroutineScope(Dispatchers.IO).launch {
                        createNewGroup(callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating group", e)
            callback(false)
        }
    }

    private fun createNewGroup(callback: (Boolean) -> Unit) {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Group created")
                callback(true)
            }

            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Failed to create group: $reasonCode")
                callback(false)
            }
        })
    }

    fun connectToDeviceByMac(macAddress: String, callback: (Boolean) -> Unit) {
        val config = WifiP2pConfig().apply {
            deviceAddress = macAddress
        }

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection initiated with device: $macAddress")
                callback(true)
            }

            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Failed to connect to device: $macAddress, reason: $reasonCode")
                callback(false)
            }
        })
    }

    fun removeGroup(callback: (Boolean) -> Unit) {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Group removed")
                callback(true)
            }

            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Failed to remove group: $reasonCode")
                callback(false)
            }
        })
    }

    fun cleanup() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
            receiver = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up", e)
        }
    }

    fun getGroupInfo(): WifiP2pGroup? {
        var groupInfo: WifiP2pGroup? = null
        val lock = Object()
        
        manager.requestGroupInfo(channel, object : WifiP2pManager.GroupInfoListener {
            override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
                synchronized(lock) {
                    groupInfo = group
                    lock.notify()
                }
            }
        })
        
        try {
            synchronized(lock) {
                lock.wait(5000) // Wait up to 5 seconds for the group info
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for group info", e)
        }
        
        return groupInfo
    }
}
