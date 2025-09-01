package com.example.pkgenrich

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.pkgenrich.databinding.FragmentResultPanelBinding
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.text.DecimalFormat

class ResultPanelFragment : Fragment() {
    private var _binding: FragmentResultPanelBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResultPanelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Result Panel"
        loadResult()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadResult()
    }

    fun loadResult() {
        val context = requireContext()
        val panel = binding.resultPanelInfo
        panel.text = ""
        panel.gravity = Gravity.CENTER
        panel.setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        panel.textSize = 18f

        if (!EnrichmentStatus.isEnriched) {
            // enrichment not completed, center large text display
            panel.text = "No Enrichment Result Yet"
            binding.resultList.visibility = View.GONE
            return
        }

        // enrichment completed, display variables
        val totalTime = EnrichmentStatus.totalTime
        val totalBytesReceived = EnrichmentStatus.totalBytesReceived
        val enrichedNodeCount = EnrichmentStatus.enrichedNodeCount
        val timeFormatted = DecimalFormat("#.##").format(totalTime)
        val bytesFormatted = formatBytes(totalBytesReceived)

        // display variables
        val info = """
            <b>Enrichment Result</b><br><br>
            <font color='#1976D2'>Running Time:</font> <b>${timeFormatted}s</b><br>
            <font color='#388E3C'>Communication Cost:</font> <b>$bytesFormatted</b><br>
            <font color='#F57C00'>Enriched Nodes:</font> <b>$enrichedNodeCount</b>
        """.trimIndent()
        panel.textSize = 20f
        panel.setTextColor(ContextCompat.getColor(context, android.R.color.black))
        panel.gravity = Gravity.START
        panel.setLineSpacing(8f, 1.1f)
        panel.setPadding(32, 32, 32, 32)
        panel.text = android.text.Html.fromHtml(info, android.text.Html.FROM_HTML_MODE_LEGACY)
        binding.resultList.visibility = View.VISIBLE
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return String.format("%.2f %s", value, units[unitIndex])
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 