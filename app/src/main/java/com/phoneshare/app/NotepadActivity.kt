package com.phoneshare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.snackbar.Snackbar
import java.util.Date

class NotepadActivity : AppCompatActivity() {

    private lateinit var fingerprint: String
    private lateinit var editor: EditText
    private lateinit var footer: TextView
    private lateinit var titleView: TextView

    private var lastLoadedText: String = ""
    private var dirty = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoSaveRunnable = Runnable { if (dirty) save(silent = true) }

    private val noteUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != PhoneShareServer.ACTION_NOTE_UPDATED) return
            val fp = intent.getStringExtra("fingerprint")
            if (fp == fingerprint && !dirty) reloadFromServer(silent = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notepad)
        fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT) ?: run { finish(); return }
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Note"

        titleView = findViewById(R.id.tvNotepadTitle)
        editor    = findViewById(R.id.edNotepadBody)
        footer    = findViewById(R.id.tvNotepadFooter)
        titleView.text = label

        findViewById<ImageButton>(R.id.btnNotepadBack).setOnClickListener {
            if (dirty) save(silent = true)
            finish()
        }
        findViewById<Button>(R.id.btnNotepadSave).setOnClickListener { save(silent = false) }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if ((s?.toString() ?: "") == lastLoadedText) return
                dirty = true
                footer.text = "* unsaved"
                mainHandler.removeCallbacks(autoSaveRunnable)
                mainHandler.postDelayed(autoSaveRunnable, 1500)
            }
        })

        reloadFromServer(silent = true)
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            noteUpdatedReceiver, IntentFilter(PhoneShareServer.ACTION_NOTE_UPDATED)
        )
    }

    override fun onPause() {
        super.onPause()
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(noteUpdatedReceiver) } catch (_: Throwable) {}
        try { if (dirty) save(silent = true) } catch (_: Throwable) {}
        mainHandler.removeCallbacks(autoSaveRunnable)
    }

    private fun reloadFromServer(silent: Boolean) {
        val srv = WebServerService.instance ?: run {
            footer.text = "Server not running."
            return
        }
        val n = srv.getNoteFor(fingerprint)
        if (dirty && editor.text.toString() != n.text) return
        lastLoadedText = n.text
        editor.setText(n.text)
        editor.setSelection(editor.text.length)
        dirty = false
        footer.text = if (n.updatedAt > 0)
            "saved " + DateFormat.format("HH:mm:ss", Date(n.updatedAt)) +
                " by " + (if (n.updatedBy == "phone") "you" else "device")
        else "new note"
    }

    private fun save(silent: Boolean) {
        val srv = WebServerService.instance ?: run {
            Snackbar.make(findViewById(android.R.id.content), "Server not running.", Snackbar.LENGTH_SHORT).show()
            return
        }
        val text = editor.text.toString()
        srv.setNoteFromPhone(fingerprint, text)
        lastLoadedText = text
        dirty = false
        val ts = DateFormat.format("HH:mm:ss", Date(System.currentTimeMillis()))
        footer.text = "saved $ts by you"
        if (!silent) Snackbar.make(findViewById(android.R.id.content), "Saved.", Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_LABEL = "label"
    }
}
