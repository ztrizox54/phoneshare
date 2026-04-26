package com.phoneshare.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar

class SettingsFragment : Fragment() {

    private lateinit var rootView: View

    // Default GUEST
    private lateinit var edDefGuestRoots: EditText
    private lateinit var cbGuestRead:  CheckBox
    private lateinit var cbGuestDl:    CheckBox
    private lateinit var cbGuestUp:    CheckBox
    private lateinit var cbGuestMkdir: CheckBox
    private lateinit var cbGuestDel:   CheckBox
    private lateinit var cbGuestRen:   CheckBox
    private lateinit var cbGuestMov:   CheckBox

    // Default ADMIN
    private lateinit var cbAdminRead:  CheckBox
    private lateinit var cbAdminDl:    CheckBox
    private lateinit var cbAdminUp:    CheckBox
    private lateinit var cbAdminMkdir: CheckBox
    private lateinit var cbAdminDel:   CheckBox
    private lateinit var cbAdminRen:   CheckBox
    private lateinit var cbAdminMov:   CheckBox

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        rootView = v
        edDefGuestRoots = v.findViewById(R.id.edDefGuestRoots)
        cbGuestRead  = v.findViewById(R.id.cbDefGuestRead)
        cbGuestDl    = v.findViewById(R.id.cbDefGuestDl)
        cbGuestUp    = v.findViewById(R.id.cbDefGuestUp)
        cbGuestMkdir = v.findViewById(R.id.cbDefGuestMkdir)
        cbGuestDel   = v.findViewById(R.id.cbDefGuestDel)
        cbGuestRen   = v.findViewById(R.id.cbDefGuestRen)
        cbGuestMov   = v.findViewById(R.id.cbDefGuestMov)

        cbAdminRead  = v.findViewById(R.id.cbDefAdminRead)
        cbAdminDl    = v.findViewById(R.id.cbDefAdminDl)
        cbAdminUp    = v.findViewById(R.id.cbDefAdminUp)
        cbAdminMkdir = v.findViewById(R.id.cbDefAdminMkdir)
        cbAdminDel   = v.findViewById(R.id.cbDefAdminDel)
        cbAdminRen   = v.findViewById(R.id.cbDefAdminRen)
        cbAdminMov   = v.findViewById(R.id.cbDefAdminMov)

        v.findViewById<Button>(R.id.btnSavePermissions).setOnClickListener { savePermissions() }
        return v
    }

    override fun onResume() {
        super.onResume()
        loadFromServer()
    }

    private fun loadFromServer() {
        val srv = WebServerService.instance ?: return
        val g = srv.getDefaultPerms(PhoneShareServer.DeviceRole.GUEST)
        val a = srv.getDefaultPerms(PhoneShareServer.DeviceRole.ADMIN)
        edDefGuestRoots.setText(g.allowedRoots.joinToString("\n"))
        cbGuestRead.isChecked  = g.requireReadApproval
        cbGuestDl.isChecked    = g.requireDownloadApproval
        cbGuestUp.isChecked    = g.requireUploadApproval
        cbGuestMkdir.isChecked = g.requireMkdirApproval
        cbGuestDel.isChecked   = g.requireDeleteApproval
        cbGuestRen.isChecked   = g.requireRenameApproval
        cbGuestMov.isChecked   = g.requireMoveApproval
        cbAdminRead.isChecked  = a.requireReadApproval
        cbAdminDl.isChecked    = a.requireDownloadApproval
        cbAdminUp.isChecked    = a.requireUploadApproval
        cbAdminMkdir.isChecked = a.requireMkdirApproval
        cbAdminDel.isChecked   = a.requireDeleteApproval
        cbAdminRen.isChecked   = a.requireRenameApproval
        cbAdminMov.isChecked   = a.requireMoveApproval
    }

    private fun savePermissions() {
        val srv = WebServerService.instance ?: return
        val guestRoots = edDefGuestRoots.text.toString().split('\n')
            .map { it.trim().trim('/') }.filter { it.isNotBlank() }.map { "/$it" }
        srv.setDefaultPerms(PhoneShareServer.DeviceRole.GUEST,
            allowedRoots = guestRoots,
            requireDownloadApproval = cbGuestDl.isChecked,
            requireUploadApproval   = cbGuestUp.isChecked,
            requireDeleteApproval   = cbGuestDel.isChecked,
            requireRenameApproval   = cbGuestRen.isChecked,
            requireMoveApproval     = cbGuestMov.isChecked,
            requireReadApproval     = cbGuestRead.isChecked,
            requireMkdirApproval    = cbGuestMkdir.isChecked,
        )
        srv.setDefaultPerms(PhoneShareServer.DeviceRole.ADMIN,
            allowedRoots = null,
            requireDownloadApproval = cbAdminDl.isChecked,
            requireUploadApproval   = cbAdminUp.isChecked,
            requireDeleteApproval   = cbAdminDel.isChecked,
            requireRenameApproval   = cbAdminRen.isChecked,
            requireMoveApproval     = cbAdminMov.isChecked,
            requireReadApproval     = cbAdminRead.isChecked,
            requireMkdirApproval    = cbAdminMkdir.isChecked,
        )
        Snackbar.make(rootView, "Permissions saved.", Snackbar.LENGTH_SHORT).show()
    }
}
