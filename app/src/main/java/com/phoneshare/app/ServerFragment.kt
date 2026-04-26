package com.phoneshare.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.net.NetworkInterface

class ServerFragment : Fragment() {

    private lateinit var statusCard: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var tvUrl: TextView
    private lateinit var statusSub: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnCopy: Button
    private lateinit var btnClearLog: Button
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == WebServerService.ACTION_STATUS) refreshStatus()
        }
    }
    private val logListener: (ServerLog.Entry) -> Unit = { e -> appendLogLine(e) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_server, container, false)
        statusCard = v.findViewById(R.id.statusCard)
        statusDot  = v.findViewById(R.id.statusDot)
        statusText = v.findViewById(R.id.statusText)
        tvUrl      = v.findViewById(R.id.tvUrl)
        statusSub  = v.findViewById(R.id.statusSub)
        btnStart   = v.findViewById(R.id.btnStart)
        btnStop    = v.findViewById(R.id.btnStop)
        btnCopy    = v.findViewById(R.id.btnCopy)
        btnClearLog= v.findViewById(R.id.btnClearLog)
        tvLog      = v.findViewById(R.id.tvLog)
        logScroll  = v.findViewById(R.id.logScroll)

        btnStart.setOnClickListener { startServer() }
        btnStop.setOnClickListener  { stopServer() }
        btnCopy.setOnClickListener  { copyUrl() }
        btnClearLog.setOnClickListener {
            ServerLog.clear()
            tvLog.text = ""
        }
        rebuildLogFromSnapshot()
        return v
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            requireContext(), statusReceiver,
            IntentFilter(WebServerService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ServerLog.addListener(logListener)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        ServerLog.removeListener(logListener)
    }

    private fun startServer() {
        val svc = Intent(requireContext(), WebServerService::class.java).setAction(WebServerService.ACTION_START)
        ContextCompat.startForegroundService(requireContext(), svc)
        refreshStatus()
    }

    private fun stopServer() {
        val svc = Intent(requireContext(), WebServerService::class.java).setAction(WebServerService.ACTION_STOP)
        requireContext().startService(svc)
        refreshStatus()
    }

    private fun primaryHostPort(): String {
        val ip = getLocalIpv4() ?: "0.0.0.0"
        return "$ip:${WebServerService.port}"
    }

    private fun copyUrl() {
        val url = "http://" + primaryHostPort()
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PhoneShare URL", url))
        Toast.makeText(requireContext(), "Copied: $url", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        val running = WebServerService.isRunning
        if (running) {
            statusCard.setBackgroundResource(R.drawable.bg_status_running)
            statusDot.setBackgroundResource(R.drawable.status_dot_green)
            statusText.text = "RUNNING"
            tvUrl.text = "[HTTP] ${primaryHostPort()}"
            tvUrl.visibility = View.VISIBLE
            val storageNote = if (hasAllFilesAccess())
                "Same Wi-Fi only - full storage access granted."
            else
                "Same Wi-Fi only - limited storage - grant access in Authorization tab."
            statusSub.text = storageNote
        } else {
            statusCard.setBackgroundResource(R.drawable.bg_status_stopped)
            statusDot.setBackgroundResource(R.drawable.status_dot_red)
            statusText.text = "STOPPED"
            tvUrl.text = ""
            tvUrl.visibility = View.GONE
            statusSub.text = "Tap Start to launch the web server."
        }
    }

    private fun hasAllFilesAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        Environment.isExternalStorageManager() else true

    private fun getLocalIpv4(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    val host = addr.hostAddress ?: continue
                    if (!addr.isLoopbackAddress && !host.contains(':')) return host
                }
            }
        } catch (_: Exception) {}
        return null
    }

    // ---------- Log rendering ----------

    private fun rebuildLogFromSnapshot() {
        val sb = SpannableStringBuilder()
        for (e in ServerLog.snapshot()) appendStyled(sb, e)
        tvLog.text = sb
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendLogLine(e: ServerLog.Entry) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            val sb = SpannableStringBuilder(tvLog.text)
            appendStyled(sb, e)
            tvLog.text = sb
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun appendStyled(sb: SpannableStringBuilder, e: ServerLog.Entry) {
        if (sb.isNotEmpty()) sb.append('\n')
        val color = when (e.level) {
            ServerLog.Level.INFO -> 0xFF8888A0.toInt()
            ServerLog.Level.OK   -> 0xFF5DCA7A.toInt()
            ServerLog.Level.WARN -> 0xFFE0C060.toInt()
            ServerLog.Level.ERR  -> 0xFFE05A6A.toInt()
        }
        val line = ServerLog.format(e)
        val start = sb.length
        sb.append(line)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
