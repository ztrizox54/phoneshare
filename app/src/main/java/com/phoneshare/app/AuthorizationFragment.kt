package com.phoneshare.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

class AuthorizationFragment : Fragment() {

    private lateinit var rootView: View
    private lateinit var tvStorageStatus: TextView
    private lateinit var strangersList: LinearLayout
    private lateinit var strangersEmpty: TextView

    private val poller = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() { refreshStrangers(); poller.postDelayed(this, 3000) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_authorization, container, false)
        rootView = v
        tvStorageStatus = v.findViewById(R.id.tvStorageStatus)
        strangersList   = v.findViewById(R.id.strangersList)
        strangersEmpty  = v.findViewById(R.id.tvStrangersEmpty)
        v.findViewById<Button>(R.id.btnGrantStorage).setOnClickListener { requestAllFilesAccess() }
        v.findViewById<Button>(R.id.btnAuthCode).setOnClickListener { showManualCodeDialog() }
        v.findViewById<Button>(R.id.btnRefreshStrangers).setOnClickListener { refreshStrangers() }
        v.findViewById<Button>(R.id.btnClearAllData).setOnClickListener { confirmClearAllData() }
        return v
    }

    override fun onResume() {
        super.onResume()
        tvStorageStatus.text = if (hasAllFilesAccess())
            "[OK] Full storage access - every file on the phone is shareable."
        else
            "[!] Limited access - only your app folder is visible. Grant for full sharing."
        refreshStrangers()
        poller.postDelayed(pollRunnable, 3000)
    }

    override fun onPause() {
        super.onPause()
        poller.removeCallbacks(pollRunnable)
    }

    private fun hasAllFilesAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        Environment.isExternalStorageManager() else true

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${requireContext().packageName}")
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun showManualCodeDialog() {
        val srv = WebServerService.instance
        if (srv == null) {
            Toast.makeText(requireContext(), "Start the server first.", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = "ABC234"
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Enter device authorization code")
            .setMessage("Type the 6-character code shown under the QR code on the other device.")
            .setView(input)
            .setPositiveButton("Continue") { _, _ ->
                val code = input.text.toString().trim().uppercase(Locale.US)
                if (code.isEmpty()) return@setPositiveButton
                (activity as? MainActivity)?.handleAuthCodeEntered(code)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Strangers waiting ----------

    private fun refreshStrangers() {
        val srv = WebServerService.instance ?: return
        val list = srv.listPendingChallenges()
        strangersList.removeAllViews()
        if (list.isEmpty()) {
            strangersEmpty.visibility = View.VISIBLE
            return
        }
        strangersEmpty.visibility = View.GONE
        val now = System.currentTimeMillis()
        for (p in list) strangersList.addView(makeStrangerRow(p, now, srv))
    }

    private fun makeStrangerRow(p: PhoneShareServer.PendingAuth, now: Long, srv: PhoneShareServer): View {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_stranger, strangersList, false)
        v.findViewById<TextView>(R.id.strCode).text = p.code
        val ageSec = ((now - p.createdAt) / 1000).coerceAtLeast(0)
        val leftSec = ((p.expiresAt - now) / 1000).coerceAtLeast(0)
        v.findViewById<TextView>(R.id.strAge).text = "${ageSec}s ago - ${leftSec / 60}m${leftSec % 60}s left"
        val ua = if (p.userAgent.isBlank()) "Unknown agent" else uaShort(p.userAgent)
        v.findViewById<TextView>(R.id.strDevice).text = "$ua - ${p.ip}"
        v.findViewById<Button>(R.id.strApprove).setOnClickListener { showApproveDialog(p) }
        v.findViewById<Button>(R.id.strDeny).setOnClickListener {
            srv.denyPendingChallenge(p.token)
            refreshStrangers()
        }
        return v
    }

    private fun uaShort(ua: String): String {
        val name = when {
            ua.contains("Edg/", true)     -> "Edge"
            ua.contains("OPR/", true)     -> "Opera"
            ua.contains("Chrome/", true)  -> "Chrome"
            ua.contains("Firefox/", true) -> "Firefox"
            ua.contains("Safari/", true)  -> "Safari"
            else -> "Browser"
        }
        val os = when {
            ua.contains("Windows", true)   -> "Windows"
            ua.contains("Macintosh", true) -> "macOS"
            ua.contains("Android", true)   -> "Android"
            ua.contains("iPhone", true)    -> "iOS"
            ua.contains("Linux", true)     -> "Linux"
            else -> ""
        }
        return if (os.isNotBlank()) "$name on $os" else name
    }

    private fun showApproveDialog(p: PhoneShareServer.PendingAuth) {
        val srv = WebServerService.instance ?: return
        val view = layoutInflater.inflate(R.layout.dialog_authorize, null)
        val tvDevice = view.findViewById<TextView>(R.id.tvAuthDeviceLine)
        val tvCode   = view.findViewById<TextView>(R.id.tvAuthCodeLine)
        val tvKnown  = view.findViewById<TextView>(R.id.tvAuthKnownHint)
        val rbAdmin  = view.findViewById<RadioButton>(R.id.rbAuthAdmin)
        val rbGuest  = view.findViewById<RadioButton>(R.id.rbAuthGuest)

        tvDevice.text = "${uaShort(p.userAgent)} - ${p.ip}"
        tvCode.text = "Code ${p.code}"
        val known = srv.pendingAuthKnownDevice(p.code)
        if (known != null) {
            tvKnown.visibility = View.VISIBLE
            tvKnown.text = "Known device: \"${known.label}\" - previous role: ${known.role.name.lowercase()}."
            if (known.role == PhoneShareServer.DeviceRole.GUEST) rbGuest.isChecked = true
            else rbAdmin.isChecked = true
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Approve stranger?")
            .setView(view)
            .setPositiveButton("Approve") { _, _ ->
                val role = if (rbGuest.isChecked) PhoneShareServer.DeviceRole.GUEST
                           else PhoneShareServer.DeviceRole.ADMIN
                val res = srv.approveAuth(p.code, role)
                val msg = when (res) {
                    PhoneShareServer.AuthResult.APPROVED   -> "Approved as ${role.name.lowercase()}."
                    PhoneShareServer.AuthResult.AT_CAPACITY -> "At capacity - disconnect a device first."
                    PhoneShareServer.AuthResult.EXPIRED    -> "Code expired."
                }
                Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT).show()
                refreshStrangers()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Danger zone ----------

    private fun confirmClearAllData() {
        val srv = WebServerService.instance
        if (srv == null) {
            Toast.makeText(requireContext(), "Start the server first.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Clear ALL device data?")
            .setMessage(
                "This permanently deletes:\n" +
                "  - all sessions (every device signed out)\n" +
                "  - all remembered devices and their saved permissions\n" +
                "  - all shared notes\n" +
                "  - the entire activity log\n\n" +
                "Type-confirm by tapping Wipe twice. There is NO undo."
            )
            .setPositiveButton("Wipe") { _, _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Really wipe everything?")
                    .setMessage("Last chance. This cannot be undone.")
                    .setPositiveButton("Yes, wipe") { _, _ ->
                        srv.clearAllDeviceData()
                        Toast.makeText(requireContext(), "All device data cleared.", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
