package com.example.pkgenrich

import android.os.Bundle
import android.widget.TextView
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.widget.Switch
import android.widget.SeekBar

class SettingsActivity : AppCompatActivity() {
    private val TAG = "SettingsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.top_bar_title).setOnClickListener {
            finish() // Close the settings page and return to main
        }

        findViewById<ImageButton>(R.id.icon_help).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("FAQ")
                .setMessage("Here are some frequently asked questions.")
                .setPositiveButton("Close", null)
                .show()
        }

        findViewById<ImageButton>(R.id.icon_info).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Info")
                .setMessage("This page contains settings for the app.")
                .setPositiveButton("Close", null)
                .show()
        }

        val seekBarEnrichmentPolicy = findViewById<SeekBar>(R.id.seekbar_enrichment_policy)
        val switchSecurityMode = findViewById<Switch>(R.id.switch_security_mode)
        val switchDeviceFindable = findViewById<Switch>(R.id.switch_device_findable)

        // Load saved settings
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val initialEnrichmentPolicy = prefs.getInt("enrichment_policy", 1) // Default to Balanced
        val initialSecurityMode = prefs.getBoolean("security_mode", false)
        val initialDeviceFindable = prefs.getBoolean("device_findable", true)

        seekBarEnrichmentPolicy.progress = initialEnrichmentPolicy
        switchSecurityMode.isChecked = initialSecurityMode
        switchDeviceFindable.isChecked = initialDeviceFindable

        // Save settings
        seekBarEnrichmentPolicy.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt("enrichment_policy", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        switchSecurityMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("security_mode", isChecked).apply()
        }
        switchDeviceFindable.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("device_findable", isChecked).apply()
        }
    }

    override fun onPause() {
        super.onPause()
        // take the settings when closing the settings page
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val finalSecurityMode = prefs.getBoolean("security_mode", false)
        val finalDeviceFindable = prefs.getBoolean("device_findable", true)
        // Log.d(TAG, "Settings when closing - SecurityMode: $finalSecurityMode, DeviceFindable: $finalDeviceFindable")
    }
}
