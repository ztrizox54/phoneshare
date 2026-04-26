package com.phoneshare.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var topbarTitle: TextView
    private lateinit var drawerFooter: TextView
    private lateinit var navServer: View
    private lateinit var navAuth: View
    private lateinit var navDevices: View
    private lateinit var navNotes: View
    private lateinit var navSettings: View
    private lateinit var navLogs: View
    private lateinit var navDevicesBadge: View
    private lateinit var navNotesBadge: View
    private lateinit var navSettingsBadge: TextView

    private var currentTag: String = TAG_SERVER

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == WebServerService.ACTION_STATUS) refreshDrawerFooter()
        }
    }

    private val noteUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != PhoneShareServer.ACTION_NOTE_UPDATED) return
            if (currentTag != TAG_NOTES) navNotesBadge.visibility = View.VISIBLE
        }
    }

    private val approvalReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != PhoneShareServer.ACTION_APPROVAL_REQUEST) return
            val id = intent.getStringExtra("id") ?: return
            val action = intent.getStringExtra("action") ?: "?"
            val path = intent.getStringExtra("path") ?: "?"
            val device = intent.getStringExtra("device") ?: "?"
            val size = intent.getLongExtra("size", -1L)
            shownApprovalIds.add(id)
            promptApproval(id, action, path, device, size)
        }
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawer = findViewById(R.id.drawerLayout)
        topbarTitle = findViewById(R.id.tvTopbarTitle)
        drawerFooter = findViewById(R.id.tvDrawerFooter)
        navServer   = findViewById(R.id.navServer)
        navAuth     = findViewById(R.id.navAuth)
        navDevices  = findViewById(R.id.navDevices)
        navNotes    = findViewById(R.id.navNotes)
        navSettings = findViewById(R.id.navSettings)
        navLogs     = findViewById(R.id.navLogs)
        navDevicesBadge  = findViewById(R.id.navDevicesBadge)
        navNotesBadge    = findViewById(R.id.navNotesBadge)
        navSettingsBadge = findViewById(R.id.navSettingsBadge)

        findViewById<ImageButton>(R.id.btnDrawerToggle).setOnClickListener {
            if (drawer.isDrawerOpen(Gravity.START)) drawer.closeDrawer(Gravity.START)
            else drawer.openDrawer(Gravity.START)
        }
        navServer  .setOnClickListener { selectPage(TAG_SERVER) }
        navAuth    .setOnClickListener { selectPage(TAG_AUTH) }
        navDevices .setOnClickListener { selectPage(TAG_DEVICES) }
        navNotes   .setOnClickListener { selectPage(TAG_NOTES); navNotesBadge.visibility = View.GONE }
        navSettings.setOnClickListener { selectPage(TAG_SETTINGS); navSettingsBadge.visibility = View.GONE }
        navLogs    .setOnClickListener { selectPage(TAG_LOGS) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (savedInstanceState == null) {
            selectPage(TAG_SERVER)
        } else {
            currentTag = savedInstanceState.getString(KEY_PAGE, TAG_SERVER)
            updateNavSelection()
        }

        handleAuthIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PAGE, currentTag)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(WebServerService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            approvalReceiver, IntentFilter(PhoneShareServer.ACTION_APPROVAL_REQUEST)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            noteUpdatedReceiver, IntentFilter(PhoneShareServer.ACTION_NOTE_UPDATED)
        )
        refreshDrawerFooter()
        showPendingApprovals()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        LocalBroadcastManager.getInstance(this).unregisterReceiver(approvalReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(noteUpdatedReceiver)
    }

    private val shownApprovalIds = HashSet<String>()
    private fun showPendingApprovals() {
        val srv = WebServerService.instance ?: return
        for (a in srv.listPendingApprovals()) {
            if (!shownApprovalIds.add(a.id)) continue
            promptApproval(a.id, a.action, a.path, a.deviceLabel, a.sizeBytes ?: -1L)
        }
    }

    private fun selectPage(tag: String) {
        currentTag = tag
        val fragment: Fragment = when (tag) {
            TAG_SERVER   -> ServerFragment()
            TAG_AUTH     -> AuthorizationFragment()
            TAG_DEVICES  -> DevicesFragment()
            TAG_NOTES    -> NotesFragment()
            TAG_SETTINGS -> SettingsFragment()
            TAG_LOGS     -> LogsFragment()
            else         -> ServerFragment()
        }
        topbarTitle.text = titleFor(tag)
        supportFragmentManager.commit {
            replace(R.id.fragmentContainer, fragment, tag)
        }
        updateNavSelection()
        drawer.closeDrawer(Gravity.START)
    }

    private fun titleFor(tag: String): String = when (tag) {
        TAG_SERVER   -> "Server"
        TAG_AUTH     -> "Authorization"
        TAG_DEVICES  -> "Devices"
        TAG_NOTES    -> "Shared notes"
        TAG_SETTINGS -> "Permissions"
        TAG_LOGS     -> "Activity log"
        else         -> "PhoneShare"
    }

    private fun updateNavSelection() {
        navServer  .isSelected = (currentTag == TAG_SERVER)
        navAuth    .isSelected = (currentTag == TAG_AUTH)
        navDevices .isSelected = (currentTag == TAG_DEVICES)
        navNotes   .isSelected = (currentTag == TAG_NOTES)
        navSettings.isSelected = (currentTag == TAG_SETTINGS)
        navLogs    .isSelected = (currentTag == TAG_LOGS)
    }

    private fun refreshDrawerFooter() {
        val running = WebServerService.isRunning
        drawerFooter.text = if (running) "server: running on :${WebServerService.port}"
                            else         "server: stopped"
        if (currentTag != TAG_SETTINGS) {
            val n = WebServerService.instance?.listPendingChallenges()?.size ?: 0
            if (n > 0) {
                navSettingsBadge.visibility = View.VISIBLE
                navSettingsBadge.text = n.toString()
            } else navSettingsBadge.visibility = View.GONE
        }
    }

    // ---------- Deep-link / approval ----------

    private fun handleAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "phoneshare" || data.host != "auth") return
        val tokenOrCode = data.getQueryParameter("token")
            ?: data.getQueryParameter("code")
            ?: return
        val code = data.getQueryParameter("code") ?: tokenOrCode
        promptApprove(tokenOrCode, code)
    }

    /** Called by AuthorizationFragment when the user types in a code. */
    fun handleAuthCodeEntered(code: String) {
        promptApprove(code, code)
    }

    private fun promptApprove(tokenOrCode: String, displayCode: String?) {
        val srv = WebServerService.instance
        if (srv == null) {
            Toast.makeText(this, "Start the server first.", Toast.LENGTH_SHORT).show()
            return
        }
        val description = srv.pendingAuthDescription(tokenOrCode)
        if (description == null) {
            Toast.makeText(this, "Code expired or unknown - refresh the device's page.", Toast.LENGTH_LONG).show()
            return
        }
        val known = srv.pendingAuthKnownDevice(tokenOrCode)
        val view = layoutInflater.inflate(R.layout.dialog_authorize, null)
        val tvDevice = view.findViewById<TextView>(R.id.tvAuthDeviceLine)
        val tvCode   = view.findViewById<TextView>(R.id.tvAuthCodeLine)
        val tvKnown  = view.findViewById<TextView>(R.id.tvAuthKnownHint)
        val rbAdmin  = view.findViewById<RadioButton>(R.id.rbAuthAdmin)
        val rbGuest  = view.findViewById<RadioButton>(R.id.rbAuthGuest)

        val parts = description.split(" - ", limit = 2)
        tvDevice.text = parts.getOrNull(1) ?: description
        tvCode.text = displayCode?.let { "Code $it" } ?: parts.getOrNull(0) ?: ""

        if (known != null) {
            tvKnown.visibility = View.VISIBLE
            tvKnown.text = "Known device: \"${known.label}\" (last role: ${known.role.name.lowercase()}). " +
                "Approving will reuse its previous settings."
            if (known.role == PhoneShareServer.DeviceRole.GUEST) rbGuest.isChecked = true
            else rbAdmin.isChecked = true
        }

        AlertDialog.Builder(this)
            .setTitle(if (known != null) "Re-authorize this device?" else "Authorize this device?")
            .setView(view)
            .setPositiveButton("Approve") { _, _ ->
                val role = if (rbGuest.isChecked) PhoneShareServer.DeviceRole.GUEST
                           else PhoneShareServer.DeviceRole.ADMIN
                val res = srv.approveAuth(tokenOrCode, role)
                when (res) {
                    PhoneShareServer.AuthResult.APPROVED ->
                        Toast.makeText(this, "Approved as ${role.name.lowercase()}.", Toast.LENGTH_SHORT).show()
                    PhoneShareServer.AuthResult.AT_CAPACITY ->
                        Toast.makeText(this, "Max devices reached - disconnect one in Devices first.", Toast.LENGTH_LONG).show()
                    PhoneShareServer.AuthResult.EXPIRED ->
                        Toast.makeText(this, "Code expired.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Deny", null)
            .show()
    }

    private fun promptApproval(id: String, action: String, path: String, device: String, sizeBytes: Long) {
        val srv = WebServerService.instance ?: return
        val sizeNote = if (sizeBytes > 0) " (${humanSizeUi(sizeBytes)})" else ""
        val verb = if (action == "upload") "upload to" else "download"
        AlertDialog.Builder(this)
            .setTitle("Approval requested")
            .setMessage("$device wants to $verb:\n\n$path$sizeNote")
            .setPositiveButton("Approve") { _, _ -> srv.decideApproval(id, true) }
            .setNegativeButton("Deny")    { _, _ -> srv.decideApproval(id, false) }
            .setCancelable(true)
            .show()
    }

    private fun humanSizeUi(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    companion object {
        private const val TAG_SERVER   = "server"
        private const val TAG_AUTH     = "auth"
        private const val TAG_DEVICES  = "devices"
        private const val TAG_NOTES    = "notes"
        private const val TAG_SETTINGS = "settings"
        private const val TAG_LOGS     = "logs"
        private const val KEY_PAGE     = "current_page"
    }
}
