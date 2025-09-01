package com.example.pkgenrich.utils

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Interface for receiving progress updates from Python
 */
interface PythonProgressListener {
    fun onProgressUpdate(progressPercentage: Int, statusMessage: String)
}

/**
 * Class to manage progress bar UI and state
 */
class ProgressManager(
    private val progressContainer: View,
    private val progressBar: ProgressBar,
    private val progressText: TextView,
    private val lifecycleOwner: LifecycleOwner
) {
    private var isProcessing = false

    /**
     * Start showing progress with initial message
     */
    fun startProgress(initialMessage: String = "Processing...") {
        progressContainer.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressText.text = initialMessage
        isProcessing = true
    }

    /**
     * Update progress with percentage and message
     */
    fun updateProgress(progressPercentage: Int, statusMessage: String) {
        if (!isProcessing) return
        
        progressBar.progress = progressPercentage
        progressText.text = "$statusMessage ($progressPercentage%)"
    }

    /**
     * Stop progress with final message
     */
    fun stopProgress(finalMessage: String = "Completed") {
        progressText.text = finalMessage
        progressBar.progress = 100
        
        // Delay hiding the progress container
        lifecycleOwner.lifecycleScope.launch {
            delay(1000) // 1 second delay
            progressContainer.visibility = View.GONE
        }
        isProcessing = false
    }

    /**
     * Stop progress immediately without animation
     */
    fun stopProgressImmediately() {
        progressContainer.visibility = View.GONE
        isProcessing = false
    }

    /**
     * Check if currently processing
     */
    fun isCurrentlyProcessing(): Boolean = isProcessing
} 