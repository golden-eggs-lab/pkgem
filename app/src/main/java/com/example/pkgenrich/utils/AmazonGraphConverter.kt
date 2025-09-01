package com.example.pkgenrich.utils

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Amazon Purchase Data to Graph Converter
 * 
 * This class converts Amazon purchase data from JSON format
 * into a mobile app-friendly graph format.
 * 
 * Output format:
 * - nodes: array of nodes with id and labels
 * - edges: array of edges with source, target, and labels
 */
class AmazonGraphConverter {
    private val nodes = mutableListOf<JSONObject>()
    private val edges = mutableListOf<JSONObject>()
    private var nodeIdCounter = 1
    private var edgeIdCounter = 1
    private val nodeMap = mutableMapOf<String, String>() // Map node content to node ID
    
    companion object {
        private const val TAG = "AmazonGraphConverter"
    }
    
    /**
     * Get existing node ID or create new node
     */
    private fun getOrCreateNode(content: String, labels: List<String>): String {
        // Clean content for better matching
        val cleanContent = content.trim()
        if (cleanContent.isEmpty()) {
            return getOrCreateNode("Unknown", labels)
        }
        
        // Create a unique key for the node
        val nodeKey = "${cleanContent}_${labels.joinToString("_")}"
        
        nodeMap[nodeKey]?.let { return it }
        
        // Create new node
        val nodeId = "n$nodeIdCounter"
        nodeIdCounter++
        
        // Create node with content as the first label
        val node = JSONObject().apply {
            put("id", nodeId)
            put("labels", JSONArray().apply {
                put(cleanContent)
            })
        }
        
        nodes.add(node)
        nodeMap[nodeKey] = nodeId
        return nodeId
    }
    
    /**
     * Add an edge between two nodes
     */
    private fun addEdge(sourceId: String, targetId: String, labels: List<String>) {
        val edge = JSONObject().apply {
            put("id", "e$edgeIdCounter")
            put("source", sourceId)
            put("target", targetId)
            put("labels", JSONArray().apply {
                labels.forEach { put(it) }
            })
        }
        
        edges.add(edge)
        edgeIdCounter++
    }
    
    /**
     * Extract product category from product name using keyword matching
     */
    private fun extractProductCategory(productName: String): String {
        val productNameLower = productName.lowercase()
        
        // Define category keywords
        val categories = mapOf(
            "Electronics" to listOf("phone", "cable", "usb", "airpods", "macbook", "ipad", "iphone", "laptop", "computer", "docking"),
            "Kitchen" to listOf("knife", "blender", "kettle", "wok", "pan", "cookware", "kitchen", "tea kettle"),
            "Food" to listOf("food", "meat", "rice", "noodle", "juice", "tea", "water", "yogurt", "tofu", "cheesecake", "bagels"),
            "Home" to listOf("candle", "paper", "desk", "chair", "comforter", "bed", "window cleaner"),
            "Health" to listOf("vitamin", "supplement", "medicine", "eye drops", "bandana", "mask"),
            "Outdoor" to listOf("backpack", "boots", "mask", "gaitor", "outdoor", "neck gaiter"),
            "Office" to listOf("office", "desk", "chair", "paper", "printer", "study"),
            "Gift" to listOf("gift card", "gift"),
            "Clothing" to listOf("socks", "shirt", "pants", "shoes", "boots"),
            "Beauty" to listOf("candle", "fragrance", "personal care")
        )
        
        for ((category, keywords) in categories) {
            if (keywords.any { keyword -> productNameLower.contains(keyword) }) {
                return category
            }
        }
        
        return "Other"
    }
    
    /**
     * Process a single purchase record and add to graph
     */
    private fun processPurchaseRecord(record: JSONObject) {
        // Create person node (if not exists)
        val personId = getOrCreateNode("User", listOf("Person"))
        
        // Create purchase episode node with detailed information
        val purchaseId = record.optString("purchase_id", "purchase_${record.optString("startTime", "unknown")}")
        
        // Create purchase node with more detailed attributes
        val purchaseLabels = mutableListOf("Purchase")
        record.optString("type")?.let { type ->
            if (type.isNotEmpty()) {
                purchaseLabels.add(type)
            }
        }
        
        val episodeId = getOrCreateNode(purchaseId, purchaseLabels)
        
        // Connect person to purchase
        addEdge(personId, episodeId, listOf("made_purchase"))
        
        // Add source (Amazon)
        record.optString("source")?.let { source ->
            if (source.isNotEmpty()) {
                val sourceId = getOrCreateNode(source, listOf("Source"))
                addEdge(episodeId, sourceId, listOf("from_source"))
            }
        }
        
        // Add product information with more details
        record.optString("productName")?.let { productName ->
            if (productName.isNotEmpty()) {
                // Create product node with full name
                val productId = getOrCreateNode(productName, listOf("Product"))
                addEdge(episodeId, productId, listOf("involves_product"))
                
                // Add product category
                val category = extractProductCategory(productName)
                val categoryId = getOrCreateNode(category, listOf("Category"))
                addEdge(productId, categoryId, listOf("belongs_to"))
                
                // Add product price if available
                record.optString("productPrice")?.let { price ->
                    if (price.isNotEmpty()) {
                        val priceId = getOrCreateNode("$$price", listOf("Price"))
                        addEdge(productId, priceId, listOf("has_price"))
                    }
                }
                
                // Add product ID if available
                record.optString("productId")?.let { productIdStr ->
                    if (productIdStr.isNotEmpty()) {
                        val productCodeId = getOrCreateNode(productIdStr, listOf("ProductCode"))
                        addEdge(productId, productCodeId, listOf("has_code"))
                    }
                }
                
                // Add quantity information
                record.optString("productQuantity")?.let { quantity ->
                    if (quantity.isNotEmpty() && quantity != "0") {
                        val quantityId = getOrCreateNode("Qty: $quantity", listOf("Quantity"))
                        addEdge(episodeId, quantityId, listOf("with_quantity"))
                    }
                }
            }
        }
        
        // Add time information with more granularity
        record.optString("startTime")?.let { startTime ->
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                dateFormat.timeZone = TimeZone.getTimeZone("UTC")
                val date = dateFormat.parse(startTime)
                
                date?.let { dt ->
                    val dateFormat2 = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)
                    val yearFormat = SimpleDateFormat("yyyy", Locale.US)
                    val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
                    
                    val dateStr = dateFormat2.format(dt)
                    val monthStr = monthFormat.format(dt)
                    val yearStr = yearFormat.format(dt)
                    val timeStr = timeFormat.format(dt)
                    
                    // Add specific time
                    val timeId = getOrCreateNode(timeStr, listOf("TimeOfDay"))
                    addEdge(episodeId, timeId, listOf("at_time"))
                    
                    // Add date node
                    val dateId = getOrCreateNode(dateStr, listOf("Date"))
                    addEdge(episodeId, dateId, listOf("on_date"))
                    
                    // Add month node
                    val monthId = getOrCreateNode(monthStr, listOf("Month"))
                    addEdge(dateId, monthId, listOf("in_month"))
                    
                    // Add year node
                    val yearId = getOrCreateNode(yearStr, listOf("Year"))
                    addEdge(monthId, yearId, listOf("in_year"))
                }
            } catch (e: Exception) {
                // If date parsing fails, just add the raw string
                val timeId = getOrCreateNode(startTime, listOf("Time"))
                addEdge(episodeId, timeId, listOf("at_time"))
            }
        }
        
        // Add additional purchase details
        record.optString("purchase_id")?.let { purchaseIdStr ->
            if (purchaseIdStr.isNotEmpty()) {
                val purchaseCodeId = getOrCreateNode(purchaseIdStr, listOf("PurchaseCode"))
                addEdge(episodeId, purchaseCodeId, listOf("has_purchase_id"))
            }
        }
        
        // Add currency information if available
        record.optString("currency")?.let { currency ->
            if (currency.isNotEmpty()) {
                val currencyId = getOrCreateNode(currency, listOf("Currency"))
                addEdge(episodeId, currencyId, listOf("in_currency"))
            }
        }
        
        // Add outdoor indicator if available
        if (record.has("outdoor")) {
            val outdoor = record.optInt("outdoor")
            val outdoorStatus = if (outdoor == 1) "Outdoor" else "Indoor"
            val outdoorId = getOrCreateNode(outdoorStatus, listOf("Environment"))
            addEdge(episodeId, outdoorId, listOf("in_environment"))
        }
    }
    
    /**
     * Convert Amazon data to graph format
     */
    fun convertData(inputJson: String): JSONObject? {
        return try {
            Log.d(TAG, "Starting conversion of Amazon data...")
            
            // Reset state
            nodes.clear()
            edges.clear()
            nodeIdCounter = 1
            edgeIdCounter = 1
            nodeMap.clear()
            
            // Parse input JSON
            val data = JSONArray(inputJson)
            Log.d(TAG, "Loaded ${data.length()} purchase records")
            
            // Process each record
            for (i in 0 until data.length()) {
                if (i % 100 == 0) {
                    Log.d(TAG, "Processing record ${i + 1}/${data.length()}...")
                }
                val record = data.getJSONObject(i)
                processPurchaseRecord(record)
            }
            
            // Create output structure
            val outputData = JSONObject().apply {
                put("nodes", JSONArray().apply {
                    nodes.forEach { put(it) }
                })
                put("edges", JSONArray().apply {
                    edges.forEach { put(it) }
                })
            }
            
            Log.d(TAG, "Conversion completed! Created ${nodes.size} nodes and ${edges.size} edges")
            
            // Print statistics
            printStatistics()
            
            outputData
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert data: ${e.message}")
            null
        }
    }
    
    /**
     * Print graph statistics
     */
    private fun printStatistics() {
        // Count nodes by type
        val nodeTypes = mutableMapOf<String, Int>()
        for (node in nodes) {
            val labels = node.getJSONArray("labels")
            for (i in 0 until labels.length()) {
                val label = labels.getString(i)
                nodeTypes[label] = (nodeTypes[label] ?: 0) + 1
            }
        }
        
        Log.d(TAG, "Node Statistics:")
        nodeTypes.entries.sortedBy { it.key }.forEach { (nodeType, count) ->
            Log.d(TAG, "  $nodeType: $count")
        }
        
        // Count edges by type
        val edgeTypes = mutableMapOf<String, Int>()
        for (edge in edges) {
            val labels = edge.getJSONArray("labels")
            for (i in 0 until labels.length()) {
                val label = labels.getString(i)
                edgeTypes[label] = (edgeTypes[label] ?: 0) + 1
            }
        }
        
        Log.d(TAG, "Edge Statistics:")
        edgeTypes.entries.sortedBy { it.key }.forEach { (edgeType, count) ->
            Log.d(TAG, "  $edgeType: $count")
        }
    }
    
    /**
     * Check if the input JSON is in Amazon purchase format
     */
    fun isAmazonPurchaseFormat(inputJson: String): Boolean {
        return try {
            val data = JSONArray(inputJson)
            if (data.length() == 0) return false
            
            // Check if first record has Amazon purchase fields
            val firstRecord = data.getJSONObject(0)
            val hasProductName = firstRecord.has("productName")
            val hasStartTime = firstRecord.has("startTime")
            val hasPurchaseId = firstRecord.has("purchase_id")
            
            hasProductName && hasStartTime && hasPurchaseId
        } catch (e: Exception) {
            false
        }
    }
}
