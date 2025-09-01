package com.example.pkgenrich

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.pkgenrich.databinding.ActivityMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.widget.Toast
import android.view.View
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.pkgenrich.DeviceNearbyFragment
import com.example.pkgenrich.DeviceNearbyDisabledFragment
import com.example.pkgenrich.utils.WifiDirectBroadcastReceiver
import com.example.pkgenrich.utils.AmazonGraphConverter
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.widget.TextView
import android.os.Environment
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import android.net.Uri
import android.provider.Settings

object EnrichmentStatus {
    var isEnriched: Boolean = false
        get() {
            return field
        }
        set(value) {
            field = value
        }

    var totalTime: Float = 0f
        get() = field
        set(value) {
            field = value
        }

    var totalBytesReceived: Long = 0L
        get() = field
        set(value) {
            field = value
        }

    var enrichedNodeCount: Int = 0
        get() = field
        set(value) {
            field = value
        }
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var webView: WebView? = null
    private val TAG = "MainActivity"
    private val STORAGE_PERMISSION_CODE = 1001
    private val FILE_PICKER_REQUEST_CODE = 1002
    private val amazonConverter = AmazonGraphConverter()
    private var currentGraphFile: String? = null // Track current graph file

    // Add JavaScript interface class
    inner class WebAppInterface {
        @android.webkit.JavascriptInterface
        fun onGraphReady() {
            Log.d(TAG, "onGraphReady called")
            runOnUiThread {
                // priority: 1. current graph file, 2. enriched graph, 3. default external file
                val fileToLoad = when {
                    currentGraphFile != null -> currentGraphFile!!
                    EnrichmentStatus.isEnriched -> "graph.json"
                    else -> getExternalFilePath()
                }
                Log.d(TAG, "file to loaded: $fileToLoad")
                loadGraphData(fileToLoad)
            }
        }

        @android.webkit.JavascriptInterface
        fun onNodeAdded(nodeId: String, nodeName: String, sourceNodeId: String, relationType: String) {
            Log.d(TAG, "New node added: $nodeId ($nodeId) connected to $sourceNodeId with relation: $relationType")
            runOnUiThread {
                Toast.makeText(this@MainActivity, 
                    "Added: $nodeName → $relationType", 
                    Toast.LENGTH_SHORT).show()
            }
        }

        @android.webkit.JavascriptInterface
        fun uploadGraphFile() {
            Log.d(TAG, "Upload graph file requested from JavaScript")
            runOnUiThread {
                openFilePicker()
            }
        }
    }

    private fun getExternalFilePath(): String {
        // use public Downloads directory
        return "/sdcard/Download/original.json"
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select JSON file"), FILE_PICKER_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processSelectedFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val jsonString = inputStream?.bufferedReader()?.use { it.readText() }
            
            if (jsonString == null) {
                Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show()
                return
            }

            // check if it's Amazon purchase format
            if (amazonConverter.isAmazonPurchaseFormat(jsonString)) {
                // convert Amazon data to graph format
                val convertedData = amazonConverter.convertData(jsonString)
                if (convertedData != null) {
                    // save converted data to internal storage
                    val outputFile = File(filesDir, "uploaded_graph.json")
                    outputFile.writeText(convertedData.toString())
                    
                    // Load the converted graph
                    loadGraphData("uploaded_graph.json")
                    Toast.makeText(this, "Amazon data converted and loaded successfully!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Failed to convert Amazon data", Toast.LENGTH_SHORT).show()
                }
            } else {
                // try to load as regular graph format
                try {
                    val jsonObject = org.json.JSONObject(jsonString)
                    if (jsonObject.has("nodes") && jsonObject.has("edges")) {
                        // save the graph data
                        val outputFile = File(filesDir, "uploaded_graph.json")
                        outputFile.writeText(jsonString)
                        
                        // Load the graph
                        loadGraphData("uploaded_graph.json")
                        Toast.makeText(this, "Graph file loaded successfully!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Upload file format incorrect", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Upload file format incorrect", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing selected file: ${e.message}")
            Toast.makeText(this, "Error processing file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkStoragePermission(): Boolean {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val hasRequestedPermission = prefs.getBoolean("has_requested_storage_permission", false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ needs MANAGE_EXTERNAL_STORAGE permission
            if (!Environment.isExternalStorageManager()) {
                if (!hasRequestedPermission) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.addCategory("android.intent.category.DEFAULT")
                        intent.data = Uri.parse("package:${applicationContext.packageName}")
                        startActivityForResult(intent, STORAGE_PERMISSION_CODE)
                        // Save that we've requested permission
                        prefs.edit().putBoolean("has_requested_storage_permission", true).apply()
                    } catch (e: Exception) {
                        val intent = Intent()
                        intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                        startActivityForResult(intent, STORAGE_PERMISSION_CODE)
                        // Save that we've requested permission
                        prefs.edit().putBoolean("has_requested_storage_permission", true).apply()
                    }
                }
                return false
            }
        } else {
            // For Android 10 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                if (!hasRequestedPermission) {
                    ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        STORAGE_PERMISSION_CODE)
                    // Save that we've requested permission
                    prefs.edit().putBoolean("has_requested_storage_permission", true).apply()
                }
                return false
            }
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            STORAGE_PERMISSION_CODE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        // Permission granted, reload data
                        val fileToLoad = when {
                            currentGraphFile != null -> currentGraphFile!!
                            EnrichmentStatus.isEnriched -> "graph.json"
                            else -> getExternalFilePath()
                        }
                        loadGraphData(fileToLoad)
                    } else {
                        Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            FILE_PICKER_REQUEST_CODE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val uri = data.data
                    if (uri != null) {
                        processSelectedFile(uri)
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // permission granted, reload data
                val fileToLoad = when {
                    currentGraphFile != null -> currentGraphFile!!
                    EnrichmentStatus.isEnriched -> "graph.json"
                    else -> getExternalFilePath()
                }
                loadGraphData(fileToLoad)
            } else {
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // TopBar button and title event binding
        val topBar = findViewById<View>(R.id.main_top_bar_layout)
        val title = topBar.findViewById<TextView>(R.id.top_bar_title)
        val help = topBar.findViewById<ImageButton>(R.id.icon_help)
        val settings = topBar.findViewById<ImageButton>(R.id.icon_settings)
        val info = topBar.findViewById<ImageButton>(R.id.icon_info)

        title.setOnClickListener {
            showMyKGPage()
        }
        help.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Help")
                .setMessage("This is the help dialog.")
                .setPositiveButton("Close", null)
                .show()
        }
        settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        info.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Info")
                .setMessage("This is the info dialog.")
                .setPositiveButton("Close", null)
                .show()
        }

        // Make the overlay cover the entire screen including status bar and navigation bar
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

        // Initialize WebView container
        binding.webViewContainer.visibility = View.GONE
        binding.fragmentContainer.visibility = View.GONE

        // Setup bottom navigation
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_my_kg -> {
                    showMyKGPage()
                    true
                }
                R.id.nav_device_nearby -> {
                    showDeviceNearbyPage()
                    true
                }
                R.id.nav_result_panel -> {
                    showResultPanelPage()
                    true
                }
                else -> false
            }
        }

        // Set default selection to MyKG
        bottomNav.selectedItemId = R.id.nav_my_kg
    }

    fun loadGraphData(filePath: String) {
        // Update current graph file
        currentGraphFile = filePath
        
        if (filePath.startsWith("/") && !checkStoragePermission()) {
            Log.d(TAG, "Waiting for storage permission...")
            return
        }

        val jsonString = try {
            if (filePath.startsWith("/")) {
                // external storage
                val file = File(filePath)
                Log.d(TAG, "Loading file from: $filePath")
                Log.d(TAG, "File exists? ${file.exists()}, size=${file.length()}")
                if (file.exists()) {
                    file.readText()
                } else {
                    Log.d(TAG, "File not found, using default data")
                    assets.open("datasets/synthetic/bob.json").bufferedReader().use { it.readText() }
                }
            } else {
                // internal storage
                val file = File(filesDir, filePath)
                if (file.exists()) {
                    file.readText()
                } else {
                    assets.open(filePath).bufferedReader().use { it.readText() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load graph data: ${e.message}")
            return
        }

        val safeJson = org.json.JSONObject(jsonString).toString()
        webView?.evaluateJavascript(
            "initGraph($safeJson)",
            null
        )
        Log.d(TAG, "Graph data injection completed")
    }

    private fun setupWebView() {
        if (webView != null) {
            return
        }

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            // Add JavaScript interface
            addJavascriptInterface(WebAppInterface(), "Android")

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d(TAG, "WebView page started loading: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "WebView page finished loading: $url")
                    
                    // Priority: 1. Current graph file, 2. Enriched graph, 3. Default external file
                    val fileToLoad = when {
                        currentGraphFile != null -> currentGraphFile!!
                        EnrichmentStatus.isEnriched -> "graph.json"
                        else -> getExternalFilePath()
                    }
                    
                    loadGraphData(fileToLoad)
                }

                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    Log.e(TAG, "WebView error: ${error?.description}")
                    Toast.makeText(this@MainActivity, "Failed to load graph: ${error?.description}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Add WebView to container
        binding.webViewContainer.removeAllViews()
        binding.webViewContainer.addView(webView)
    }

    private fun destroyWebView() {
        webView?.let { view ->
            view.stopLoading()
            view.webViewClient = object : WebViewClient() {}
            view.webChromeClient = null
            view.destroy()
            binding.webViewContainer.removeAllViews()
            webView = null
            System.gc()
        }
    }

    fun showMyKGPage() {
        binding.webViewContainer.visibility = View.VISIBLE
        binding.fragmentContainer.visibility = View.GONE
        setupWebView()
        webView?.loadUrl("file:///android_asset/graph.html")
    }

    fun showDeviceNearbyPage() {
        destroyWebView()
        binding.webViewContainer.visibility = View.GONE
        binding.fragmentContainer.visibility = View.VISIBLE

        // check device_findable setting
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val isDeviceFindable = prefs.getBoolean("device_findable", true)
        val fragment = if (isDeviceFindable) DeviceNearbyFragment() else DeviceNearbyDisabledFragment()

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    fun showResultPanelPage() {
        destroyWebView()
        binding.webViewContainer.visibility = View.GONE
        binding.fragmentContainer.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, ResultPanelFragment())
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyWebView()
    }

    override fun onResume() {
        super.onResume()
        // check current page and update status
        when (findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId) {
            R.id.nav_my_kg -> showMyKGPage()
            R.id.nav_device_nearby -> showDeviceNearbyPage()
            R.id.nav_result_panel -> showResultPanelPage()
        }
    }

    override fun onPause() {
        super.onPause()
    }

} 
