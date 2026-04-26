package com.phoneshare.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

class PhoneShareServer(
    private val ctx: Context,
    port: Int
) : NanoWSD(port) {

    private val rootDir: File = Environment.getExternalStorageDirectory()
        ?: ctx.getExternalFilesDir(null)
        ?: ctx.filesDir

    private val sockets = Collections.synchronizedList(mutableListOf<RefreshSocket>())

    // ---------- Models ----------

    enum class DeviceRole { ADMIN, GUEST }
    enum class ApprovalDecision { APPROVED, DENIED }
    enum class AuthResult { APPROVED, EXPIRED, AT_CAPACITY }

    data class DeviceSession(
        val id: String,
        @Volatile var fingerprint: String,
        @Volatile var label: String,
        val createdAt: Long,
        @Volatile var lastSeenAt: Long,
        @Volatile var expiresAt: Long,
        val ip: String,
        val userAgent: String,
        val authCode: String,
        @Volatile var role: DeviceRole = DeviceRole.ADMIN,
        @Volatile var allowedRoots: MutableList<String> = mutableListOf("/"),
        @Volatile var requireDownloadApproval: Boolean = false,
        @Volatile var requireUploadApproval: Boolean = false,
        @Volatile var requireDeleteApproval: Boolean = true,
        @Volatile var requireRenameApproval: Boolean = true,
        @Volatile var requireMoveApproval: Boolean = true,
        @Volatile var requireReadApproval: Boolean = false,
        @Volatile var requireMkdirApproval: Boolean = true,
        @Volatile var isStranger: Boolean = false,
    )

    data class PendingAuth(
        val token: String,
        val code: String,
        val createdAt: Long,
        val expiresAt: Long,
        val ip: String,
        val userAgent: String,
        val clientId: String,
        @Volatile var sessionToken: String? = null
    )

    data class KnownDevice(
        val fingerprint: String,
        @Volatile var label: String,
        @Volatile var role: DeviceRole,
        @Volatile var allowedRoots: MutableList<String>,
        @Volatile var requireDownloadApproval: Boolean,
        @Volatile var requireUploadApproval: Boolean,
        @Volatile var requireDeleteApproval: Boolean,
        @Volatile var requireRenameApproval: Boolean,
        @Volatile var requireMoveApproval: Boolean,
        @Volatile var requireReadApproval: Boolean,
        @Volatile var requireMkdirApproval: Boolean,
        val firstSeenAt: Long,
        @Volatile var lastSeenAt: Long,
        @Volatile var lastIp: String,
        @Volatile var lastUserAgent: String,
    )

    data class PendingApproval(
        val id: String,
        val sessionId: String,
        val deviceLabel: String,
        val action: String,
        val path: String,
        val sizeBytes: Long?,
        val createdAt: Long,
        val expiresAt: Long,
        @Volatile var decision: ApprovalDecision? = null,
        @Volatile var consumed: Boolean = false
    )

    data class LogEntry(
        val ts: Long,
        val sessionId: String?,
        val deviceLabel: String,
        val ip: String,
        val method: String,
        val uri: String,
        val status: Int,
        val ms: Long,
        val note: String?
    )

    data class NoteDoc(
        @Volatile var text: String,
        @Volatile var updatedAt: Long,
        @Volatile var updatedBy: String,
    )

    data class DefaultPerms(
        @Volatile var allowedRoots: MutableList<String>,
        @Volatile var requireDownloadApproval: Boolean,
        @Volatile var requireUploadApproval: Boolean,
        @Volatile var requireDeleteApproval: Boolean,
        @Volatile var requireRenameApproval: Boolean,
        @Volatile var requireMoveApproval: Boolean,
        @Volatile var requireReadApproval: Boolean,
        @Volatile var requireMkdirApproval: Boolean,
    )

    // ---------- State ----------

    private val sessions = ConcurrentHashMap<String, DeviceSession>()
    private val pendingByToken = ConcurrentHashMap<String, PendingAuth>()
    private val pendingByCode = ConcurrentHashMap<String, PendingAuth>()
    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()
    private val rememberedDevices = ConcurrentHashMap<String, KnownDevice>()
    private val deviceNotes = ConcurrentHashMap<String, NoteDoc>()
    private val prefs = ctx.getSharedPreferences("phoneshare_state", Context.MODE_PRIVATE)
    @Volatile private var maxConcurrentSessions: Int = prefs.getInt("max_sessions", 8).coerceIn(1, 99)

    private val defaultGuest = DefaultPerms(
        allowedRoots = prefs.getString("def_guest_roots", "")?.split('\n')
            ?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf(),
        requireDownloadApproval = prefs.getBoolean("def_guest_dl", true),
        requireUploadApproval   = prefs.getBoolean("def_guest_up", true),
        requireDeleteApproval   = prefs.getBoolean("def_guest_del", true),
        requireRenameApproval   = prefs.getBoolean("def_guest_ren", true),
        requireMoveApproval     = prefs.getBoolean("def_guest_mov", true),
        requireReadApproval     = prefs.getBoolean("def_guest_read", true),
        requireMkdirApproval    = prefs.getBoolean("def_guest_mkdir", true),
    )
    private val defaultAdmin = DefaultPerms(
        allowedRoots = mutableListOf("/"),
        requireDownloadApproval = prefs.getBoolean("def_admin_dl", false),
        requireUploadApproval   = prefs.getBoolean("def_admin_up", false),
        requireDeleteApproval   = prefs.getBoolean("def_admin_del", false),
        requireRenameApproval   = prefs.getBoolean("def_admin_ren", false),
        requireMoveApproval     = prefs.getBoolean("def_admin_mov", false),
        requireReadApproval     = prefs.getBoolean("def_admin_read", false),
        requireMkdirApproval    = prefs.getBoolean("def_admin_mkdir", false),
    )

    private val requestLog = ArrayDeque<LogEntry>()
    private val LOG_MAX = 2000
    private val rng = SecureRandom()
    private val lastSessionSaveAt = java.util.concurrent.atomic.AtomicLong(0L)

    // Background ping every 4s keeps WebSocket connections alive against
    // NanoHTTPD's 5s SO_TIMEOUT on accepted sockets.
    private val wsPinger: java.util.concurrent.ScheduledExecutorService = run {
        val exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ws-pinger").apply { isDaemon = true }
        }
        exec.scheduleAtFixedRate({
            try {
                synchronized(sockets) {
                    val it = sockets.iterator()
                    val payload = ByteArray(0)
                    while (it.hasNext()) {
                        val ws = it.next()
                        try { ws.ping(payload) } catch (_: Throwable) {
                            try { it.remove() } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) {}
        }, 4, 4, java.util.concurrent.TimeUnit.SECONDS)
        exec
    }

    init { loadRememberedDevices(); loadNotes(); loadSessions() }

    // ---------- Public API used by phone UI ----------

    fun listSessions(): List<DeviceSession> = sessions.values.sortedByDescending { it.lastSeenAt }
    fun listKnownDevices(): List<KnownDevice> = rememberedDevices.values.sortedByDescending { it.lastSeenAt }
    fun snapshotLogs(): List<LogEntry> { synchronized(requestLog) { return requestLog.toList() } }
    fun listPendingApprovals(): List<PendingApproval> {
        val now = System.currentTimeMillis()
        return pendingApprovals.values
            .filter { it.decision == null && it.expiresAt > now }
            .sortedBy { it.createdAt }
    }
    fun listPendingChallenges(): List<PendingAuth> {
        val now = System.currentTimeMillis()
        return pendingByToken.values
            .filter { it.sessionToken == null && it.expiresAt > now }
            .sortedBy { it.createdAt }
            .distinctBy { it.code }
    }

    fun fingerprintForSession(s: DeviceSession): String? {
        if (s.fingerprint.isNotBlank()) return s.fingerprint
        val match = rememberedDevices.values.firstOrNull {
            it.lastIp == s.ip && it.lastUserAgent == s.userAgent
        }?.fingerprint
        if (match != null) {
            s.fingerprint = match
            saveSessionsThrottled()
        }
        return match
    }

    fun getNoteFor(fingerprint: String): NoteDoc =
        deviceNotes[fingerprint] ?: NoteDoc("", 0L, "phone")

    fun setNoteFromPhone(fingerprint: String, text: String) {
        val now = System.currentTimeMillis()
        deviceNotes[fingerprint] = NoteDoc(text.take(NOTE_MAX_LEN), now, "phone")
        saveNotes()
        broadcastNoteChanged(fingerprint, "phone")
    }

    fun getDefaultPerms(role: DeviceRole): DefaultPerms =
        if (role == DeviceRole.GUEST) defaultGuest else defaultAdmin

    fun setDefaultPerms(
        role: DeviceRole,
        allowedRoots: List<String>?,
        requireDownloadApproval: Boolean?,
        requireUploadApproval: Boolean?,
        requireDeleteApproval: Boolean?,
        requireRenameApproval: Boolean?,
        requireMoveApproval: Boolean?,
        requireReadApproval: Boolean?,
        requireMkdirApproval: Boolean?,
    ) {
        val d = getDefaultPerms(role)
        if (allowedRoots != null && role == DeviceRole.GUEST) d.allowedRoots = allowedRoots.toMutableList()
        if (requireDownloadApproval != null) d.requireDownloadApproval = requireDownloadApproval
        if (requireUploadApproval != null)   d.requireUploadApproval = requireUploadApproval
        if (requireDeleteApproval != null)   d.requireDeleteApproval = requireDeleteApproval
        if (requireRenameApproval != null)   d.requireRenameApproval = requireRenameApproval
        if (requireMoveApproval != null)     d.requireMoveApproval = requireMoveApproval
        if (requireReadApproval != null)     d.requireReadApproval = requireReadApproval
        if (requireMkdirApproval != null)    d.requireMkdirApproval = requireMkdirApproval
        val prefix = if (role == DeviceRole.GUEST) "def_guest_" else "def_admin_"
        val ed = prefs.edit()
        if (role == DeviceRole.GUEST && allowedRoots != null) {
            ed.putString("def_guest_roots", d.allowedRoots.joinToString("\n"))
        }
        ed.putBoolean(prefix + "dl",    d.requireDownloadApproval)
          .putBoolean(prefix + "up",    d.requireUploadApproval)
          .putBoolean(prefix + "del",   d.requireDeleteApproval)
          .putBoolean(prefix + "ren",   d.requireRenameApproval)
          .putBoolean(prefix + "mov",   d.requireMoveApproval)
          .putBoolean(prefix + "read",  d.requireReadApproval)
          .putBoolean(prefix + "mkdir", d.requireMkdirApproval)
          .apply()
    }

    fun getMaxSessions(): Int = maxConcurrentSessions
    fun setMaxSessions(n: Int) {
        maxConcurrentSessions = n.coerceIn(1, 99)
        prefs.edit().putInt("max_sessions", maxConcurrentSessions).apply()
        if (sessions.size > maxConcurrentSessions) {
            val sorted = sessions.values.sortedBy { it.lastSeenAt }
            val toRemove = sessions.size - maxConcurrentSessions
            sorted.take(toRemove).forEach { sessions.remove(it.id) }
            broadcastDevicesChanged()
        }
    }

    fun denyPendingChallenge(token: String): Boolean {
        val p = pendingByToken[token] ?: return false
        if (p.sessionToken != null) return false
        pendingByToken.remove(p.token)
        pendingByCode.remove(p.code)
        ServerLog.warn("Denied stranger code ${p.code} (${p.ip})")
        return true
    }

    fun clearAllDeviceData() {
        try {
            sessions.clear()
            rememberedDevices.clear()
            deviceNotes.clear()
            pendingApprovals.clear()
            pendingByToken.clear()
            pendingByCode.clear()
            synchronized(requestLog) { requestLog.clear() }
            saveSessions()
            saveRememberedDevices()
            saveNotes()
            ServerLog.warn("All device data cleared by user")
            broadcastDevicesChanged()
        } catch (t: Throwable) {
            ServerLog.err("clearAllDeviceData failed: ${t.message}")
        }
    }

    fun forgetDevice(fingerprint: String) {
        try {
            rememberedDevices.remove(fingerprint)
            deviceNotes.remove(fingerprint)
            saveRememberedDevices()
            saveNotes()
            val toKill = sessions.values.filter { sessionFingerprint(it) == fingerprint }
            for (s in toKill) sessions.remove(s.id)
            saveSessions()
            broadcastDevicesChanged()
        } catch (t: Throwable) {
            ServerLog.err("forgetDevice failed: ${t.message}")
        }
    }

    fun revokeSession(sessionId: String) {
        if (sessionId.length < 4) return
        sessions.values.firstOrNull { it.id.startsWith(sessionId) }?.let { sessions.remove(it.id) }
        saveSessions()
        broadcastDevicesChanged()
    }

    fun updateKnownDevice(
        fingerprint: String,
        label: String? = null,
        role: DeviceRole? = null,
        allowedRoots: List<String>? = null,
        requireDownloadApproval: Boolean? = null,
        requireUploadApproval: Boolean? = null,
        requireDeleteApproval: Boolean? = null,
        requireRenameApproval: Boolean? = null,
        requireMoveApproval: Boolean? = null,
        requireReadApproval: Boolean? = null,
        requireMkdirApproval: Boolean? = null,
    ): Boolean {
        val k = rememberedDevices[fingerprint] ?: return false
        if (label != null) k.label = label.take(80)
        if (role != null) k.role = role
        if (allowedRoots != null) k.allowedRoots = allowedRoots.toMutableList()
        if (requireDownloadApproval != null) k.requireDownloadApproval = requireDownloadApproval
        if (requireUploadApproval != null) k.requireUploadApproval = requireUploadApproval
        if (requireDeleteApproval != null) k.requireDeleteApproval = requireDeleteApproval
        if (requireRenameApproval != null) k.requireRenameApproval = requireRenameApproval
        if (requireMoveApproval != null) k.requireMoveApproval = requireMoveApproval
        if (requireReadApproval != null) k.requireReadApproval = requireReadApproval
        if (requireMkdirApproval != null) k.requireMkdirApproval = requireMkdirApproval
        for (s in sessions.values) if (sessionFingerprint(s) == fingerprint) {
            if (label != null) s.label = k.label
            if (role != null) s.role = k.role
            if (allowedRoots != null) s.allowedRoots = k.allowedRoots.toMutableList()
            if (requireDownloadApproval != null) s.requireDownloadApproval = k.requireDownloadApproval
            if (requireUploadApproval != null) s.requireUploadApproval = k.requireUploadApproval
            if (requireDeleteApproval != null) s.requireDeleteApproval = k.requireDeleteApproval
            if (requireRenameApproval != null) s.requireRenameApproval = k.requireRenameApproval
            if (requireMoveApproval != null) s.requireMoveApproval = k.requireMoveApproval
            if (requireReadApproval != null) s.requireReadApproval = k.requireReadApproval
            if (requireMkdirApproval != null) s.requireMkdirApproval = k.requireMkdirApproval
        }
        saveRememberedDevices()
        saveSessions()
        broadcastDevicesChanged()
        broadcastJson("{\"type\":\"me-changed\"}") { ws ->
            val sid = ws.sessionId ?: return@broadcastJson false
            val sess = sessions[sid] ?: return@broadcastJson false
            fingerprintForSession(sess) == fingerprint
        }
        return true
    }

    /** Returns settings remembered for the device behind a pending auth, if any. */
    fun pendingAuthKnownDevice(tokenOrCode: String): KnownDevice? {
        val key = tokenOrCode.trim()
        val pending = pendingByToken[key] ?: pendingByCode[key.uppercase(Locale.US)] ?: return null
        return rememberedDevices[pending.clientId]
    }

    fun pendingAuthDescription(tokenOrCode: String): String? {
        val key = tokenOrCode.trim()
        val pending = pendingByToken[key] ?: pendingByCode[key.uppercase(Locale.US)] ?: return null
        if (System.currentTimeMillis() > pending.expiresAt) return null
        return "Code ${pending.code} - ${deviceLabelFromUA(pending.userAgent, pending.ip)}"
    }

    fun approveAuth(tokenOrCode: String, overrideRole: DeviceRole? = null): AuthResult {
        cleanupExpired()
        val key = tokenOrCode.trim()
        val pending = pendingByToken[key]
            ?: pendingByCode[key.uppercase(Locale.US)]
            ?: return AuthResult.EXPIRED
        if (System.currentTimeMillis() > pending.expiresAt) return AuthResult.EXPIRED
        if (pending.sessionToken == null) {
            val isReturning = rememberedDevices[pending.clientId] != null &&
                sessions.values.any { fingerprintForSession(it) == pending.clientId }
            if (!isReturning && sessions.size >= maxConcurrentSessions) {
                return AuthResult.AT_CAPACITY
            }

            // Drop any prior session for the same fingerprint (browser-refresh dance).
            val priorSessionIds = sessions.values
                .filter { it.fingerprint == pending.clientId }
                .map { it.id }
            for (id in priorSessionIds) sessions.remove(id)

            val stalePending = pendingByToken.values
                .filter { it.clientId == pending.clientId && it !== pending }
            for (p in stalePending) {
                pendingByToken.remove(p.token)
                pendingByCode.remove(p.code)
            }

            val token = randomToken(24)
            pending.sessionToken = token
            val now = System.currentTimeMillis()
            val baseLabel = deviceLabelFromUA(pending.userAgent, pending.ip)
            val known = rememberedDevices[pending.clientId]
            val role = overrideRole ?: known?.role ?: DeviceRole.ADMIN
            val def = getDefaultPerms(role)
            val allowedRoots = known?.allowedRoots?.toMutableList()
                ?: def.allowedRoots.toMutableList().also {
                    if (role == DeviceRole.ADMIN && it.isEmpty()) it.add("/")
                }
            val reqDl    = known?.requireDownloadApproval ?: def.requireDownloadApproval
            val reqUp    = known?.requireUploadApproval   ?: def.requireUploadApproval
            val reqDel   = known?.requireDeleteApproval   ?: def.requireDeleteApproval
            val reqRen   = known?.requireRenameApproval   ?: def.requireRenameApproval
            val reqMov   = known?.requireMoveApproval     ?: def.requireMoveApproval
            val reqRead  = known?.requireReadApproval     ?: def.requireReadApproval
            val reqMkdir = known?.requireMkdirApproval    ?: def.requireMkdirApproval
            val label = known?.label ?: baseLabel

            sessions[token] = DeviceSession(
                id = token,
                fingerprint = pending.clientId,
                label = label,
                createdAt = now,
                lastSeenAt = now,
                expiresAt = now + SESSION_DURATION_MS,
                ip = pending.ip,
                userAgent = pending.userAgent,
                authCode = pending.code,
                role = role,
                allowedRoots = allowedRoots,
                requireDownloadApproval = reqDl,
                requireUploadApproval = reqUp,
                requireDeleteApproval = reqDel,
                requireRenameApproval = reqRen,
                requireMoveApproval = reqMov,
                requireReadApproval = reqRead,
                requireMkdirApproval = reqMkdir,
            )

            val updated = KnownDevice(
                fingerprint = pending.clientId,
                label = label,
                role = role,
                allowedRoots = allowedRoots.toMutableList(),
                requireDownloadApproval = reqDl,
                requireUploadApproval = reqUp,
                requireDeleteApproval = reqDel,
                requireRenameApproval = reqRen,
                requireMoveApproval = reqMov,
                requireReadApproval = reqRead,
                requireMkdirApproval = reqMkdir,
                firstSeenAt = known?.firstSeenAt ?: now,
                lastSeenAt = now,
                lastIp = pending.ip,
                lastUserAgent = pending.userAgent,
            )
            rememberedDevices[pending.clientId] = updated
            saveRememberedDevices()

            val tag = if (known != null) "re-authorized" else "authorized"
            ServerLog.ok("$tag device '$label' as ${role.name.lowercase()} (code ${pending.code})")
            saveSessions()
            broadcastDevicesChanged()
        }
        return AuthResult.APPROVED
    }

    fun decideApproval(approvalId: String, approve: Boolean): Boolean {
        val a = pendingApprovals[approvalId] ?: return false
        if (a.decision != null) return false
        if (System.currentTimeMillis() > a.expiresAt) {
            pendingApprovals.remove(approvalId); return false
        }
        a.decision = if (approve) ApprovalDecision.APPROVED else ApprovalDecision.DENIED
        broadcastApprovalDecision(a)
        cancelApprovalNotification(a.id)
        ServerLog.info("Approval ${if (approve) "GRANTED" else "DENIED"}: ${a.action} ${a.path} for ${a.deviceLabel}")
        return true
    }

    // ---------- WebSocket ----------

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val cookieHeader = handshake.headers["cookie"] ?: ""
        val token = parseCookie(cookieHeader, COOKIE_NAME)
        return RefreshSocket(handshake, token)
    }

    override fun serve(session: IHTTPSession): Response {
        val isWsUpgrade = session.headers["upgrade"]?.equals("websocket", true) == true
        if (isWsUpgrade && currentSession(session) == null) {
            return jsonError(Response.Status.UNAUTHORIZED, "unauthorized")
        }
        return super.serve(session)
    }

    private inner class RefreshSocket(handshake: IHTTPSession, val sessionId: String?) : WebSocket(handshake) {
        init {
            disableSocketTimeout(handshake)
        }
        private fun disableSocketTimeout(h: IHTTPSession) {
            val candidates = listOf("acceptSocket", "socket", "mySocket", "session")
            var cls: Class<*>? = h.javaClass
            while (cls != null) {
                for (name in candidates) {
                    try {
                        val f = cls.getDeclaredField(name)
                        f.isAccessible = true
                        val v = f.get(h)
                        if (v is java.net.Socket) {
                            val before = v.soTimeout
                            v.soTimeout = 0
                            if (before != 0) ServerLog.info("WS keepalive: disabled SO_TIMEOUT (was ${before}ms)")
                            return
                        }
                    } catch (_: NoSuchFieldException) {}
                    catch (_: Throwable) {}
                }
                cls = cls.superclass
            }
        }
        override fun onOpen() { sockets.add(this) }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            sockets.remove(this)
        }
        override fun onMessage(message: WebSocketFrame) {
            try { send("{\"type\":\"ack\"}") } catch (_: IOException) {}
        }
        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException?) { sockets.remove(this) }
    }

    private fun broadcastJson(payload: String, predicate: (RefreshSocket) -> Boolean = { true }) {
        synchronized(sockets) {
            val it = sockets.iterator()
            while (it.hasNext()) {
                val ws = it.next()
                val ok = try { predicate(ws) } catch (_: Throwable) { false }
                if (!ok) continue
                try { ws.send(payload) } catch (_: Throwable) { try { it.remove() } catch (_: Throwable) {} }
            }
        }
    }

    private fun broadcastRefresh(path: String) {
        broadcastJson("{\"type\":\"refresh\",\"path\":${jsonString(path)}}")
    }

    private fun broadcastDevicesChanged() {
        broadcastJson("{\"type\":\"devices-changed\"}") { ws -> ws.sessionId != null }
    }

    private fun broadcastApprovalRequest(a: PendingApproval) {
        val payload = "{\"type\":\"approval-request\",\"approval\":${approvalJson(a)}}"
        broadcastJson(payload) { ws ->
            val sid = ws.sessionId ?: return@broadcastJson false
            sessions[sid]?.role == DeviceRole.ADMIN
        }
    }

    private fun broadcastApprovalDecision(a: PendingApproval) {
        val payload = "{\"type\":\"approval-decision\",\"id\":${jsonString(a.id)}," +
            "\"decision\":${jsonString(a.decision?.name?.lowercase() ?: "pending")}}"
        broadcastJson(payload) { ws -> ws.sessionId == a.sessionId }
    }

    private fun broadcastNoteChanged(fp: String, by: String) {
        val payload = "{\"type\":\"notes-changed\",\"fingerprint\":${jsonString(fp.take(16))}," +
            "\"updatedBy\":${jsonString(by)}}"
        broadcastJson(payload) { ws ->
            val sid = ws.sessionId ?: return@broadcastJson false
            val sess = sessions[sid] ?: return@broadcastJson false
            sess.role == DeviceRole.ADMIN || fingerprintForSession(sess) == fp
        }
    }

    // ---------- HTTP routing ----------

    override fun serveHttp(session: IHTTPSession): Response {
        val started = System.currentTimeMillis()
        val method = session.method?.name ?: "?"
        val uri = try { URLDecoder.decode(session.uri ?: "/", "UTF-8") } catch (_: Exception) { session.uri ?: "/" }
        val resp = try {
            route(session, uri)
        } catch (t: Throwable) {
            ServerLog.err("$method $uri  500  ${t.message}")
            return jsonError(Response.Status.INTERNAL_ERROR, t.message ?: "error")
        }
        val ms = System.currentTimeMillis() - started
        val status = resp.status.requestStatus
        val isQuiet = uri == "/api/auth/status" || uri.startsWith("/api/approvals/poll")
        val current = currentSession(session)
        if (current != null) {
            val now = System.currentTimeMillis()
            current.lastSeenAt = now
            if (current.expiresAt - now < SESSION_DURATION_MS - SESSION_RENEW_MS) {
                current.expiresAt = now + SESSION_DURATION_MS
                saveSessionsThrottled()
            }
        }

        if (uri.startsWith("/api/") || uri == "/download" || uri == "/api/upload") {
            recordLog(LogEntry(
                ts = System.currentTimeMillis(),
                sessionId = current?.id,
                deviceLabel = current?.label ?: "-",
                ip = clientIp(session),
                method = method,
                uri = uri,
                status = status,
                ms = ms,
                note = null
            ))
        }

        val line = "$method $uri  $status  ${ms}ms  <- ${current?.label ?: clientIp(session)}"
        when {
            status >= 500 -> ServerLog.err(line)
            status >= 400 && !isQuiet -> ServerLog.warn(line)
            !isQuiet -> ServerLog.info(line)
            else -> { /* swallow */ }
        }
        return resp
    }

    private fun route(session: IHTTPSession, uri: String): Response {
        when {
            uri == "/" || uri == "/index.html" -> return serveAsset("index.html", "text/html; charset=utf-8")
            uri == "/app.js"     -> return serveAsset("app.js", "application/javascript; charset=utf-8")
            uri == "/style.css"  -> return serveAsset("style.css", "text/css; charset=utf-8")
            uri == "/favicon.ico"-> return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            uri == "/api/auth/challenge" -> return handleAuthChallenge(session)
            uri == "/api/auth/qr"        -> return handleAuthQr(session)
            uri == "/api/auth/status"    -> return handleAuthStatus(session)
            uri == "/api/info"           -> return handleInfo()
            uri == "/auth/r"             -> return handleAuthRedirect(session)
        }
        val s = currentSession(session) ?: return jsonError(Response.Status.UNAUTHORIZED, "unauthorized")
        return when (uri) {
            "/api/me"                -> handleMe(s)
            "/api/list"              -> handleList(session, s)
            "/api/search"            -> handleSearch(session, s)
            "/api/upload"            -> handleUpload(session, s)
            "/api/files/delete"      -> handleDelete(session, s)
            "/api/files/rename"      -> handleRename(session, s)
            "/api/files/move"        -> handleMove(session, s)
            "/api/files/read"        -> handleReadContent(session, s)
            "/api/files/mkdir"       -> handleMkdir(session, s)
            "/download"              -> handleDownload(session, s)
            "/api/devices"           -> handleDevicesList(s)
            "/api/devices/update"    -> handleDeviceUpdate(session, s)
            "/api/devices/revoke"    -> handleDeviceRevoke(session, s)
            "/api/devices/forget"    -> handleDeviceForget(session, s)
            "/api/auth/signout"      -> handleSignout(s)
            "/api/notes/get"         -> handleNotesGet(s)
            "/api/notes/set"         -> handleNotesSet(session, s)
            "/api/approvals/request" -> handleApprovalRequest(session, s)
            "/api/approvals/poll"    -> handleApprovalPoll(session, s)
            "/api/approvals/list"    -> handleApprovalsList(s)
            "/api/approvals/decide"  -> handleApprovalDecide(session, s)
            "/api/logs"              -> handleLogs(session, s)
            else                     -> jsonError(Response.Status.NOT_FOUND, "not found")
        }
    }

    private fun serveAsset(name: String, mime: String): Response {
        val bytes = ctx.assets.open(name).use { it.readBytes() }
        val r = newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
        r.addHeader("Cache-Control", "no-store, must-revalidate")
        r.addHeader("Pragma", "no-cache")
        return r
    }

    // ---------- Files ----------

    private fun handleList(session: IHTTPSession, s: DeviceSession): Response {
        val rel = session.parameters["path"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "/"
        if (!isPathAllowed(s, rel)) return jsonError(Response.Status.FORBIDDEN, "not allowed for this device")
        val dir = resolveSafe(rel) ?: return jsonError(Response.Status.FORBIDDEN, "forbidden")
        if (!dir.exists() || !dir.isDirectory) return jsonError(Response.Status.NOT_FOUND, "not a directory")
        val canonRoot = rootDir.canonicalFile.path
        val canonDir = dir.canonicalFile.path
        val pathRel = canonDir.removePrefix(canonRoot).replace('\\', '/').ifEmpty { "/" }
        val entries = (dir.listFiles() ?: emptyArray()).sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
        val sb = StringBuilder()
        sb.append("{\"path\":").append(jsonString(pathRel))
        sb.append(",\"parent\":")
        if (canonDir == canonRoot) sb.append("null") else {
            val parentRel = (dir.parentFile?.canonicalFile?.path?.removePrefix(canonRoot) ?: "")
                .replace('\\', '/').ifEmpty { "/" }
            sb.append(if (isPathAllowed(s, parentRel)) jsonString(parentRel) else "null")
        }
        sb.append(",\"entries\":[")
        var first = true
        for (f in entries) {
            val childRel = (f.canonicalFile.path.removePrefix(canonRoot)).replace('\\', '/')
            if (!isPathAllowed(s, childRel)) continue
            if (!first) sb.append(',')
            first = false
            sb.append('{')
            sb.append("\"name\":").append(jsonString(f.name))
            sb.append(",\"path\":").append(jsonString(childRel))
            sb.append(",\"dir\":").append(f.isDirectory)
            sb.append(",\"size\":").append(if (f.isDirectory) 0L else f.length())
            sb.append(",\"mtime\":").append(f.lastModified())
            sb.append('}')
        }
        sb.append("]}")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
    }

    private fun handleSearch(session: IHTTPSession, s: DeviceSession): Response {
        val rel = session.parameters["path"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "/"
        val q = session.parameters["q"]?.firstOrNull().orEmpty().trim()
        val deep = session.parameters["deep"]?.firstOrNull() == "1"
        if (q.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "missing query")
        if (!isPathAllowed(s, rel)) return jsonError(Response.Status.FORBIDDEN, "not allowed")
        val dir = resolveSafe(rel) ?: return jsonError(Response.Status.FORBIDDEN, "forbidden")
        if (!dir.exists() || !dir.isDirectory) return jsonError(Response.Status.NOT_FOUND, "not a directory")

        val canonRoot = rootDir.canonicalFile.path
        val needle = q.lowercase(Locale.US)
        val results = mutableListOf<SearchHit>()
        val deadline = System.currentTimeMillis() + 8_000
        val maxResults = 500

        fun walk(d: File, depth: Int) {
            if (results.size >= maxResults) return
            if (System.currentTimeMillis() > deadline) return
            if (depth > 12) return
            val children = d.listFiles() ?: return
            for (f in children) {
                if (results.size >= maxResults) return
                val childRel = (f.canonicalFile.path.removePrefix(canonRoot)).replace('\\', '/')
                if (!isPathAllowed(s, childRel)) continue
                val nameMatch = f.name.lowercase(Locale.US).contains(needle)
                var matchedIn: String? = null
                if (nameMatch) matchedIn = "name"
                else if (deep && f.isFile) matchedIn = matchInsideFile(f, needle)
                if (matchedIn != null) {
                    results.add(SearchHit(
                        name = f.name, path = childRel, dir = f.isDirectory,
                        size = if (f.isDirectory) 0L else f.length(),
                        mtime = f.lastModified(), match = matchedIn
                    ))
                }
                if (f.isDirectory) walk(f, depth + 1)
            }
        }
        walk(dir, 0)

        val sb = StringBuilder("{\"results\":[")
        results.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"name\":").append(jsonString(r.name))
            sb.append(",\"path\":").append(jsonString(r.path))
            sb.append(",\"dir\":").append(r.dir)
            sb.append(",\"size\":").append(r.size)
            sb.append(",\"mtime\":").append(r.mtime)
            sb.append(",\"match\":").append(jsonString(r.match))
            sb.append('}')
        }
        sb.append("],\"truncated\":").append(results.size >= maxResults)
        sb.append("}")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
    }

    private data class SearchHit(
        val name: String, val path: String, val dir: Boolean,
        val size: Long, val mtime: Long, val match: String
    )

    private fun matchInsideFile(f: File, needle: String): String? {
        val name = f.name.lowercase(Locale.US)
        val maxScan = 1L * 1024 * 1024
        try {
            if (name.endsWith(".zip") || name.endsWith(".apk") || name.endsWith(".jar")) {
                ZipFile(f).use { zf ->
                    val it = zf.entries()
                    while (it.hasMoreElements()) {
                        val e = it.nextElement()
                        if (e.name.lowercase(Locale.US).contains(needle)) return "zip:${e.name}"
                    }
                }
                return null
            }
            if (isLikelyTextName(name) && f.length() <= maxScan) {
                val text = f.readText(Charsets.UTF_8)
                if (text.lowercase(Locale.US).contains(needle)) return "content"
            }
        } catch (_: Exception) {}
        return null
    }

    private fun isLikelyTextName(lowerName: String): Boolean {
        val exts = listOf(".txt", ".md", ".log", ".json", ".xml", ".html", ".htm",
            ".css", ".js", ".ts", ".kt", ".java", ".c", ".cpp", ".h", ".py",
            ".rb", ".rs", ".go", ".sh", ".yml", ".yaml", ".toml", ".ini",
            ".cfg", ".env", ".csv", ".tsv", ".srt", ".sub")
        return exts.any { lowerName.endsWith(it) }
    }

    private fun consumeApproval(s: DeviceSession, action: String, path: String, approvalId: String?): Boolean {
        if (approvalId == null) return false
        val a = pendingApprovals[approvalId] ?: return false
        val ok = a.sessionId == s.id
            && a.action == action
            && a.decision == ApprovalDecision.APPROVED
            && !a.consumed
            && System.currentTimeMillis() < a.expiresAt
            && a.path == normalizeRel(path)
        if (!ok) return false
        a.consumed = true
        return true
    }

    private fun handleUpload(session: IHTTPSession, s: DeviceSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val destRel = session.parameters["dest"]?.firstOrNull() ?: "/"
        if (!isPathAllowed(s, destRel)) return jsonError(Response.Status.FORBIDDEN, "destination not allowed")
        val destDir = resolveSafe(destRel) ?: return jsonError(Response.Status.FORBIDDEN, "forbidden")
        if (!destDir.isDirectory) return jsonError(Response.Status.BAD_REQUEST, "destination not a directory")

        if (s.requireUploadApproval) {
            val approvalId = session.parameters["approval"]?.firstOrNull()
            val approval = approvalId?.let { pendingApprovals[it] }
            val approvedDest = normalizeRel(destRel)
            val ok = approval != null
                && approval.sessionId == s.id
                && approval.action == "upload"
                && approval.decision == ApprovalDecision.APPROVED
                && !approval.consumed
                && System.currentTimeMillis() < approval.expiresAt
                && (approval.path == approvedDest
                    || approval.path.startsWith("$approvedDest/"))
            if (!ok) return jsonError(Response.Status.FORBIDDEN, "upload requires approval")
            approval!!.consumed = true
        }

        val tempFiles = HashMap<String, String>()
        try {
            session.parseBody(tempFiles)
        } catch (e: Exception) {
            return jsonError(Response.Status.INTERNAL_ERROR, "upload failed: ${e.message}")
        }

        val saved = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for ((field, tempPath) in tempFiles) {
            val originalNames = session.parameters[field] ?: continue
            val originalName = originalNames.firstOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val src = File(tempPath)
            if (!src.exists() || src.length() == 0L) {
                failed.add(originalName to "empty or unreadable upload buffer")
                continue
            }
            val dest = uniqueChild(destDir, sanitize(originalName))
            try {
                src.inputStream().use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                saved.add(dest.name)
                ServerLog.ok("Uploaded '${dest.name}' (${humanSize(dest.length())}) to $destRel by ${s.label}")
            } catch (e: Exception) {
                try { if (dest.exists()) dest.delete() } catch (_: Exception) {}
                val msg = (e.message ?: e.javaClass.simpleName)
                failed.add(originalName to msg)
                ServerLog.err("Save failed: ${dest.absolutePath}: $msg")
            }
        }

        val canonRoot = rootDir.canonicalFile.path
        val canonDest = destDir.canonicalFile.path
        val pathRel = canonDest.removePrefix(canonRoot).replace('\\', '/').ifEmpty { "/" }
        if (saved.isNotEmpty()) broadcastRefresh(pathRel)

        val sb = StringBuilder("{\"saved\":[")
        saved.forEachIndexed { i, n -> if (i > 0) sb.append(','); sb.append(jsonString(n)) }
        sb.append("],\"failed\":[")
        failed.forEachIndexed { i, (n, m) ->
            if (i > 0) sb.append(',')
            sb.append("{\"name\":").append(jsonString(n))
                .append(",\"error\":").append(jsonString(m)).append('}')
        }
        sb.append("],\"path\":").append(jsonString(pathRel)).append('}')
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
    }

    private fun handleDelete(session: IHTTPSession, s: DeviceSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val path = session.parameters["path"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing path")
        if (!isPathAllowed(s, path)) return jsonError(Response.Status.FORBIDDEN, "not allowed")
        val target = resolveSafe(path) ?: return jsonError(Response.Status.FORBIDDEN, "forbidden")
        if (!target.exists()) return jsonError(Response.Status.NOT_FOUND, "not found")
        if (s.requireDeleteApproval) {
            val approvalId = session.parameters["approval"]?.firstOrNull()
            if (!consumeApproval(s, "delete", path, approvalId))
                return jsonError(Response.Status.FORBIDDEN, "delete requires approval")
        }
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!ok) return jsonError(Response.Status.INTERNAL_ERROR, "delete failed")
        ServerLog.warn("Deleted '${target.absolutePath}' by ${s.label}")
        val parent = target.parentFile?.canonicalFile?.path
            ?.removePrefix(rootDir.canonicalFile.path)?.replace('\\', '/')?.ifEmpty { "/" } ?: "/"
        broadcastRefresh(parent)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", "{\"ok\":true}")
    }

    private fun handleRename(session: IHTTPSession, s: DeviceSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val path = session.parameters["path"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing path")
        val newName = session.parameters["newName"]?.firstOrNull()?.let { sanitize(it) }
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing newName")
        if (newName.isBlank() || newName.contains('/') || newName.contains('\\'))
            return jsonError(Response.Status.BAD_REQUEST, "invalid name")
        if (!isPathAllowed(s, path)) return jsonError(Response.Status.FORBIDDEN, "not allowed")
        val target = resolveSafe(path) ?: return jsonError(Response.Status.FORBIDDEN, "forbidden")
        if (!target.exists()) return jsonError(Response.Status.NOT_FOUND, "not found")
        if (s.requireRenameApproval) {
            val approvalId = session.parameters["approval"]?.firstOrNull()
            if (!consumeApproval(s, "rename", path, approvalId))
                return jsonError(Response.Status.FORBIDDEN, "rename requires approval")
        }
        val parent = target.parentFile ?: return jsonError(Response.Status.INTERNAL_ERROR, "no parent")
        val dest = File(parent, newName)
        if (dest.exists()) return jsonError(Response.Status.CONFLICT, "name already exists")
        if (!target.renameTo(dest)) return jsonError(Response.Status.INTERNAL_ERROR, "rename failed")
        ServerLog.info("Renamed '${target.name}' -> '${dest.name}' in ${parent.absolutePath} by ${s.label}")
        val parentRel = parent.canonicalFile.path
            .removePrefix(rootDir.canonicalFile.path).replace('\\', '/').ifEmpty { "/" }
        broadcastRefresh(parentRel)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
            "{\"ok\":true,\"newName\":${jsonString(dest.name)}}")
    }

    private fun handleMove(session: IHTTPSession, s: DeviceSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val path = session.parameters["path"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing path")
        val destDir = session.parameters["dest"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing dest")
        if (!isPathAllowed(s, path)) return jsonError(Response.Status.FORBIDDEN, "source not allowed")
        if (!isPathAllowed(s, destDir)) return jsonError(Response.Status.FORBIDDEN, "destination not allowed")
        val src = resolveSafe(path) ?: return jsonError(Response.Status.FORBIDDEN, "forbidden")
        if (!src.exists()) return jsonError(Response.Status.NOT_FOUND, "source not found")
        val dst = resolveSafe(destDir) ?: return jsonError(Response.Status.FORBIDDEN, "forbidden")
        if (!dst.isDirectory) return jsonError(Response.Status.BAD_REQUEST, "destination is not a directory")

        if (s.requireMoveApproval) {
            val approvalId = session.parameters["approval"]?.firstOrNull()
            if (!consumeApproval(s, "move", path, approvalId))
                return jsonError(Response.Status.FORBIDDEN, "move requires approval")
        }
        val target = uniqueChild(dst, src.name)
        if (!src.renameTo(target)) return jsonError(Response.Status.INTERNAL_ERROR, "move failed (different filesystem?)")
        ServerLog.info("Moved '${src.name}' -> '${target.absolutePath}' by ${s.label}")
        val canonRoot = rootDir.canonicalFile.path
        val srcParentRel = (src.parentFile?.canonicalFile?.path ?: "")
            .removePrefix(canonRoot).replace('\\', '/').ifEmpty { "/" }
        val dstRel = (dst.canonicalFile.path)
            .removePrefix(canonRoot).replace('\\', '/').ifEmpty { "/" }
        broadcastRefresh(srcParentRel)
        if (srcParentRel != dstRel) broadcastRefresh(dstRel)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
            "{\"ok\":true,\"newName\":${jsonString(target.name)},\"dest\":${jsonString(dstRel)}}")
    }

    private fun handleReadContent(session: IHTTPSession, s: DeviceSession): Response {
        val path = session.parameters["path"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing path")
        if (!isPathAllowed(s, path)) return jsonError(Response.Status.FORBIDDEN, "not allowed")
        val f = resolveSafe(path) ?: return jsonError(Response.Status.FORBIDDEN, "forbidden")
        if (!f.isFile) return jsonError(Response.Status.NOT_FOUND, "not a file")
        val maxBytes = 256L * 1024
        if (f.length() > maxBytes) return jsonError(Response.Status.PAYLOAD_TOO_LARGE,
            "file too large to preview (>${maxBytes / 1024} KB)")
        if (s.requireReadApproval) {
            val approvalId = session.parameters["approval"]?.firstOrNull()
            if (!consumeApproval(s, "read", path, approvalId))
                return jsonError(Response.Status.FORBIDDEN, "read requires approval")
        }
        val text = try { f.readText(Charsets.UTF_8) } catch (e: Exception) {
            return jsonError(Response.Status.INTERNAL_ERROR, "read failed: ${e.message}")
        }
        ServerLog.info("Read content '${f.name}' (${humanSize(f.length())}) by ${s.label}")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
            "{\"name\":${jsonString(f.name)},\"size\":${f.length()},\"text\":${jsonString(text)}}")
    }

    private fun handleMkdir(session: IHTTPSession, s: DeviceSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val parentRel = session.parameters["path"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing path")
        val name = session.parameters["name"]?.firstOrNull()?.let { sanitize(it) }
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing name")
        if (name.isBlank() || name.contains('/') || name.contains('\\'))
            return jsonError(Response.Status.BAD_REQUEST, "invalid name")
        if (!isPathAllowed(s, parentRel)) return jsonError(Response.Status.FORBIDDEN, "not allowed")
        val parent = resolveSafe(parentRel) ?: return jsonError(Response.Status.FORBIDDEN, "forbidden")
        if (!parent.isDirectory) return jsonError(Response.Status.BAD_REQUEST, "parent not a directory")
        val newRel = normalizeRel(parentRel) + (if (parentRel == "/") "" else "/") + name
        if (s.requireMkdirApproval) {
            val approvalId = session.parameters["approval"]?.firstOrNull()
            if (!consumeApproval(s, "mkdir", newRel, approvalId))
                return jsonError(Response.Status.FORBIDDEN, "mkdir requires approval")
        }
        val target = File(parent, name)
        if (target.exists()) return jsonError(Response.Status.CONFLICT, "name already exists")
        if (!target.mkdir()) return jsonError(Response.Status.INTERNAL_ERROR, "mkdir failed")
        ServerLog.info("Created folder '${target.absolutePath}' by ${s.label}")
        broadcastRefresh(parentRel)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
            "{\"ok\":true,\"name\":${jsonString(name)}}")
    }

    private fun handleDownload(session: IHTTPSession, s: DeviceSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: ""
        if (!isPathAllowed(s, path)) return jsonError(Response.Status.FORBIDDEN, "not allowed")
        val f = resolveSafe(path) ?: return jsonError(Response.Status.FORBIDDEN, "forbidden")
        if (!f.isFile) return jsonError(Response.Status.NOT_FOUND, "not found")

        if (s.requireDownloadApproval) {
            val approvalId = session.parameters["approval"]?.firstOrNull()
            val approval = approvalId?.let { pendingApprovals[it] }
            val ok = approval != null
                && approval.sessionId == s.id
                && approval.action == "download"
                && approval.decision == ApprovalDecision.APPROVED
                && !approval.consumed
                && System.currentTimeMillis() < approval.expiresAt
                && approval.path == normalizeRel(path)
            if (!ok) return jsonError(Response.Status.FORBIDDEN, "download requires approval")
            approval!!.consumed = true
        }

        val r = streamFile(f, session)
        r.addHeader("Content-Disposition", "attachment; filename=\"${f.name.replace("\"", "")}\"")
        return r
    }

    private fun streamFile(f: File, session: IHTTPSession): Response {
        val mime = mimeFor(f.name)
        val total = f.length()
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val parts = range.removePrefix("bytes=").split("-")
            val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = parts.getOrNull(1)?.toLongOrNull() ?: (total - 1)
            if (start in 0 until total && end < total && start <= end) {
                val length = end - start + 1
                val fis = FileInputStream(f)
                fis.channel.position(start)
                val resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, length)
                resp.addHeader("Content-Range", "bytes $start-$end/$total")
                resp.addHeader("Accept-Ranges", "bytes")
                return resp
            }
        }
        val resp = newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), total)
        resp.addHeader("Accept-Ranges", "bytes")
        return resp
    }

    // ---------- Auth handlers ----------

    private fun currentSession(session: IHTTPSession): DeviceSession? {
        cleanupExpired()
        val cookieHeader = session.headers["cookie"] ?: return null
        val token = parseCookie(cookieHeader, COOKIE_NAME) ?: return null
        val s = sessions[token] ?: return null
        if (s.expiresAt <= System.currentTimeMillis()) {
            sessions.remove(token); return null
        }
        return s
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { it.value.expiresAt <= now }
        val expiredAuth = pendingByToken.values.filter { it.expiresAt <= now }
        for (p in expiredAuth) {
            pendingByToken.remove(p.token)
            pendingByCode.remove(p.code)
        }
        pendingApprovals.entries.removeAll {
            val a = it.value
            (a.decision == null && a.expiresAt <= now) ||
            (a.decision != null && now - a.createdAt > 5L * 60 * 1000)
        }
    }

    private fun handleInfo(): Response {
        val name = deviceName()
        val body = "{\"device\":${jsonString(name)}}"
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
    }

    private fun handleMe(s: DeviceSession): Response {
        val sb = StringBuilder("{")
        sb.append("\"id\":").append(jsonString(s.id.take(8)))
        sb.append(",\"label\":").append(jsonString(s.label))
        sb.append(",\"role\":").append(jsonString(s.role.name.lowercase()))
        sb.append(",\"allowedRoots\":[")
        s.allowedRoots.forEachIndexed { i, r -> if (i > 0) sb.append(','); sb.append(jsonString(r)) }
        sb.append("],\"requireDownloadApproval\":").append(s.requireDownloadApproval)
        sb.append(",\"requireUploadApproval\":").append(s.requireUploadApproval)
        sb.append(",\"requireDeleteApproval\":").append(s.requireDeleteApproval)
        sb.append(",\"requireRenameApproval\":").append(s.requireRenameApproval)
        sb.append(",\"requireMoveApproval\":").append(s.requireMoveApproval)
        sb.append(",\"requireReadApproval\":").append(s.requireReadApproval)
        sb.append(",\"requireMkdirApproval\":").append(s.requireMkdirApproval)
        sb.append(",\"isStranger\":").append(s.isStranger)
        sb.append("}")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
    }

    private fun deviceName(): String {
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        val model = android.os.Build.MODEL ?: ""
        val combined = if (model.startsWith(manufacturer, ignoreCase = true)) model
                       else "$manufacturer $model".trim()
        return combined.ifBlank { "Android" }
    }

    private fun deviceLabelFromUA(ua: String, ip: String): String {
        if (ua.isBlank()) return "Device - $ip"
        val name = when {
            ua.contains("Edg/", true)                     -> "Edge"
            ua.contains("OPR/", true) || ua.contains("Opera", true) -> "Opera"
            ua.contains("Chrome/", true)                  -> "Chrome"
            ua.contains("Firefox/", true)                 -> "Firefox"
            ua.contains("Safari/", true)                  -> "Safari"
            else                                          -> "Browser"
        }
        val os = when {
            ua.contains("Windows", true)                  -> "Windows"
            ua.contains("Macintosh", true) || ua.contains("Mac OS", true) -> "macOS"
            ua.contains("Android", true)                  -> "Android"
            ua.contains("iPhone", true) || ua.contains("iPad", true)      -> "iOS"
            ua.contains("Linux", true)                    -> "Linux"
            else                                          -> ""
        }
        return if (os.isNotBlank()) "$name on $os - $ip" else "$name - $ip"
    }

    private fun handleAuthChallenge(session: IHTTPSession): Response {
        cleanupExpired()
        val now = System.currentTimeMillis()
        val ua = session.headers["user-agent"].orEmpty()
        val ip = clientIp(session)
        val clientId = (session.parameters["clientId"]?.firstOrNull()
            ?.takeIf { it.length in 8..128 })
            ?: ("ip:" + ip + ":" + ua.hashCode().toString(16))

        val reusable = pendingByToken.values.firstOrNull {
            it.clientId == clientId
                && it.sessionToken == null
                && now - it.createdAt < CODE_REUSE_MS
                && now < it.expiresAt
        }
        val auth = if (reusable != null) {
            reusable
        } else {
            val stale = pendingByToken.values.filter { it.clientId == clientId }
            for (p in stale) {
                pendingByToken.remove(p.token)
                pendingByCode.remove(p.code)
            }
            val token = randomToken(24)
            val code = randomCode()
            val fresh = PendingAuth(token, code, now, now + PENDING_DURATION_MS, ip, ua, clientId)
            pendingByToken[token] = fresh
            pendingByCode[code] = fresh
            fresh
        }

        val known = rememberedDevices[clientId]
        val sb = StringBuilder("{")
        sb.append("\"token\":").append(jsonString(auth.token))
        sb.append(",\"code\":").append(jsonString(auth.code))
        sb.append(",\"expiresInMs\":").append((auth.expiresAt - now).coerceAtLeast(0))
        if (known != null) {
            sb.append(",\"known\":true")
            sb.append(",\"knownLabel\":").append(jsonString(known.label))
            sb.append(",\"knownRole\":").append(jsonString(known.role.name.lowercase()))
        } else {
            sb.append(",\"known\":false")
        }
        sb.append('}')
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
    }

    private fun handleAuthQr(session: IHTTPSession): Response {
        val token = session.parameters["token"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing token")
        val pending = pendingByToken[token]
            ?: return jsonError(Response.Status.NOT_FOUND, "expired or unknown token")
        if (System.currentTimeMillis() > pending.expiresAt) {
            return jsonError(Response.Status.GONE, "token expired")
        }
        val host = (session.headers["host"]?.takeIf { it.isNotBlank() })
            ?: "127.0.0.1:8080"
        val payload = "http://" + host + "/auth/r?c=" +
            URLEncoder.encode(pending.code, "UTF-8")
        val png = generateQrPng(payload, 560)
        val resp = newFixedLengthResponse(
            Response.Status.OK, "image/png", ByteArrayInputStream(png), png.size.toLong()
        )
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun handleAuthRedirect(session: IHTTPSession): Response {
        val code = session.parameters["c"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing code")
        val target = "phoneshare://auth?code=" + URLEncoder.encode(code, "UTF-8")
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <title>PhoneShare</title>
            <meta http-equiv="refresh" content="0;url=$target">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0e0e12;color:#eef;text-align:center;padding:60px 20px;}
            a{color:#6c8cff;font-size:18px;text-decoration:none;display:inline-block;margin-top:16px;padding:12px 24px;border:1px solid #6c8cff;border-radius:8px;}
            h2{color:#6c8cff;font-family:monospace;}.code{font-family:monospace;font-size:32px;color:#fff;letter-spacing:6px;margin:14px 0;}</style>
            </head><body>
            <h2>&gt;_ PhoneShare</h2>
            <p>Opening the app...</p>
            <div class="code">$code</div>
            <a href="$target">Tap to open PhoneShare</a>
            <script>setTimeout(function(){location.href='$target';},80);</script>
            </body></html>
        """.trimIndent()
        val resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun handleAuthStatus(session: IHTTPSession): Response {
        cleanupExpired()
        val token = session.parameters["token"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing token")
        val pending = pendingByToken[token]
            ?: return newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8",
                "{\"status\":\"expired\"}"
            )
        if (System.currentTimeMillis() > pending.expiresAt) {
            pendingByToken.remove(pending.token)
            pendingByCode.remove(pending.code)
            return newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8",
                "{\"status\":\"expired\"}"
            )
        }
        val sessionToken = pending.sessionToken
        if (sessionToken != null) {
            pendingByToken.remove(pending.token)
            pendingByCode.remove(pending.code)
            val resp = newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8",
                "{\"status\":\"approved\"}"
            )
            val maxAgeSeconds = SESSION_DURATION_MS / 1000
            resp.addHeader(
                "Set-Cookie",
                "$COOKIE_NAME=$sessionToken; Path=/; Max-Age=$maxAgeSeconds; HttpOnly; SameSite=Lax"
            )
            return resp
        }
        return newFixedLengthResponse(
            Response.Status.OK, "application/json; charset=utf-8",
            "{\"status\":\"pending\"}"
        )
    }

    private fun handleSignout(s: DeviceSession): Response {
        sessions.remove(s.id)
        saveSessions()
        ServerLog.info("Signed out '${s.label}' (own session)")
        broadcastDevicesChanged()
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", "{\"ok\":true}")
        resp.addHeader("Set-Cookie", "$COOKIE_NAME=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax")
        return resp
    }

    // ---------- Devices management ----------

    private fun handleDevicesList(s: DeviceSession): Response {
        if (s.role != DeviceRole.ADMIN) return jsonError(Response.Status.FORBIDDEN, "admin only")
        val active = sessions.values.sortedByDescending { it.lastSeenAt }
        val activeFps = active.mapNotNull { sessionFingerprint(it) }.toSet()
        val offline = rememberedDevices.values
            .filter { it.fingerprint !in activeFps }
            .sortedByDescending { it.lastSeenAt }
        val sb = StringBuilder("{\"devices\":[")
        var first = true
        for (d in active) {
            if (!first) sb.append(',') else first = false
            sb.append(deviceJson(d, isMe = d.id == s.id))
        }
        for (k in offline) {
            if (!first) sb.append(',') else first = false
            sb.append(knownDeviceJson(k))
        }
        sb.append("]}")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
    }

    private fun handleDeviceUpdate(session: IHTTPSession, s: DeviceSession): Response {
        if (s.role != DeviceRole.ADMIN) return jsonError(Response.Status.FORBIDDEN, "admin only")
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val files = HashMap<String, String>()
        try { session.parseBody(files) } catch (_: Exception) {}
        val id = session.parameters["id"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing id")
        if (id.length < 4) return jsonError(Response.Status.BAD_REQUEST, "id too short")

        val activeTarget = sessions.values.firstOrNull { it.id.startsWith(id) }
        val knownTarget = activeTarget?.let { sessionFingerprint(it) }
            ?: rememberedDevices.values.firstOrNull { it.fingerprint.startsWith(id) }?.fingerprint

        val newLabel = session.parameters["label"]?.firstOrNull()
        val newRole = session.parameters["role"]?.firstOrNull()?.let {
            if (it.equals("guest", true)) DeviceRole.GUEST else DeviceRole.ADMIN
        }
        val newRoots = session.parameters["allowedRoots"]?.firstOrNull()?.let { raw ->
            raw.split('\n').map { it.trim().trim('/') }.filter { it.isNotBlank() }.map { "/$it" }
        }
        val newReqDl = session.parameters["requireDownloadApproval"]?.firstOrNull()?.let { it == "1" || it.equals("true", true) }
        val newReqUp = session.parameters["requireUploadApproval"]?.firstOrNull()?.let { it == "1" || it.equals("true", true) }
        val newReqDel = session.parameters["requireDeleteApproval"]?.firstOrNull()?.let { it == "1" || it.equals("true", true) }
        val newReqRen = session.parameters["requireRenameApproval"]?.firstOrNull()?.let { it == "1" || it.equals("true", true) }
        val newReqMov = session.parameters["requireMoveApproval"]?.firstOrNull()?.let { it == "1" || it.equals("true", true) }
        val newReqRead = session.parameters["requireReadApproval"]?.firstOrNull()?.let { it == "1" || it.equals("true", true) }
        val newReqMkdir = session.parameters["requireMkdirApproval"]?.firstOrNull()?.let { it == "1" || it.equals("true", true) }

        if (activeTarget != null) {
            if (newLabel != null) activeTarget.label = newLabel.take(80)
            if (newRole  != null) activeTarget.role = newRole
            if (newRoots != null) activeTarget.allowedRoots = newRoots.toMutableList()
            if (newReqDl != null) activeTarget.requireDownloadApproval = newReqDl
            if (newReqUp != null) activeTarget.requireUploadApproval = newReqUp
            if (newReqDel != null) activeTarget.requireDeleteApproval = newReqDel
            if (newReqRen != null) activeTarget.requireRenameApproval = newReqRen
            if (newReqMov != null) activeTarget.requireMoveApproval = newReqMov
            if (newReqRead != null) activeTarget.requireReadApproval = newReqRead
            if (newReqMkdir != null) activeTarget.requireMkdirApproval = newReqMkdir
        }
        if (knownTarget != null) {
            updateKnownDevice(knownTarget,
                label = newLabel,
                role = newRole,
                allowedRoots = newRoots,
                requireDownloadApproval = newReqDl,
                requireUploadApproval = newReqUp,
                requireDeleteApproval = newReqDel,
                requireRenameApproval = newReqRen,
                requireMoveApproval = newReqMov,
                requireReadApproval = newReqRead,
                requireMkdirApproval = newReqMkdir,
            )
        }
        if (activeTarget == null && knownTarget == null) {
            return jsonError(Response.Status.NOT_FOUND, "no such device")
        }
        broadcastDevicesChanged()
        val payload = activeTarget?.let { deviceJson(it, isMe = it.id == s.id) }
            ?: knownDeviceJson(rememberedDevices[knownTarget]!!)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
            "{\"ok\":true,\"device\":$payload}")
    }

    private fun handleDeviceRevoke(session: IHTTPSession, s: DeviceSession): Response {
        if (s.role != DeviceRole.ADMIN) return jsonError(Response.Status.FORBIDDEN, "admin only")
        val id = session.parameters["id"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing id")
        if (id.length < 4) return jsonError(Response.Status.BAD_REQUEST, "id too short")
        val target = sessions.values.firstOrNull { it.id.startsWith(id) }
            ?: return jsonError(Response.Status.NOT_FOUND, "no such device")
        sessions.remove(target.id)
        ServerLog.warn("Revoked device '${target.label}' (${target.authCode})")
        broadcastDevicesChanged()
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", "{\"ok\":true}")
    }

    private fun handleDeviceForget(session: IHTTPSession, s: DeviceSession): Response {
        if (s.role != DeviceRole.ADMIN) return jsonError(Response.Status.FORBIDDEN, "admin only")
        val id = session.parameters["id"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing id")
        if (id.length < 4) return jsonError(Response.Status.BAD_REQUEST, "id too short")
        val active = sessions.values.firstOrNull { it.id.startsWith(id) }
        val fp = active?.let { sessionFingerprint(it) }
            ?: rememberedDevices.values.firstOrNull { it.fingerprint.startsWith(id) }?.fingerprint
            ?: return jsonError(Response.Status.NOT_FOUND, "no such device")
        forgetDevice(fp)
        ServerLog.warn("Forgot device '${rememberedDevices[fp]?.label ?: fp}'")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", "{\"ok\":true}")
    }

    // ---------- Notes ----------

    private fun handleNotesGet(s: DeviceSession): Response {
        val fp = fingerprintForSession(s) ?: return jsonError(Response.Status.NOT_FOUND, "no fingerprint")
        val doc = deviceNotes[fp] ?: NoteDoc("", 0L, "device")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
            "{\"text\":${jsonString(doc.text)},\"updatedAt\":${doc.updatedAt},\"updatedBy\":${jsonString(doc.updatedBy)}}")
    }

    private fun handleNotesSet(session: IHTTPSession, s: DeviceSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val files = HashMap<String, String>()
        try { session.parseBody(files) } catch (_: Exception) {}
        val text = session.parameters["text"]?.firstOrNull() ?: ""
        val fp = fingerprintForSession(s) ?: return jsonError(Response.Status.NOT_FOUND, "no fingerprint")
        val now = System.currentTimeMillis()
        deviceNotes[fp] = NoteDoc(text.take(NOTE_MAX_LEN), now, "device")
        saveNotes()
        broadcastNoteChanged(fp, "device")
        sendNativeNoteUpdated(fp, s.label)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
            "{\"ok\":true,\"updatedAt\":$now}")
    }

    // ---------- Approvals ----------

    private fun handleApprovalRequest(session: IHTTPSession, s: DeviceSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val files = HashMap<String, String>()
        try { session.parseBody(files) } catch (_: Exception) {}
        val action = session.parameters["action"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing action")
        val path = session.parameters["path"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing path")
        val size = session.parameters["size"]?.firstOrNull()?.toLongOrNull()
        if (action !in listOf("download", "upload", "delete", "rename", "move", "read", "mkdir")) {
            return jsonError(Response.Status.BAD_REQUEST, "bad action")
        }

        val needs = (action == "download" && s.requireDownloadApproval) ||
                    (action == "upload"   && s.requireUploadApproval)   ||
                    (action == "delete"   && s.requireDeleteApproval)   ||
                    (action == "rename"   && s.requireRenameApproval)   ||
                    (action == "move"     && s.requireMoveApproval)     ||
                    (action == "read"     && s.requireReadApproval)     ||
                    (action == "mkdir"    && s.requireMkdirApproval)
        if (!needs) {
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                "{\"required\":false}")
        }
        if (!isPathAllowed(s, path)) return jsonError(Response.Status.FORBIDDEN, "path not allowed")

        val now = System.currentTimeMillis()
        val id = randomToken(12)
        val approval = PendingApproval(
            id = id, sessionId = s.id, deviceLabel = s.label,
            action = action, path = normalizeRel(path), sizeBytes = size,
            createdAt = now, expiresAt = now + APPROVAL_DURATION_MS
        )
        pendingApprovals[id] = approval
        broadcastApprovalRequest(approval)
        sendNativeApprovalIntent(approval)
        ServerLog.warn("Approval REQUESTED: ${approval.action} ${approval.path} from ${approval.deviceLabel} (id ${id.take(8)})")

        val sb = StringBuilder("{\"required\":true,\"id\":")
            .append(jsonString(id))
            .append(",\"expiresInMs\":").append(APPROVAL_DURATION_MS)
            .append('}')
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
    }

    private fun handleApprovalPoll(session: IHTTPSession, s: DeviceSession): Response {
        val id = session.parameters["id"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing id")
        val a = pendingApprovals[id]
            ?: return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                "{\"status\":\"unknown\"}")
        if (a.sessionId != s.id) return jsonError(Response.Status.FORBIDDEN, "not yours")
        if (System.currentTimeMillis() > a.expiresAt && a.decision == null) {
            pendingApprovals.remove(id)
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                "{\"status\":\"expired\"}")
        }
        val status = when (a.decision) {
            ApprovalDecision.APPROVED -> "approved"
            ApprovalDecision.DENIED -> "denied"
            null -> "pending"
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
            "{\"status\":${jsonString(status)}}")
    }

    private fun handleApprovalsList(s: DeviceSession): Response {
        if (s.role != DeviceRole.ADMIN) return jsonError(Response.Status.FORBIDDEN, "admin only")
        val pending = pendingApprovals.values.filter { it.decision == null && System.currentTimeMillis() < it.expiresAt }
            .sortedByDescending { it.createdAt }
        val sb = StringBuilder("{\"approvals\":[")
        pending.forEachIndexed { i, a ->
            if (i > 0) sb.append(',')
            sb.append(approvalJson(a))
        }
        sb.append("]}")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
    }

    private fun handleApprovalDecide(session: IHTTPSession, s: DeviceSession): Response {
        if (s.role != DeviceRole.ADMIN) return jsonError(Response.Status.FORBIDDEN, "admin only")
        val id = session.parameters["id"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing id")
        val approve = session.parameters["approve"]?.firstOrNull() == "1"
        val ok = decideApproval(id, approve)
        return newFixedLengthResponse(if (ok) Response.Status.OK else Response.Status.NOT_FOUND,
            "application/json; charset=utf-8", "{\"ok\":$ok}")
    }

    private fun sendNativeApprovalIntent(a: PendingApproval) {
        try {
            val intent = Intent(ACTION_APPROVAL_REQUEST).apply {
                setPackage(ctx.packageName)
                putExtra("id", a.id)
                putExtra("action", a.action)
                putExtra("path", a.path)
                putExtra("device", a.deviceLabel)
                putExtra("size", a.sizeBytes ?: -1L)
            }
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
        } catch (_: Throwable) {}
        try { showApprovalNotification(a) } catch (_: Throwable) {}
    }

    private fun showApprovalNotification(a: PendingApproval) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = APPROVAL_CHANNEL_ID
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                channelId, "Approval requests",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when a connected device asks for download/upload approval"
                enableVibration(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(ch)
        }

        val piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            android.app.PendingIntent.FLAG_IMMUTABLE

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openPi = android.app.PendingIntent.getActivity(ctx, a.id.hashCode(), openIntent, piFlags)

        val approveIntent = Intent(ctx, ApprovalActionReceiver::class.java).apply {
            action = ApprovalActionReceiver.ACTION_APPROVE
            setPackage(ctx.packageName)
            putExtra("id", a.id)
        }
        val approvePi = android.app.PendingIntent.getBroadcast(
            ctx, ("a:" + a.id).hashCode(), approveIntent, piFlags)

        val denyIntent = Intent(ctx, ApprovalActionReceiver::class.java).apply {
            action = ApprovalActionReceiver.ACTION_DENY
            setPackage(ctx.packageName)
            putExtra("id", a.id)
        }
        val denyPi = android.app.PendingIntent.getBroadcast(
            ctx, ("d:" + a.id).hashCode(), denyIntent, piFlags)

        val verb = if (a.action == "upload") "upload to" else "download"
        val sizeNote = if ((a.sizeBytes ?: 0L) > 0L) " (${humanSize(a.sizeBytes!!)})" else ""
        val text = "${a.deviceLabel} -> $verb ${a.path}$sizeNote"

        val notif = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_notif_approval)
            .setContentTitle("Approve ${a.action}?")
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setTimeoutAfter(APPROVAL_DURATION_MS)
            .addAction(android.R.drawable.ic_menu_send, "Approve", approvePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Deny", denyPi)
            .build()

        nm.notify(ApprovalActionReceiver.notifIdFor(a.id), notif)
    }

    private fun cancelApprovalNotification(id: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(ApprovalActionReceiver.notifIdFor(id))
        } catch (_: Throwable) {}
    }

    private fun sendNativeNoteUpdated(fp: String, deviceLabel: String) {
        try {
            val intent = Intent(ACTION_NOTE_UPDATED).apply {
                setPackage(ctx.packageName)
                putExtra("fingerprint", fp)
                putExtra("device", deviceLabel)
            }
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    // ---------- Logs ----------

    private fun recordLog(e: LogEntry) {
        synchronized(requestLog) {
            requestLog.addLast(e)
            while (requestLog.size > LOG_MAX) requestLog.removeFirst()
        }
    }

    private fun handleLogs(session: IHTTPSession, s: DeviceSession): Response {
        val deviceFilter = session.parameters["device"]?.firstOrNull()
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, LOG_MAX) ?: 300
        val isAdmin = s.role == DeviceRole.ADMIN

        val snapshot: List<LogEntry>
        synchronized(requestLog) { snapshot = requestLog.toList() }

        val filtered = snapshot.asReversed().asSequence()
            .filter { entry ->
                if (!isAdmin && entry.sessionId != s.id) return@filter false
                if (deviceFilter != null && deviceFilter != "all") {
                    val sid = entry.sessionId ?: return@filter false
                    if (!sid.startsWith(deviceFilter)) return@filter false
                }
                true
            }
            .take(limit)
            .toList()

        val sb = StringBuilder("{\"logs\":[")
        filtered.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"ts\":").append(e.ts)
            sb.append(",\"device\":").append(jsonString(e.deviceLabel))
            sb.append(",\"sessionId\":").append(if (e.sessionId == null) "null" else jsonString(e.sessionId.take(8)))
            sb.append(",\"ip\":").append(jsonString(e.ip))
            sb.append(",\"method\":").append(jsonString(e.method))
            sb.append(",\"uri\":").append(jsonString(e.uri))
            sb.append(",\"status\":").append(e.status)
            sb.append(",\"ms\":").append(e.ms)
            sb.append('}')
        }
        sb.append("]}")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
    }

    // ---------- JSON serializers ----------

    private fun deviceJson(d: DeviceSession, isMe: Boolean): String {
        val sb = StringBuilder("{")
        sb.append("\"id\":").append(jsonString(d.id.take(8)))
        sb.append(",\"fingerprint\":").append(jsonString((sessionFingerprint(d) ?: "").take(16)))
        sb.append(",\"label\":").append(jsonString(d.label))
        sb.append(",\"ip\":").append(jsonString(d.ip))
        sb.append(",\"code\":").append(jsonString(d.authCode))
        sb.append(",\"online\":true")
        sb.append(",\"createdAt\":").append(d.createdAt)
        sb.append(",\"lastSeenAt\":").append(d.lastSeenAt)
        sb.append(",\"expiresAt\":").append(d.expiresAt)
        sb.append(",\"role\":").append(jsonString(d.role.name.lowercase()))
        sb.append(",\"isMe\":").append(isMe)
        sb.append(",\"requireDownloadApproval\":").append(d.requireDownloadApproval)
        sb.append(",\"requireUploadApproval\":").append(d.requireUploadApproval)
        sb.append(",\"requireDeleteApproval\":").append(d.requireDeleteApproval)
        sb.append(",\"requireRenameApproval\":").append(d.requireRenameApproval)
        sb.append(",\"requireMoveApproval\":").append(d.requireMoveApproval)
        sb.append(",\"requireReadApproval\":").append(d.requireReadApproval)
        sb.append(",\"requireMkdirApproval\":").append(d.requireMkdirApproval)
        sb.append(",\"allowedRoots\":[")
        d.allowedRoots.forEachIndexed { i, r -> if (i > 0) sb.append(','); sb.append(jsonString(r)) }
        sb.append(']')
        sb.append("}")
        return sb.toString()
    }

    private fun knownDeviceJson(k: KnownDevice): String {
        val sb = StringBuilder("{")
        sb.append("\"id\":").append(jsonString(k.fingerprint.take(16)))
        sb.append(",\"fingerprint\":").append(jsonString(k.fingerprint.take(16)))
        sb.append(",\"label\":").append(jsonString(k.label))
        sb.append(",\"ip\":").append(jsonString(k.lastIp))
        sb.append(",\"code\":").append(jsonString(""))
        sb.append(",\"online\":false")
        sb.append(",\"createdAt\":").append(k.firstSeenAt)
        sb.append(",\"lastSeenAt\":").append(k.lastSeenAt)
        sb.append(",\"expiresAt\":0")
        sb.append(",\"role\":").append(jsonString(k.role.name.lowercase()))
        sb.append(",\"isMe\":false")
        sb.append(",\"requireDownloadApproval\":").append(k.requireDownloadApproval)
        sb.append(",\"requireUploadApproval\":").append(k.requireUploadApproval)
        sb.append(",\"requireDeleteApproval\":").append(k.requireDeleteApproval)
        sb.append(",\"requireRenameApproval\":").append(k.requireRenameApproval)
        sb.append(",\"requireMoveApproval\":").append(k.requireMoveApproval)
        sb.append(",\"requireReadApproval\":").append(k.requireReadApproval)
        sb.append(",\"requireMkdirApproval\":").append(k.requireMkdirApproval)
        sb.append(",\"allowedRoots\":[")
        k.allowedRoots.forEachIndexed { i, r -> if (i > 0) sb.append(','); sb.append(jsonString(r)) }
        sb.append(']')
        sb.append("}")
        return sb.toString()
    }

    private fun approvalJson(a: PendingApproval): String {
        val sb = StringBuilder("{")
        sb.append("\"id\":").append(jsonString(a.id))
        sb.append(",\"deviceLabel\":").append(jsonString(a.deviceLabel))
        sb.append(",\"sessionId\":").append(jsonString(a.sessionId.take(8)))
        sb.append(",\"action\":").append(jsonString(a.action))
        sb.append(",\"path\":").append(jsonString(a.path))
        sb.append(",\"size\":").append(a.sizeBytes ?: -1)
        sb.append(",\"createdAt\":").append(a.createdAt)
        sb.append(",\"expiresAt\":").append(a.expiresAt)
        sb.append("}")
        return sb.toString()
    }

    // ---------- Persistence ----------

    private fun saveSessionsThrottled() {
        val now = System.currentTimeMillis()
        val prev = lastSessionSaveAt.get()
        if (now - prev < 60_000) return
        if (!lastSessionSaveAt.compareAndSet(prev, now)) return
        saveSessions()
    }

    private fun saveSessions() {
        val sb = StringBuilder("[")
        var first = true
        for (s in sessions.values) {
            if (!first) sb.append(',') else first = false
            sb.append('{')
            sb.append("\"id\":").append(jsonString(s.id))
            sb.append(",\"fingerprint\":").append(jsonString(s.fingerprint))
            sb.append(",\"label\":").append(jsonString(s.label))
            sb.append(",\"createdAt\":").append(s.createdAt)
            sb.append(",\"lastSeenAt\":").append(s.lastSeenAt)
            sb.append(",\"expiresAt\":").append(s.expiresAt)
            sb.append(",\"ip\":").append(jsonString(s.ip))
            sb.append(",\"userAgent\":").append(jsonString(s.userAgent))
            sb.append(",\"authCode\":").append(jsonString(s.authCode))
            sb.append(",\"role\":").append(jsonString(s.role.name))
            sb.append(",\"requireDownloadApproval\":").append(s.requireDownloadApproval)
            sb.append(",\"requireUploadApproval\":").append(s.requireUploadApproval)
            sb.append(",\"requireDeleteApproval\":").append(s.requireDeleteApproval)
            sb.append(",\"requireRenameApproval\":").append(s.requireRenameApproval)
            sb.append(",\"requireMoveApproval\":").append(s.requireMoveApproval)
            sb.append(",\"requireReadApproval\":").append(s.requireReadApproval)
            sb.append(",\"requireMkdirApproval\":").append(s.requireMkdirApproval)
            sb.append(",\"allowedRoots\":[")
            s.allowedRoots.forEachIndexed { i, r -> if (i > 0) sb.append(','); sb.append(jsonString(r)) }
            sb.append(']')
            sb.append('}')
        }
        sb.append(']')
        prefs.edit().putString("sessions", sb.toString()).apply()
    }

    private fun loadSessions() {
        val raw = prefs.getString("sessions", null) ?: return
        try {
            val arr = org.json.JSONArray(raw)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val expiresAt = o.optLong("expiresAt", 0L)
                if (expiresAt <= now) continue
                val role = try { DeviceRole.valueOf(o.optString("role", "ADMIN")) } catch (_: Exception) { DeviceRole.ADMIN }
                val rootsArr = o.optJSONArray("allowedRoots")
                val roots = mutableListOf<String>()
                if (rootsArr != null) for (j in 0 until rootsArr.length()) roots.add(rootsArr.getString(j))
                val id = o.getString("id")
                sessions[id] = DeviceSession(
                    id = id,
                    fingerprint = o.optString("fingerprint", ""),
                    label = o.optString("label", "Device"),
                    createdAt = o.optLong("createdAt", now),
                    lastSeenAt = o.optLong("lastSeenAt", now),
                    expiresAt = expiresAt,
                    ip = o.optString("ip", ""),
                    userAgent = o.optString("userAgent", ""),
                    authCode = o.optString("authCode", ""),
                    role = role,
                    allowedRoots = roots,
                    requireDownloadApproval = o.optBoolean("requireDownloadApproval", false),
                    requireUploadApproval = o.optBoolean("requireUploadApproval", false),
                    requireDeleteApproval = o.optBoolean("requireDeleteApproval", true),
                    requireRenameApproval = o.optBoolean("requireRenameApproval", true),
                    requireMoveApproval = o.optBoolean("requireMoveApproval", true),
                    requireReadApproval = o.optBoolean("requireReadApproval", false),
                    requireMkdirApproval = o.optBoolean("requireMkdirApproval", true),
                )
            }
            ServerLog.info("Restored ${sessions.size} session(s) from disk")
        } catch (e: Exception) {
            ServerLog.warn("Failed to load sessions: ${e.message}")
        }
    }

    private fun saveRememberedDevices() {
        val sb = StringBuilder("[")
        var first = true
        for (k in rememberedDevices.values) {
            if (!first) sb.append(',') else first = false
            sb.append('{')
            sb.append("\"fingerprint\":").append(jsonString(k.fingerprint))
            sb.append(",\"label\":").append(jsonString(k.label))
            sb.append(",\"role\":").append(jsonString(k.role.name))
            sb.append(",\"requireDownloadApproval\":").append(k.requireDownloadApproval)
            sb.append(",\"requireUploadApproval\":").append(k.requireUploadApproval)
            sb.append(",\"requireDeleteApproval\":").append(k.requireDeleteApproval)
            sb.append(",\"requireRenameApproval\":").append(k.requireRenameApproval)
            sb.append(",\"requireMoveApproval\":").append(k.requireMoveApproval)
            sb.append(",\"requireReadApproval\":").append(k.requireReadApproval)
            sb.append(",\"requireMkdirApproval\":").append(k.requireMkdirApproval)
            sb.append(",\"firstSeenAt\":").append(k.firstSeenAt)
            sb.append(",\"lastSeenAt\":").append(k.lastSeenAt)
            sb.append(",\"lastIp\":").append(jsonString(k.lastIp))
            sb.append(",\"lastUserAgent\":").append(jsonString(k.lastUserAgent))
            sb.append(",\"allowedRoots\":[")
            k.allowedRoots.forEachIndexed { i, r -> if (i > 0) sb.append(','); sb.append(jsonString(r)) }
            sb.append(']')
            sb.append('}')
        }
        sb.append(']')
        prefs.edit().putString("known_devices", sb.toString()).apply()
    }

    private fun loadRememberedDevices() {
        val raw = prefs.getString("known_devices", null) ?: return
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rolesStr = o.optString("role", "ADMIN")
                val role = try { DeviceRole.valueOf(rolesStr) } catch (_: Exception) { DeviceRole.ADMIN }
                val rootsArr = o.optJSONArray("allowedRoots")
                val roots = mutableListOf<String>()
                if (rootsArr != null) for (j in 0 until rootsArr.length()) roots.add(rootsArr.getString(j))
                val fp = o.getString("fingerprint")
                rememberedDevices[fp] = KnownDevice(
                    fingerprint = fp,
                    label = o.optString("label", "Device"),
                    role = role,
                    allowedRoots = roots,
                    requireDownloadApproval = o.optBoolean("requireDownloadApproval", false),
                    requireUploadApproval = o.optBoolean("requireUploadApproval", false),
                    requireDeleteApproval = o.optBoolean("requireDeleteApproval", true),
                    requireRenameApproval = o.optBoolean("requireRenameApproval", true),
                    requireMoveApproval = o.optBoolean("requireMoveApproval", true),
                    requireReadApproval = o.optBoolean("requireReadApproval", false),
                    requireMkdirApproval = o.optBoolean("requireMkdirApproval", true),
                    firstSeenAt = o.optLong("firstSeenAt", 0L),
                    lastSeenAt = o.optLong("lastSeenAt", 0L),
                    lastIp = o.optString("lastIp", ""),
                    lastUserAgent = o.optString("lastUserAgent", ""),
                )
            }
        } catch (e: Exception) {
            ServerLog.warn("Failed to load remembered devices: ${e.message}")
        }
    }

    private fun saveNotes() {
        val sb = StringBuilder("{")
        var first = true
        for ((fp, d) in deviceNotes) {
            if (!first) sb.append(',') else first = false
            sb.append(jsonString(fp)).append(":{")
            sb.append("\"text\":").append(jsonString(d.text))
            sb.append(",\"updatedAt\":").append(d.updatedAt)
            sb.append(",\"updatedBy\":").append(jsonString(d.updatedBy))
            sb.append('}')
        }
        sb.append('}')
        prefs.edit().putString("device_notes", sb.toString()).apply()
    }

    private fun loadNotes() {
        val raw = prefs.getString("device_notes", null) ?: return
        try {
            val o = org.json.JSONObject(raw)
            val keys = o.keys()
            while (keys.hasNext()) {
                val fp = keys.next()
                val d = o.getJSONObject(fp)
                deviceNotes[fp] = NoteDoc(
                    text = d.optString("text", ""),
                    updatedAt = d.optLong("updatedAt", 0L),
                    updatedBy = d.optString("updatedBy", "device"),
                )
            }
        } catch (e: Exception) { ServerLog.warn("Failed to load notes: ${e.message}") }
    }

    // ---------- Helpers ----------

    private fun sessionFingerprint(s: DeviceSession): String? = fingerprintForSession(s)

    private fun isPathAllowed(s: DeviceSession, relPath: String): Boolean {
        if (s.role == DeviceRole.ADMIN) return true
        val target = normalizeRel(relPath)
        if (s.allowedRoots.isEmpty()) return false
        return s.allowedRoots.any { root ->
            val r = normalizeRel(root)
            r == "/" || target == r || target.startsWith("$r/")
        }
    }

    private fun normalizeRel(p: String): String {
        val cleaned = "/" + p.trim().trim('/').replace('\\', '/')
        if (cleaned == "/") return "/"
        return cleaned.replace(Regex("/+"), "/")
    }

    private fun resolveSafe(rel: String): File? {
        val cleaned = rel.trimStart('/')
        val target = if (cleaned.isEmpty()) rootDir else File(rootDir, cleaned)
        val canonRoot = rootDir.canonicalFile
        val canonTarget = try { target.canonicalFile } catch (_: Exception) { return null }
        if (!canonTarget.path.startsWith(canonRoot.path)) return null
        return canonTarget
    }

    private fun uniqueChild(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    private fun sanitize(name: String): String {
        val n = name.replace('\\', '/').substringAfterLast('/')
        return n.replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_").ifBlank { "upload.bin" }
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun humanSize(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    private fun parseCookie(header: String, name: String): String? {
        for (part in header.split(';')) {
            val kv = part.trim()
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            if (kv.substring(0, eq) == name) return kv.substring(eq + 1)
        }
        return null
    }

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        rng.nextBytes(buf)
        return android.util.Base64.encodeToString(
            buf, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    private fun randomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder(6)
        repeat(6) { sb.append(alphabet[rng.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    private fun generateQrPng(text: String, size: Int): ByteArray {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 2
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                pixels[off + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun jsonError(status: Response.Status, msg: String): Response {
        val body = "{\"error\":${jsonString(msg)}}"
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body)
    }

    private fun clientIp(session: IHTTPSession): String =
        session.headers["x-forwarded-for"] ?: session.headers["remote-addr"] ?: "?"

    companion object {
        private const val COOKIE_NAME = "phoneshare_session"
        private const val SESSION_DURATION_MS = 30L * 24 * 60 * 60 * 1000
        private const val SESSION_RENEW_MS    = 24L * 60 * 60 * 1000
        private const val PENDING_DURATION_MS = 5L * 60 * 1000
        private const val CODE_REUSE_MS       = 60L * 1000
        private const val APPROVAL_DURATION_MS = 2L * 60 * 1000
        const val ACTION_APPROVAL_REQUEST = "com.phoneshare.app.APPROVAL_REQUEST"
        const val ACTION_NOTE_UPDATED     = "com.phoneshare.app.NOTE_UPDATED"
        private const val NOTE_MAX_LEN = 64 * 1024
        private const val APPROVAL_CHANNEL_ID = "approval_requests"
    }
}
