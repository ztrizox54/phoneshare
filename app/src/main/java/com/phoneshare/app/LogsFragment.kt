package com.phoneshare.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.Date

class LogsFragment : Fragment() {

    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private lateinit var spinner: Spinner

    private var deviceFilter: String = "all"
    private val poller = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() { refresh(); poller.postDelayed(this, 3000) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_logs, container, false)
        body = v.findViewById(R.id.tvLogsBody)
        scroll = v.findViewById(R.id.logScrollView)
        spinner = v.findViewById(R.id.spLogDevice)
        v.findViewById<Button>(R.id.btnLogsRefresh).setOnClickListener { refresh() }
        rebuildSpinner()
        return v
    }

    override fun onResume() {
        super.onResume()
        rebuildSpinner()
        refresh()
        poller.postDelayed(pollRunnable, 3000)
    }

    override fun onPause() {
        super.onPause()
        poller.removeCallbacks(pollRunnable)
    }

    private fun rebuildSpinner() {
        val srv = WebServerService.instance
        val items = mutableListOf("All devices")
        val ids = mutableListOf("all")
        if (srv != null) {
            val sessions = srv.listSessions()
            val onlineFps = sessions.mapNotNull { srv.fingerprintForSession(it) }.toHashSet()
            for (d in sessions) {
                items.add(d.label + " (online)"); ids.add(d.id.take(8))
            }
            for (k in srv.listKnownDevices()) {
                if (k.fingerprint in onlineFps) continue
                items.add(k.label + " (offline)"); ids.add(k.fingerprint.take(8))
            }
        }
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item_dark, items)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_dark)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                deviceFilter = ids[position]; refresh()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val idx = ids.indexOf(deviceFilter)
        if (idx >= 0) spinner.setSelection(idx)
    }

    private fun refresh() {
        val srv = WebServerService.instance
        if (srv == null) {
            body.text = "Server not running. Start it from the Server tab."
            return
        }
        val all = srv.snapshotLogs()
        val filter = deviceFilter
        val filtered = all.asReversed().asSequence()
            .filter { if (filter == "all") true else (it.sessionId?.startsWith(filter) == true) }
            .take(400)
            .toList()
            .asReversed()

        if (filtered.isEmpty()) {
            body.text = if (filter == "all") "No activity yet. Anything devices do will appear here."
                        else "No activity from this device yet."
            return
        }

        val sb = SpannableStringBuilder()
        for (e in filtered) {
            val color = when {
                e.status >= 500 -> 0xFFE05A6A.toInt()
                e.status >= 400 -> 0xFFE0C060.toInt()
                e.status in 200..299 -> 0xFF5DCA7A.toInt()
                else -> 0xFF8888A0.toInt()
            }
            val time = DateFormat.format("HH:mm:ss", Date(e.ts)).toString()
            val line = "$time  ${e.method} ${e.uri}  -> ${e.status} ${e.ms}ms - ${e.deviceLabel}\n"
            val start = sb.length
            sb.append(line)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        body.text = sb
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
