package com.phoneshare.app

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.Date

class DevicesFragment : Fragment() {

    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var tvMaxValue: TextView

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_devices, parent, false)
        container = v.findViewById(R.id.devicesContainer)
        emptyText = v.findViewById(R.id.tvDevicesEmpty)
        tvMaxValue = v.findViewById(R.id.tvMaxValue)
        v.findViewById<Button>(R.id.btnDevicesRefresh).setOnClickListener { refresh() }
        v.findViewById<Button>(R.id.btnMaxMinus).setOnClickListener { adjustMax(-1) }
        v.findViewById<Button>(R.id.btnMaxPlus).setOnClickListener  { adjustMax(+1) }
        return v
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun adjustMax(delta: Int) {
        val srv = WebServerService.instance ?: return
        val cur = srv.getMaxSessions()
        val next = (cur + delta).coerceIn(1, 99)
        if (next != cur) {
            srv.setMaxSessions(next)
            refresh()
        }
    }

    private fun refresh() {
        container.removeAllViews()
        val srv = WebServerService.instance
        if (srv == null) {
            emptyText.text = "Server not running. Start it from the Server tab."
            emptyText.visibility = View.VISIBLE
            tvMaxValue.text = "-"
            return
        }
        tvMaxValue.text = srv.getMaxSessions().toString()
        val sessions = srv.listSessions()
        val activeFps = sessions.mapNotNull { srv.fingerprintForSession(it) }.toSet()
        val offline = srv.listKnownDevices().filter { it.fingerprint !in activeFps }

        if (sessions.isEmpty() && offline.isEmpty()) {
            emptyText.text = "No devices yet. Authorize one from a browser."
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE
        for (s in sessions) container.addView(makeRowFromSession(s, srv))
        for (k in offline) container.addView(makeRowFromKnown(k, srv))
    }

    private fun makeRowFromSession(s: PhoneShareServer.DeviceSession, srv: PhoneShareServer): View {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_device, container, false)
        v.findViewById<TextView>(R.id.devLabel).text = s.label
        v.findViewById<View>(R.id.devOnlineDot).setBackgroundResource(R.drawable.status_dot_green)
        v.findViewById<TextView>(R.id.devRoleTag).text = s.role.name
        val expiresMin = ((s.expiresAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        v.findViewById<TextView>(R.id.devMeta).text =
            "${s.ip} - code ${s.authCode} - expires in ${expiresMin}m - last seen ${formatTs(s.lastSeenAt)}"
        val fp = srv.fingerprintForSession(s)
        v.setOnClickListener {
            if (fp == null) return@setOnClickListener
            startActivity(Intent(requireContext(), DeviceDetailActivity::class.java)
                .putExtra(DeviceDetailActivity.EXTRA_FINGERPRINT, fp)
                .putExtra(DeviceDetailActivity.EXTRA_SESSION_ID, s.id)
                .putExtra(DeviceDetailActivity.EXTRA_ONLINE, true))
        }
        return v
    }

    private fun makeRowFromKnown(k: PhoneShareServer.KnownDevice, srv: PhoneShareServer): View {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_device, container, false)
        v.findViewById<TextView>(R.id.devLabel).text = k.label
        v.findViewById<View>(R.id.devOnlineDot).setBackgroundResource(R.drawable.status_dot_red)
        v.findViewById<TextView>(R.id.devRoleTag).text = k.role.name
        v.findViewById<TextView>(R.id.devMeta).text =
            "${k.lastIp} - offline - last seen ${formatTs(k.lastSeenAt)}"
        v.setOnClickListener {
            startActivity(Intent(requireContext(), DeviceDetailActivity::class.java)
                .putExtra(DeviceDetailActivity.EXTRA_FINGERPRINT, k.fingerprint)
                .putExtra(DeviceDetailActivity.EXTRA_ONLINE, false))
        }
        return v
    }

    private fun formatTs(ms: Long): String {
        if (ms <= 0) return "-"
        return DateFormat.format("HH:mm:ss", Date(ms)).toString()
    }
}
