package com.example.pkgenrich

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.pkgenrich.databinding.FragmentDeviceNearbyBinding
import com.example.pkgenrich.utils.AssetUtils
import com.example.pkgenrich.utils.ProgressManager
import com.example.pkgenrich.utils.PythonProgressListener
import com.example.pkgenrich.utils.WifiDirectUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.ProgressBar
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ProcessBuilder
import java.io.File
import android.os.Environment

class DeviceNearbyFragment : Fragment(), PythonProgressListener {
    private var _binding: FragmentDeviceNearbyBinding? = null
    private val binding get() = _binding!!
    private lateinit var progressManager: ProgressManager
    private var isProcessing = false
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val NEARBY_WIFI_DEVICES_PERMISSION_REQUEST_CODE = 1002
    private lateinit var wifiDirectUtils: WifiDirectUtils
    private val PERMISSION_REQUEST_CODE = 1003
    private var serverSocket: ServerSocket? = null
    private var deviceListDialog: AlertDialog? = null
    private var isConnected = false
    private var isGroupOwner = false
    private var groupOwnerIp: String? = null
    private var localIp: String? = null
    private var originalDatasetPath: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceNearbyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // initialize WifiDirectUtils first
        wifiDirectUtils = WifiDirectUtils(requireContext())
        
        // initialize progress manager
        progressManager = ProgressManager(
            binding.progressContainer,
            binding.progressBar,
            binding.progressText,
            viewLifecycleOwner
        )

        // initialize Python and copy datasets
        initializePythonAndDatasets()

        // Set up WifiDirect listeners
        setupWifiDirectListeners()
        
        // check permissions
        checkPermissions()

        // setup click listeners
        view.findViewById<MaterialCardView>(R.id.createGroupCard).setOnClickListener {
            if (checkPermissions()) {
                createGroup()
            }
        }

        view.findViewById<MaterialCardView>(R.id.startEnrichmentCard).setOnClickListener {
            if (isConnected) {
                startEnrichment()
            } else {
                showMessage("Please connect to a device first")
            }
        }

        view.findViewById<FloatingActionButton>(R.id.searchDevicesFab).setOnClickListener {
            if (checkPermissions()) {
                startDeviceDiscovery()
            }
        }
    }

    private fun initializePythonAndDatasets() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val safeContext = context ?: return@launch
                // initialize Python if not already started
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(safeContext))
                    Python.getInstance()
                } else {
                    Python.getInstance()
                }

                // check for original.json in public Downloads directory
                val publicFile = File("/sdcard/Download/original.json")
                Log.d("DatasetPath", "Checking original.json at: ${publicFile.absolutePath}")
                Log.d("DatasetPath", "File exists? ${publicFile.exists()}, size=${publicFile.length()}")
                
                if (publicFile.exists()) {
                    originalDatasetPath = publicFile.absolutePath
                    Log.d("DatasetPath", "Using original.json from: $originalDatasetPath")
                } else {
                    Log.d("DatasetPath", "original.json not found in public storage")
                    // fallback to default dataset if needed
                    val defaultDataset = AssetUtils.copyAssetToInternalStorage(
                        safeContext,
                        "datasets/synthetic/bob.json",
                        "datasets/synthetic/bob.json"
                    )
                    originalDatasetPath = defaultDataset.absolutePath
                    Log.d("DatasetPath", "Using default dataset from: $originalDatasetPath")
                }

            } catch (e: Exception) {
                Log.e("DeviceNearbyFragment", "Error initializing Python or copying assets", e)
                withContext(Dispatchers.Main) {
                    showErrorOnMainThread("Initialization Error", "Failed to initialize: ${e.message}")
                }
            }
        }
    }

    private fun setupWifiDirectListeners() {
        wifiDirectUtils.setOnPeersAvailableListener { peers ->
            // show available devices in a dialog
            showDeviceListDialog(peers.deviceList)
        }

        wifiDirectUtils.setOnConnectionInfoAvailableListener { info ->
            onP2PConnectionEstablished(info)
        }

        wifiDirectUtils.setOnGroupCreatedListener { groupOwnerIp ->
            Log.d("WifiDirect", "Group created with IP: $groupOwnerIp")
            showMessage("WifiDirect group created")
            isGroupOwner = true
            this.groupOwnerIp = groupOwnerIp
            this.localIp = getLocalIpAddress()
            isConnected = true
            showEnrichmentButton()
        }

        wifiDirectUtils.setOnGroupRemovedListener {
            Log.d("WifiDirect", "Group removed")
            showMessage("WifiDirect group removed")
            resetConnectionState()
        }

        wifiDirectUtils.setOnGroupJoinedListener { groupOwnerIp ->
            Log.d("WifiDirect", "Joined group with owner IP: $groupOwnerIp")
            showMessage("Joined WifiDirect group")
            isGroupOwner = false
            this.groupOwnerIp = groupOwnerIp
            this.localIp = getLocalIpAddress()
            isConnected = true
            showEnrichmentButton()
        }

        wifiDirectUtils.setOnErrorListener { error ->
            Log.e("WifiDirect", "WifiDirect error: $error")
            showMessage("Error: $error")
            resetConnectionState()
        }
    }

    private fun showDeviceListDialog(devices: Collection<WifiP2pDevice>) {
        if (!isAdded) return // Check if fragment is still attached
        
        // Dismiss existing dialog if any
        deviceListDialog?.dismiss()
        
        view?.findViewById<ProgressBar>(R.id.progressBar)?.visibility = View.GONE
        
        val deviceNames = devices.map { it.deviceName }
        val builder = AlertDialog.Builder(requireContext())
        deviceListDialog = builder.setTitle("Available Devices")
            .setItems(deviceNames.toTypedArray()) { _, which ->
                connectToDevice(devices.elementAt(which))
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setOnDismissListener {
                deviceListDialog = null
            }
            .create()
        
        deviceListDialog?.show()
    }

    private fun connectToDevice(device: WifiP2pDevice) {
        if (!isAdded) return // Check if fragment is still attached
        
        view?.findViewById<ProgressBar>(R.id.progressBar)?.visibility = View.VISIBLE
        wifiDirectUtils.connectToDevice(device)
    }

    private fun resetConnectionState() {
        isConnected = false
        isGroupOwner = false
        groupOwnerIp = null
        localIp = null
        hideEnrichmentButton()
    }

    private fun showEnrichmentButton() {
        view?.findViewById<MaterialCardView>(R.id.startEnrichmentCard)?.visibility = View.VISIBLE
    }

    private fun hideEnrichmentButton() {
        view?.findViewById<MaterialCardView>(R.id.startEnrichmentCard)?.visibility = View.GONE
    }

    private fun startEnrichment() {
        if (!isConnected || groupOwnerIp == null || localIp == null) {
            showMessage("Connection not established")
            return
        }

        if (isGroupOwner) {
            startServerEnrichment(localIp!!)
        } else {
            startClientEnrichment(groupOwnerIp!!, localIp!!)
        }
    }

    private fun onP2PConnectionEstablished(info: WifiP2pInfo) {
        Log.d("WifiDirect", "Connection established. Group formed: ${info.groupFormed}, isGO: ${info.isGroupOwner}")
        if (info.groupFormed) {
            if (info.groupOwnerAddress != null) {
                val serverIp = info.groupOwnerAddress.hostAddress
                Log.d("WifiDirect", "Group owner IP: $serverIp")
                
                // Get local IP address
                val localIp = getLocalIpAddress()
                Log.d("WifiDirect", "Local IP: $localIp")
                
                isConnected = true
                isGroupOwner = info.isGroupOwner
                this.groupOwnerIp = serverIp
                this.localIp = localIp
                showEnrichmentButton()
            } else {
                Log.e("WifiDirect", "Group formed but server IP is null")
                showErrorOnMainThread("WifiDirect Error", "Failed to get server IP address")
                resetConnectionState()
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.contains("p2p")) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet4Address) {
                            return address.hostAddress
                        }
                    }
                }
                                }
                            } catch (e: Exception) {
            Log.e("WifiDirect", "Error getting local IP", e)
        }
        return "0.0.0.0"
    }

    private fun startIpExchangeServer(localIp: String, onClientIpReceived: (String) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket(12345)
                Log.d("WifiDirect", "IP exchange server started on port 12345")
                
                val clientSocket = serverSocket.accept()
                val input = clientSocket.getInputStream().bufferedReader()
                val clientIp = input.readLine()
                
                // Send local IP to client
                val output = clientSocket.getOutputStream().writer()
                output.write("$localIp\n")
                output.flush()
                
                clientSocket.close()
                serverSocket.close()
                
                onClientIpReceived(clientIp)
                            } catch (e: Exception) {
                Log.e("WifiDirect", "Error in IP exchange server", e)
                withContext(Dispatchers.Main) {
                    showErrorOnMainThread("Connection Error", "Failed to exchange IP addresses: ${e.message}")
                }
            }
        }
    }

    private fun exchangeIpWithServer(serverIp: String, localIp: String, onServerIpReceived: (String) -> Unit) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                val socket = Socket(serverIp, 12345)
                val output = socket.getOutputStream().writer()
                output.write("$localIp\n")
                output.flush()
                
                val input = socket.getInputStream().bufferedReader()
                val serverIp = input.readLine()
                
                socket.close()
                onServerIpReceived(serverIp)
                } catch (e: Exception) {
                Log.e("WifiDirect", "Error exchanging IP with server", e)
                withContext(Dispatchers.Main) {
                    showErrorOnMainThread("Connection Error", "Failed to exchange IP addresses: ${e.message}")
                }
            }
        }
    }

    private fun killProcessOnPort(port: Int) {
        try {
            // For macOS/Linux
            val processBuilder = ProcessBuilder("lsof", "-ti", ":$port")
            val process = processBuilder.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val pid = reader.readLine()
            
            if (pid != null) {
                Log.d("PortKiller", "Found process $pid using port $port")
                val killProcess = ProcessBuilder("kill", "-9", pid).start()
                killProcess.waitFor()
                Log.d("PortKiller", "Killed process $pid")
            } else {
                Log.d("PortKiller", "No process found using port $port")
            }
        } catch (e: Exception) {
            Log.e("PortKiller", "Error killing process on port $port", e)
        }
    }

    private fun startServerEnrichment(serverIp: String) {
        if (isProcessing) return
        if (originalDatasetPath == null) {
            showErrorOnMainThread("Error", "Dataset not initialized")
            return
        }
        
        startProgressIndicator("Waiting for client to join group...")

        // Start a socket server to receive client's IP
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Kill any existing process on port 65432 before starting server
                killProcessOnPort(65432)
                
                // Wait for client to join group
                var clientJoined = false
                var retryCount = 0
                val maxRetries = 30 // Wait up to 30 seconds
                
                while (!clientJoined && retryCount < maxRetries) {
                    val groupInfo = wifiDirectUtils.getGroupInfo()
                    Log.d("WifiDirect", "Checking group info: ${groupInfo?.clientList?.size ?: 0} clients")
                    if (groupInfo != null && groupInfo.clientList.size > 0) {
                        clientJoined = true
                        Log.d("WifiDirect", "Client joined the group")
                } else {
                        retryCount++
                        Log.d("WifiDirect", "Waiting for client to join, attempt $retryCount")
                        delay(1000) // Wait 1 second before checking again
                    }
                }

                if (!clientJoined) {
                    throw Exception("No client joined the group after $maxRetries seconds")
                }

                // Now start the socket server
                startProgressIndicator("Starting IP exchange...")
                
                // Close existing server socket if any
                serverSocket?.close()
                serverSocket = null

                // Try to create server socket with retry
                var port = 12345
                var maxPortAttempts = 5
                var portAttempt = 0
                
                while (portAttempt < maxPortAttempts) {
                    try {
                        serverSocket = ServerSocket(port)
                        Log.d("WifiDirect", "Server socket created successfully on port $port")
                        break
                        } catch (e: Exception) {
                        portAttempt++
                        port++
                        Log.e("WifiDirect", "Failed to bind to port ${port-1}, trying port $port", e)
                        if (portAttempt == maxPortAttempts) {
                            throw Exception("Failed to find available port after $maxPortAttempts attempts")
                        }
                        delay(1000) // Wait before trying next port
                    }
                }
                
                // Wait for client connection with timeout
                var clientSocket: Socket? = null
                retryCount = 0
                
                while (retryCount < maxRetries && clientSocket == null) {
                    try {
                        Log.d("WifiDirect", "Waiting for client connection on port $port, attempt ${retryCount + 1}")
                        serverSocket?.soTimeout = 5000 // 5 seconds timeout
                        clientSocket = serverSocket?.accept()
                        Log.d("WifiDirect", "Client connected successfully")
        } catch (e: Exception) {
                        retryCount++
                        Log.e("WifiDirect", "Failed to accept client connection, attempt $retryCount", e)
                        delay(1000) // Wait 1 second before retrying
                    }
                }
                
                if (clientSocket == null) {
                    throw Exception("Failed to accept client connection after $maxRetries attempts")
                }

                try {
                    Log.d("WifiDirect", "Reading client IP...")
                    val input = clientSocket.getInputStream().bufferedReader()
                    val clientIp = input.readLine()
                    Log.d("WifiDirect", "Received client IP: $clientIp")
                    
                    // Send server IP to client
                    Log.d("WifiDirect", "Sending server IP: $serverIp")
                    val output = clientSocket.getOutputStream().writer()
                    output.write("$serverIp\n")
                    output.flush()
                    Log.d("WifiDirect", "Server IP sent successfully")
                    
                    clientSocket.close()
                    serverSocket?.close()
                    serverSocket = null
                    
                    // Start enrichment process with client IP
        withContext(Dispatchers.Main) {
                        startProgressIndicator("Starting server enrichment...")
                    }

                val progressListenerCallback = object : PythonProgressListener {
                    override fun onProgressUpdate(progressPercentage: Int, statusMessage: String) {
                        activity?.runOnUiThread {
                            updateUIAfterProgress(progressPercentage, statusMessage)
                        }
                    }
                }
                val pyProgressListener = PyObject.fromJava(progressListenerCallback)

                    var finalStatusMap: Map<String, Any?>? = null
                    var errorMsg: String? = null
                    try {
                        val py = Python.getInstance()
                        val pyObj = py.getModule("mobile_entry")

                        Log.d("ChaquopyCall", "Calling Python's run_enrichment_server_wrapper with callback.")
                        val resultPyObject = pyObj.callAttr(
                            "run_enrichment_server_wrapper",
                            clientIp,  // Use client's IP for binding
                            65432,
                            originalDatasetPath,
                            pyProgressListener,
                            requireContext()
                        )

                        val pyMap = resultPyObject?.asMap()
                        finalStatusMap = pyMap?.mapKeys { it.key.toString() }?.mapValues { entry ->
                            entry.value?.toJava(Object::class.java)
                        }
                    } catch (e: PyException) {
                        errorMsg = "Python Error in Server: ${e.message}\n${e.stackTraceToString()}"
                        Log.e("ChaquopyCall", errorMsg, e)
                    } catch (e: Exception) {
                        errorMsg = "Kotlin Error in Server: ${e.message}"
                        Log.e("ChaquopyCall", errorMsg, e)
                    }

                    withContext(Dispatchers.Main) {
                        val pythonReportedError = finalStatusMap?.get("error")?.toString()
                        if (pythonReportedError != null && pythonReportedError.isNotEmpty()) {
                            stopProgressIndicator("Server Failed: Error occurred")
                            AlertDialog.Builder(requireContext())
                                .setTitle("Enrichment Error")
                                .setMessage(pythonReportedError)
                                .setPositiveButton("OK", null)
                                .show()
                        } else if (finalStatusMap != null) {
                            stopProgressIndicator("Server Enrichment Completed!")
                            val statusMap = finalStatusMap
                            EnrichmentStatus.isEnriched = true
                            EnrichmentStatus.totalTime = (statusMap?.get("total_time") as? Number)?.toFloat() ?: 0f
                            EnrichmentStatus.totalBytesReceived = (statusMap?.get("total_bytes_received") as? Number)?.toLong() ?: 0L
                            EnrichmentStatus.enrichedNodeCount = (statusMap?.get("enriched_node_count") as? Number)?.toInt() ?: 0
                        } else {
                            stopProgressIndicator("Server Failed: ${errorMsg ?: "Unknown"}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WifiDirect", "Error during IP exchange", e)
                    throw e
                }
            } catch (e: Exception) {
                Log.e("WifiDirect", "Error in server socket", e)
                withContext(Dispatchers.Main) {
                    stopProgressIndicator("Server Failed: ${e.message}")
                    showErrorOnMainThread("Connection Error", "Failed to establish connection: ${e.message}")
                }
            } finally {
                // Clean up server socket
                try {
                    serverSocket?.close()
                    serverSocket = null
                } catch (e: Exception) {
                    Log.e("WifiDirect", "Error closing server socket", e)
                }
            }
        }
    }

    private fun startClientEnrichment(serverIp: String, clientIp: String) {
        if (isProcessing) return
        if (originalDatasetPath == null) {
            showErrorOnMainThread("Error", "Dataset not initialized")
            return
        }
        
        startProgressIndicator("Connecting to server...")

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Kill any existing process on port 65432 before starting client
                killProcessOnPort(65432)
                
                // Add delay to ensure server is ready
                Log.d("WifiDirect", "Waiting for server to be ready...")
                delay(2000) // Wait 2 seconds for server to start
                
                // Try to connect with retries
                var socket: Socket? = null
                var retryCount = 0
                val maxRetries = 5
                
                while (retryCount < maxRetries && socket == null) {
                    try {
                        Log.d("WifiDirect", "Attempting to connect to server $serverIp:12345, attempt ${retryCount + 1}")
                        socket = Socket()
                        socket?.connect(InetSocketAddress(serverIp, 12345), 5000) // 5 seconds timeout
                        Log.d("WifiDirect", "Successfully connected to server")
                    } catch (e: Exception) {
                        retryCount++
                        Log.e("WifiDirect", "Failed to connect to server, attempt $retryCount", e)
                        delay(1000) // Wait 1 second before retrying
                    }
                }
                
                if (socket == null) {
                    throw Exception("Failed to connect to server after $maxRetries attempts")
                }

                try {
                    Log.d("WifiDirect", "Sending client IP: $clientIp")
                    val output = socket.getOutputStream().writer()
                    output.write("$clientIp\n")
                    output.flush()
                    Log.d("WifiDirect", "Client IP sent successfully")
                    
                    Log.d("WifiDirect", "Reading server IP...")
                    val input = socket.getInputStream().bufferedReader()
                    val serverIp = input.readLine()
                    Log.d("WifiDirect", "Received server IP: $serverIp")
                    
                    socket.close()
                    
                    withContext(Dispatchers.Main) {
                startProgressIndicator("Starting client enrichment...")
                    }

                val progressListenerCallback = object : PythonProgressListener {
                    override fun onProgressUpdate(progressPercentage: Int, statusMessage: String) {
                        activity?.runOnUiThread {
                            updateUIAfterProgress(progressPercentage, statusMessage)
                        }
                    }
                }
                val pyProgressListener = PyObject.fromJava(progressListenerCallback)

                    var finalStatusMap: Map<String, Any?>? = null
                    var errorMsg: String? = null
                    try {
                        val py = Python.getInstance()
                        val pyObj = py.getModule("mobile_entry")

                        val resultPyObject = pyObj.callAttr(
                            "run_enrichment_client_wrapper",
                            serverIp,
                            65432,
                            originalDatasetPath,
                            pyProgressListener,
                            requireContext()
                        )

                        val pyMap = resultPyObject?.asMap()
                        finalStatusMap = pyMap?.mapKeys { it.key.toString() }?.mapValues { entry ->
                            entry.value?.toJava(Object::class.java)
                        }
                    } catch (e: PyException) {
                        errorMsg = "Python Error in Client: ${e.message}\n${e.stackTraceToString()}"
                        Log.e("ChaquopyCall", errorMsg, e)
                    } catch (e: Exception) {
                        errorMsg = "Kotlin Error in Client: ${e.message}"
                        Log.e("ChaquopyCall", errorMsg, e)
                    }

                    withContext(Dispatchers.Main) {
                        if (!isAdded || _binding == null) return@withContext
                        if (finalStatusMap != null) {
                            stopProgressIndicator("Client Enrichment Completed!")
                            val statusMap = finalStatusMap
                            EnrichmentStatus.isEnriched = true
                            EnrichmentStatus.totalTime = (statusMap?.get("total_time") as? Number)?.toFloat() ?: 0f
                            EnrichmentStatus.totalBytesReceived = (statusMap?.get("total_bytes_received") as? Number)?.toLong() ?: 0L
                            EnrichmentStatus.enrichedNodeCount = (statusMap?.get("enriched_node_count") as? Number)?.toInt() ?: 0
                        } else {
                            stopProgressIndicator("Client Failed: "+ (errorMsg ?: "Unknown"))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WifiDirect", "Error during IP exchange", e)
                    throw e
                    }
                } catch (e: Exception) {
                Log.e("WifiDirect", "Error in client socket", e)
                    withContext(Dispatchers.Main) {
                    stopProgressIndicator("Client Failed: ${e.message}")
                    showErrorOnMainThread("Connection Error", "Failed to establish connection: ${e.message}")
                }
            }
        }
    }

    private fun startProgressIndicator(message: String) {
        if (!isAdded) return // Check if fragment is still attached
        
        activity?.runOnUiThread {
            try {
                isProcessing = true
                progressManager.startProgress(message)
        } catch (e: Exception) {
                Log.e("Progress", "Error starting progress", e)
            }
        }
    }

    private fun stopProgressIndicator(message: String) {
        if (!isAdded) return // Check if fragment is still attached
        
        activity?.runOnUiThread {
            try {
                isProcessing = false
                progressManager.stopProgress(message)
            } catch (e: Exception) {
                Log.e("Progress", "Error stopping progress", e)
            }
        }
    }

    private fun showMessage(message: String) {
        if (!isAdded) return // Check if fragment is still attached
        
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showErrorOnMainThread(title: String, message: String) {
        if (!isAdded) return // Check if fragment is still attached
        
        activity?.runOnUiThread {
            try {
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
        } catch (e: Exception) {
                Log.e("Error", "Error showing error dialog", e)
                showMessage(message)
            }
        }
    }

    private fun checkPermissions(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ),
                PERMISSION_REQUEST_CODE
            )
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showMessage("Permissions granted")
            } else {
                showMessage("Permissions required for Wifi Direct")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        wifiDirectUtils.registerReceiver()
    }

    override fun onPause() {
        super.onPause()
        // Dismiss any showing dialog when fragment is paused
        deviceListDialog?.dismiss()
        deviceListDialog = null
        wifiDirectUtils.unregisterReceiver()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up server socket
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            Log.e("WifiDirect", "Error closing server socket in onDestroyView", e)
        }
        // Dismiss any showing dialog
        deviceListDialog?.dismiss()
        deviceListDialog = null
        _binding = null
    }

    override fun onProgressUpdate(progressPercentage: Int, statusMessage: String) {
        activity?.runOnUiThread {
            updateUIAfterProgress(progressPercentage, statusMessage)
        }
    }

    private fun updateUIAfterProgress(percentage: Int, message: String) {
        if (!isAdded) return // Check if fragment is still attached
        
        activity?.runOnUiThread {
            try {
                progressManager.updateProgress(percentage, message)
            } catch (e: Exception) {
                Log.e("Progress", "Error updating progress", e)
            }
        }
    }

    private fun createGroup() {
        view?.findViewById<ProgressBar>(R.id.progressBar)?.visibility = View.VISIBLE
        wifiDirectUtils.checkAndCreateGroup()
    }

    private fun startDeviceDiscovery() {
        if (!isAdded) return // Check if fragment is still attached
        
        view?.findViewById<ProgressBar>(R.id.progressBar)?.visibility = View.VISIBLE
        wifiDirectUtils.discoverPeers()
    }
} 