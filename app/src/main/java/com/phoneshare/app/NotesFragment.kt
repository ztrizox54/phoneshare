package com.phoneshare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.Date

class NotesFragment : Fragment() {

    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView

    private val noteUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == PhoneShareServer.ACTION_NOTE_UPDATED) refresh()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_notes, parent, false)
        container = v.findViewById(R.id.notesDeviceList)
        emptyText = v.findViewById(R.id.tvNotesEmpty)
        return v
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            noteUpdatedReceiver, IntentFilter(PhoneShareServer.ACTION_NOTE_UPDATED)
        )
        refresh()
    }

    override fun onPause() {
        super.onPause()
        try { LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(noteUpdatedReceiver) } catch (_: Throwable) {}
    }

    private fun refresh() {
        container.removeAllViews()
        val srv = WebServerService.instance
        if (srv == null) {
            emptyText.text = "Server not running."
            emptyText.visibility = View.VISIBLE
            return
        }
        val sessions = srv.listSessions()
        val activeFps = sessions.mapNotNull { srv.fingerprintForSession(it) }.toSet()
        val offline = srv.listKnownDevices().filter { it.fingerprint !in activeFps }

        if (sessions.isEmpty() && offline.isEmpty()) {
            emptyText.text = "No devices yet. Authorize one from a browser to share notes."
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE
        for (s in sessions) {
            val fp = srv.fingerprintForSession(s) ?: continue
            container.addView(makeRow(label = s.label, fingerprint = fp, online = true, srv = srv))
        }
        for (k in offline) {
            container.addView(makeRow(label = k.label, fingerprint = k.fingerprint, online = false, srv = srv))
        }
    }

    private fun makeRow(label: String, fingerprint: String, online: Boolean, srv: PhoneShareServer): View {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_device, container, false)
        v.findViewById<TextView>(R.id.devLabel).text = label
        v.findViewById<View>(R.id.devOnlineDot).setBackgroundResource(
            if (online) R.drawable.status_dot_green else R.drawable.status_dot_red
        )
        val note = srv.getNoteFor(fingerprint)
        val preview = note.text.lineSequence().firstOrNull()?.take(80) ?: ""
        val ts = if (note.updatedAt > 0)
            DateFormat.format("HH:mm:ss", Date(note.updatedAt)).toString()
        else "never"
        val by = if (note.updatedBy == "phone") "you" else "device"
        v.findViewById<TextView>(R.id.devRoleTag).text = if (note.text.isBlank()) "EMPTY" else "NOTE"
        v.findViewById<TextView>(R.id.devMeta).text = if (note.text.isBlank())
            "no shared note - $ts" else "\"$preview\" - last edit $ts by $by"
        v.setOnClickListener {
            val i = Intent(requireContext(), NotepadActivity::class.java)
                .putExtra(NotepadActivity.EXTRA_FINGERPRINT, fingerprint)
                .putExtra(NotepadActivity.EXTRA_LABEL, label)
            startActivity(i)
        }
        return v
    }
}
