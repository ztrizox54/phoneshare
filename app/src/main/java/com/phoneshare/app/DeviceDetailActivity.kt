package com.phoneshare.app

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import java.util.Date

class DeviceDetailActivity : AppCompatActivity() {

    private lateinit var fingerprint: String
    private var sessionId: String? = null
    private var isOnline: Boolean = false

    private lateinit var labelView: TextView
    private lateinit var metaView: TextView

    private lateinit var tabPerms: TextView
    private lateinit var tabActivity: TextView
    private lateinit var tabNote: TextView
    private lateinit var panePerms: View
    private lateinit var paneActivity: View
    private lateinit var paneNote: View

    private lateinit var edLabel: EditText
    private lateinit var rbAdmin: RadioButton
    private lateinit var rbGuest: RadioButton
    private lateinit var edRoots: EditText
    private lateinit var cbDl: CheckBox
    private lateinit var cbUp: CheckBox
    private lateinit var cbDel: CheckBox
    private lateinit var cbRen: CheckBox
    private lateinit var cbMov: CheckBox
    private lateinit var cbRead: CheckBox
    private lateinit var cbMkdir: CheckBox

    private lateinit var tvActivity: TextView
    private lateinit var tvNoteMeta: TextView
    private lateinit var tvNoteBody: TextView

    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_detail)
        rootView = findViewById(android.R.id.content)

        fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT) ?: run { finish(); return }
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        isOnline = intent.getBooleanExtra(EXTRA_ONLINE, false)

        labelView = findViewById(R.id.tvDevDetailLabel)
        metaView  = findViewById(R.id.tvDevDetailMeta)

        tabPerms    = findViewById(R.id.tabPerms)
        tabActivity = findViewById(R.id.tabActivity)
        tabNote     = findViewById(R.id.tabNote)
        panePerms    = findViewById(R.id.panePerms)
        paneActivity = findViewById(R.id.paneActivity)
        paneNote     = findViewById(R.id.paneNote)

        edLabel = findViewById(R.id.edDevLabel)
        rbAdmin = findViewById(R.id.rbDevAdmin)
        rbGuest = findViewById(R.id.rbDevGuest)
        edRoots = findViewById(R.id.edDevAllowed)
        cbDl    = findViewById(R.id.cbDevReqDl)
        cbUp    = findViewById(R.id.cbDevReqUp)
        cbDel   = findViewById(R.id.cbDevReqDel)
        cbRen   = findViewById(R.id.cbDevReqRen)
        cbMov   = findViewById(R.id.cbDevReqMov)
        cbRead  = findViewById(R.id.cbDevReqRead)
        cbMkdir = findViewById(R.id.cbDevReqMkdir)

        tvActivity  = findViewById(R.id.tvDevActivity)
        tvNoteMeta  = findViewById(R.id.tvDevNoteMeta)
        tvNoteBody  = findViewById(R.id.tvDevNoteBody)

        findViewById<ImageButton>(R.id.btnDevDetailBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnDevDetailSave).setOnClickListener { onSave() }
        findViewById<Button>(R.id.btnDevDisconnect).setOnClickListener { onDisconnect() }
        findViewById<Button>(R.id.btnDevForget).setOnClickListener { onForget() }
        findViewById<Button>(R.id.btnDevOpenNote).setOnClickListener { openNote() }

        tabPerms   .setOnClickListener { selectTab(0) }
        tabActivity.setOnClickListener { selectTab(1) }
        tabNote    .setOnClickListener { selectTab(2) }

        selectTab(0)
        loadFromServer()
    }

    override fun onResume() {
        super.onResume()
        loadFromServer()
    }

    private fun selectTab(idx: Int) {
        tabPerms   .isSelected = idx == 0
        tabActivity.isSelected = idx == 1
        tabNote    .isSelected = idx == 2
        panePerms   .visibility = if (idx == 0) View.VISIBLE else View.GONE
        paneActivity.visibility = if (idx == 1) View.VISIBLE else View.GONE
        paneNote    .visibility = if (idx == 2) View.VISIBLE else View.GONE
        if (idx == 1) refreshActivity()
        if (idx == 2) refreshNote()
    }

    private fun loadFromServer() {
        val srv = WebServerService.instance ?: run {
            snack("Server not running."); return
        }
        val session = srv.listSessions().firstOrNull { srv.fingerprintForSession(it) == fingerprint }
        val known   = srv.listKnownDevices().firstOrNull { it.fingerprint == fingerprint }
        if (session == null && known == null) {
            snack("Device not found."); finish(); return
        }
        isOnline = session != null
        sessionId = session?.id

        val label = session?.label ?: known?.label ?: "Device"
        val role = session?.role ?: known?.role ?: PhoneShareServer.DeviceRole.ADMIN
        val roots = session?.allowedRoots ?: known?.allowedRoots ?: mutableListOf("/")
        val reqDl = session?.requireDownloadApproval ?: known?.requireDownloadApproval ?: false
        val reqUp = session?.requireUploadApproval ?: known?.requireUploadApproval ?: false
        val reqDel   = session?.requireDeleteApproval ?: known?.requireDeleteApproval ?: true
        val reqRen   = session?.requireRenameApproval ?: known?.requireRenameApproval ?: false
        val reqMov   = session?.requireMoveApproval   ?: known?.requireMoveApproval   ?: true
        val reqRead  = session?.requireReadApproval   ?: known?.requireReadApproval   ?: false
        val reqMkdir = session?.requireMkdirApproval  ?: known?.requireMkdirApproval  ?: true
        val ip = session?.ip ?: known?.lastIp ?: "?"
        val authCode = session?.authCode ?: ""
        val expiresAt = session?.expiresAt ?: 0L

        labelView.text = label
        val expHint = if (isOnline && expiresAt > 0)
            " - expires in ${((expiresAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)}m"
        else " - offline"
        val codeHint = if (authCode.isNotBlank()) " - code $authCode" else ""
        metaView.text = "$ip$codeHint$expHint"

        edLabel.setText(label)
        if (role == PhoneShareServer.DeviceRole.GUEST) rbGuest.isChecked = true else rbAdmin.isChecked = true
        edRoots.setText(roots.joinToString("\n"))
        cbDl.isChecked    = reqDl
        cbUp.isChecked    = reqUp
        cbDel.isChecked   = reqDel
        cbRen.isChecked   = reqRen
        cbMov.isChecked   = reqMov
        cbRead.isChecked  = reqRead
        cbMkdir.isChecked = reqMkdir
    }

    private fun onSave() {
        val srv = WebServerService.instance ?: return
        val newRole = if (rbGuest.isChecked) PhoneShareServer.DeviceRole.GUEST
                      else PhoneShareServer.DeviceRole.ADMIN
        val newRoots = edRoots.text.toString().split('\n')
            .map { it.trim().trim('/') }.filter { it.isNotBlank() }.map { "/$it" }
        val ok = srv.updateKnownDevice(
            fingerprint = fingerprint,
            label = edLabel.text.toString().trim(),
            role = newRole,
            allowedRoots = newRoots,
            requireDownloadApproval = cbDl.isChecked,
            requireUploadApproval = cbUp.isChecked,
            requireDeleteApproval = cbDel.isChecked,
            requireRenameApproval = cbRen.isChecked,
            requireMoveApproval = cbMov.isChecked,
            requireReadApproval = cbRead.isChecked,
            requireMkdirApproval = cbMkdir.isChecked,
        )
        if (ok) snack("Saved.") else snack("Save failed: device not found.")
        loadFromServer()
    }

    private fun onDisconnect() {
        if (!isOnline || sessionId == null) {
            snack("Already offline."); return
        }
        AlertDialog.Builder(this)
            .setTitle("Disconnect this session?")
            .setMessage("Closes the active session. The device will need to re-authorize. Settings are kept.")
            .setPositiveButton("Disconnect") { _, _ ->
                WebServerService.instance?.revokeSession(sessionId!!)
                snack("Disconnected.")
                isOnline = false
                sessionId = null
                loadFromServer()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onForget() {
        AlertDialog.Builder(this)
            .setTitle("Forget device?")
            .setMessage("Removes saved settings, signs out any active session, and clears the shared note. Default permissions on next auth.")
            .setPositiveButton("Forget") { _, _ ->
                WebServerService.instance?.forgetDevice(fingerprint)
                snack("Device forgotten.")
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openNote() {
        startActivity(Intent(this, NotepadActivity::class.java)
            .putExtra(NotepadActivity.EXTRA_FINGERPRINT, fingerprint)
            .putExtra(NotepadActivity.EXTRA_LABEL, labelView.text.toString()))
    }

    private fun refreshActivity() {
        val srv = WebServerService.instance ?: return
        val all = srv.snapshotLogs()
        val sid = sessionId
        val mine = if (sid != null) all.filter { it.sessionId != null
            && it.sessionId.startsWith(sid.take(8)) }.takeLast(80) else emptyList()
        if (mine.isEmpty()) {
            tvActivity.text = if (isOnline) "No activity from this device yet."
                              else "No recent activity (device is offline)."
            return
        }
        val sb = SpannableStringBuilder()
        for (e in mine) {
            val color = when {
                e.status >= 500 -> 0xFFE05A6A.toInt()
                e.status >= 400 -> 0xFFE0C060.toInt()
                e.status in 200..299 -> 0xFF5DCA7A.toInt()
                else -> 0xFF8888A0.toInt()
            }
            val time = DateFormat.format("HH:mm:ss", Date(e.ts)).toString()
            val line = "$time  ${e.method} ${e.uri}  -> ${e.status} ${e.ms}ms\n"
            val start = sb.length
            sb.append(line)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tvActivity.text = sb
    }

    private fun refreshNote() {
        val srv = WebServerService.instance ?: return
        val n = srv.getNoteFor(fingerprint)
        tvNoteMeta.text = if (n.updatedAt > 0)
            "last update " + DateFormat.format("HH:mm:ss", Date(n.updatedAt)) +
                " by " + (if (n.updatedBy == "phone") "you" else "device")
        else "no note yet"
        tvNoteBody.text = if (n.text.isBlank()) "(empty - tap Open notepad to write one)" else n.text
    }

    private fun snack(msg: String) {
        Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_SESSION_ID  = "session_id"
        const val EXTRA_ONLINE      = "online"
    }
}
