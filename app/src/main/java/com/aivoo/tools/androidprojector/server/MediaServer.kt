package app.aivoo.tools.androidprojector.server

import android.content.Context
import android.os.StatFs
import android.util.Log
import app.aivoo.tools.androidprojector.model.UploadedFile
import app.aivoo.tools.androidprojector.util.HttpsConfig
import app.aivoo.tools.androidprojector.util.MediaTypeDetector
import app.aivoo.tools.androidprojector.util.PathUtils
import app.aivoo.tools.androidprojector.util.RangeParser
import app.aivoo.tools.androidprojector.util.UrlUtils
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.SecureRandom

class MediaServer(
    private val context: Context,
    private val port: Int = 7071,
    private val onUpload: (UploadedFile) -> Unit,
    private val onListenChanged: (Boolean) -> Unit = {},
    private val onPdfCommand: (String) -> Unit = {},
    private val onVideoCommand: (String) -> Unit = {},
    private val onFilesChanged: () -> Unit = {},
    private val onSessionChanged: (Boolean) -> Unit = {},
    private val onScreenShareStart: () -> Unit = {},
    private val onScreenShareOffer: (String) -> String? = { null },
    private val onScreenShareStop: () -> Unit = {},
    private val onScreenShareStatus: () -> String = { "unavailable" },
) : NanoHTTPD(port) {

    init {
        makeSecure(HttpsConfig.getSocketFactory(context), arrayOf("TLSv1.2"))
    }

    private data class RemoteSession(
        val id: String,
        val clientId: String,
        val createdAt: Long,
        var lastSeen: Long,
    )

    private sealed interface ClaimResult {
        data class Ok(val sessionId: String) : ClaimResult
        data object Busy : ClaimResult
        data object BadPin : ClaimResult
        data object Blocked : ClaimResult
    }

    companion object {
        private const val TAG = "MediaServer"
        private const val MAX_UPLOAD_BYTES = 512L * 1024 * 1024 // 512 MB
        private const val MAX_RTC_OFFER_BYTES = 64L * 1024
        private const val MIN_FREE_BYTES = 64L * 1024 * 1024 // 64 MB headroom
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "ts")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif")

        private fun generatePin(): String {
            val value = SecureRandom().nextInt(1_000_000)
            return String.format(java.util.Locale.ROOT, "%06d", value)
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }

    val pairingPin: String = generatePin()

    @Volatile var currentFile: File? = null
    @Volatile var currentPositionMs: Long = 0L
    @Volatile var currentPlaying: Boolean = false
    @Volatile var pdfPage: Int = 0
    @Volatile var pdfPageCount: Int = 0
    @Volatile var videoDurationMs: Long = 0L
    @Volatile var videoEventId: Long = 1
    @Volatile private var listenerCount: Int = 0
    @Volatile private var activeSession: RemoteSession? = null
    @Volatile private var screenShareActive: Boolean = false
    @Volatile private var pinAttempts: Int = 0
    @Volatile private var pinBlockedUntil: Long = 0L
    private val sessionLock = Any()
    private val uploadedFiles = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val sessionTimeoutMs = 15_000L
    private val sessionCookieName = "tv_session"
    private val sessionCookiePath = "/"

    private fun clearExpiredSession(): Boolean {
        val expired = synchronized(sessionLock) {
            val current = activeSession ?: return@synchronized false
            if (current.lastSeen + sessionTimeoutMs < System.currentTimeMillis()) {
                activeSession = null
                true
            } else {
                false
            }
        }
        if (expired) {
            stopScreenShare()
            onSessionChanged(false)
        }
        return expired
    }

    fun hasActiveSession(): Boolean {
        clearExpiredSession()
        return activeSession != null
    }

    fun endSession() {
        val hadSession = synchronized(sessionLock) {
            val had = activeSession != null
            activeSession = null
            had
        }
        stopScreenShare()
        if (hadSession) onSessionChanged(false)
    }

    private fun claimSession(clientId: String, pin: String): ClaimResult {
        clearExpiredSession()
        val now = System.currentTimeMillis()
        if (now < pinBlockedUntil) return ClaimResult.Blocked
        if (!constantTimeEquals(pin, pairingPin)) {
            pinAttempts += 1
            if (pinAttempts >= 5) {
                pinAttempts = 0
                pinBlockedUntil = now + 30_000L
                return ClaimResult.Blocked
            }
            return ClaimResult.BadPin
        }
        pinAttempts = 0
        pinBlockedUntil = 0L
        return synchronized(sessionLock) {
            val current = activeSession
            if (current != null && current.lastSeen + sessionTimeoutMs >= System.currentTimeMillis()) {
                if (current.clientId == clientId) {
                    current.lastSeen = System.currentTimeMillis()
                    return@synchronized ClaimResult.Ok(current.id)
                }
                return@synchronized ClaimResult.Busy
            }
            val session = RemoteSession(
                id = java.util.UUID.randomUUID().toString(),
                clientId = clientId,
                createdAt = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
            )
            activeSession = session
            onSessionChanged(true)
            ClaimResult.Ok(session.id)
        }
    }

    private fun heartbeat(sessionId: String): Boolean {
        if (clearExpiredSession()) return false
        return synchronized(sessionLock) {
            val current = activeSession ?: return@synchronized false
            if (current.id != sessionId) return@synchronized false
            current.lastSeen = System.currentTimeMillis()
            true
        }
    }

    private fun releaseSession(sessionId: String): Boolean {
        val released = synchronized(sessionLock) {
            val current = activeSession ?: return@synchronized false
            if (current.id != sessionId) return@synchronized false
            activeSession = null
            onSessionChanged(false)
            true
        }
        if (released) stopScreenShare()
        return released
    }

    private fun requireSession(session: IHTTPSession): Boolean {
        if (clearExpiredSession()) return false
        val sessionId = session.cookies.read(sessionCookieName) ?: return false
        return synchronized(sessionLock) {
            val current = activeSession ?: return@synchronized false
            if (current.id != sessionId) return@synchronized false
            current.lastSeen = System.currentTimeMillis()
            true
        }
    }

    private fun stopScreenShare() {
        val wasActive = synchronized(sessionLock) {
            val active = screenShareActive
            screenShareActive = false
            active
        }
        if (wasActive) onScreenShareStop()
    }

    private fun cookieHeader(sessionId: String): String {
        return "$sessionCookieName=$sessionId; Path=$sessionCookiePath; HttpOnly; Secure; SameSite=Strict"
    }

    private fun clearCookieHeader(): String {
        return "$sessionCookieName=; Path=$sessionCookiePath; Max-Age=0; Secure; SameSite=Strict"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val route = uri.ifEmpty { "/" }

        if (route == "/favicon.ico") {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        }
        if (route == "/" && method == Method.GET) {
            return serveRoot(session)
        }
        if (route == "/app" && method == Method.GET) {
            if (!requireSession(session)) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Session required")
            }
            return serveApp()
        }
        if (route == "/session/claim" && method == Method.POST) {
            return handleSessionClaim(session)
        }
        if (route == "/session/heartbeat" && method == Method.POST) {
            return handleSessionHeartbeat(session)
        }
        if (route == "/session" && method == Method.DELETE) {
            return handleSessionDelete(session)
        }
        if (route == "/session" && method == Method.GET) {
            return handleSessionStatus()
        }

        if (method == Method.POST && route == "/upload") {
            if (session.headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                return newFixedLengthResponse(Response.Status.LENGTH_REQUIRED, "text/plain", "Content-Length required")
            }
            val contentLength = session.headers["content-length"]?.toLongOrNull()
                ?: return newFixedLengthResponse(Response.Status.LENGTH_REQUIRED, "text/plain", "Content-Length required")
            if (contentLength > MAX_UPLOAD_BYTES) {
                return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, "text/plain", "File too large (max 512 MB)")
            }
            val available = StatFs(context.cacheDir.absolutePath).availableBytes
            if (contentLength > available - MIN_FREE_BYTES) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Not enough free storage")
            }
        }

        if (!requireSession(session)) {
            return newFixedLengthResponse(Response.Status.CONFLICT, "text/plain", "Session required")
        }

        Log.d(TAG, "$method $route")
        return try {
            when {
                route == "/upload" && method == Method.POST -> handleUpload(session)
                route == "/url" && method == Method.POST -> handleUrl(session)
                route.startsWith("/media/") && (method == Method.GET || method == Method.HEAD) -> serveMedia(session, route.removePrefix("/media/"))
                route == "/nowplaying" && method == Method.GET -> serveNowPlaying()
                route == "/pdf/state" && method == Method.GET -> servePdfState()
                route == "/pdf/command" && method == Method.POST -> handlePdfCommand(session)
                route == "/video/state" && method == Method.GET -> serveVideoState()
                route == "/video/command" && method == Method.POST -> handleVideoCommand(session)
                route == "/files" && method == Method.GET -> serveFiles()
                route == "/files/delete" && method == Method.POST -> handleDelete(session)
                route == "/files/clear" && method == Method.POST -> handleClear()
                route == "/listen/start" && method == Method.POST -> handleListen(true)
                route == "/listen/stop" && method == Method.POST -> handleListen(false)
                route == "/rtc/offer" && method == Method.POST -> handleRtcOffer(session)
                route == "/rtc/stop" && method == Method.POST -> handleRtcStop()
                route == "/rtc/status" && method == Method.GET -> handleRtcStatus()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal error")
        }
    }

    private fun handleSessionClaim(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        try {
            session.parseBody(files)
        } catch (_: Exception) {
        }
        val clientId = session.parameters["clientId"]?.firstOrNull()?.trim()
        val pin = session.parameters["pin"]?.firstOrNull()?.trim()
        if (clientId.isNullOrBlank() || pin.isNullOrBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "clientId and pin required")
        }
        return when (val result = claimSession(clientId, pin)) {
            is ClaimResult.Ok -> {
                val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                response.addHeader("Set-Cookie", cookieHeader(result.sessionId))
                response
            }
            ClaimResult.Busy -> newFixedLengthResponse(Response.Status.CONFLICT, "text/plain", "session_in_progress")
            ClaimResult.BadPin -> newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "invalid_pin")
            ClaimResult.Blocked -> newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, "text/plain", "too_many_attempts")
        }
    }

    private fun handleSessionHeartbeat(session: IHTTPSession): Response {
        val sessionId = session.cookies.read(sessionCookieName)
        if (sessionId == null || !heartbeat(sessionId)) {
            return newFixedLengthResponse(Response.Status.GONE, "text/plain", "session_expired")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
    }

    private fun handleSessionDelete(session: IHTTPSession): Response {
        val sessionId = session.cookies.read(sessionCookieName)
        val released = sessionId != null && releaseSession(sessionId)
        val response = newFixedLengthResponse(
            if (released) Response.Status.OK else Response.Status.GONE,
            "text/plain",
            if (released) "released" else "session_expired",
        )
        response.addHeader("Set-Cookie", clearCookieHeader())
        return response
    }

    private fun handleSessionStatus(): Response {
        val active = hasActiveSession()
        val json = "{\"active\":$active}"
        val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun handleRtcOffer(session: IHTTPSession): Response {
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength > MAX_RTC_OFFER_BYTES) {
            return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, "text/plain", "Offer too large")
        }
        val offer = try {
            String(session.inputStream.readBytes(), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Unable to read offer")
        }
        if (offer.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "offer required")
        }
        val busy = synchronized(sessionLock) {
            if (screenShareActive) {
                true
            } else {
                screenShareActive = true
                false
            }
        }
        if (busy) {
            return newFixedLengthResponse(Response.Status.CONFLICT, "text/plain", "screen_share_in_progress")
        }
        try {
            onScreenShareStart()
            val answer = onScreenShareOffer(offer)
            if (answer.isNullOrBlank()) {
                val detail = onScreenShareStatus()
                Log.e(TAG, "Screen share answer was null/blank; stage=" + detail)
                stopScreenShare()
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Unable to answer: " + detail)
            }
            val json = JSONObject().put("answer", answer).toString()
            val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
            response.addHeader("Cache-Control", "no-store")
            return response
        } catch (e: Exception) {
            Log.e(TAG, "screen share failed", e)
            stopScreenShare()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Screen share failed")
        }
    }

    private fun handleRtcStatus(): Response {
        val status = onScreenShareStatus()
        val json = JSONObject()
            .put("active", screenShareActive)
            .put("status", status)
            .toString()
        val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun handleRtcStop(): Response {
        stopScreenShare()
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "stopped")
    }

    private fun serveRoot(session: IHTTPSession): Response {
        if (requireSession(session)) {
            val redirect = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "Redirecting")
            redirect.addHeader("Location", "app")
            return redirect
        }
        if (hasActiveSession()) {
            return serveBusy()
        }
        return serveLanding()
    }

    private fun serveBusy(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <meta name="referrer" content="no-referrer" />
              <title>Android TV Projector</title>
              <style>
                body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center; background:#0b1020; color:#e2e8f0; font-family:system-ui,-apple-system,sans-serif; padding:24px; }
                .card { background:rgba(30,41,59,.85); border:1px solid rgba(148,163,184,.2); border-radius:18px; padding:28px; max-width:420px; width:100%; text-align:center; }
                button { margin-top:14px; width:100%; padding:12px; border:none; border-radius:10px; background:#6366f1; color:white; font-size:15px; cursor:pointer; }
                .hint { color:#94a3b8; font-size:13px; margin-top:10px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>Android TV Projector</h1>
                <p class="hint">TV is in use by another device.</p>
                <p class="hint">Ask the other device to leave the session, then retry.</p>
                <button onclick="location.href='.'">Retry</button>
              </div>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Referrer-Policy", "no-referrer")
        }
    }

    private fun serveLanding(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <meta name="referrer" content="no-referrer" />
              <title>Android TV Projector</title>
              <style>
                body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center; background:#0b1020; color:#e2e8f0; font-family:system-ui,-apple-system,sans-serif; padding:24px; }
                .card { background:rgba(30,41,59,.85); border:1px solid rgba(148,163,184,.2); border-radius:18px; padding:28px; max-width:420px; width:100%; text-align:center; }
                input { width:100%; margin-top:14px; padding:12px; border-radius:10px; border:1px solid rgba(148,163,184,.25); background:#0f172a; color:#e2e8f0; font-size:22px; text-align:center; letter-spacing:6px; }
                button { margin-top:14px; width:100%; padding:12px; border:none; border-radius:10px; background:#6366f1; color:white; font-size:15px; cursor:pointer; }
                .hint { color:#94a3b8; font-size:13px; margin-top:10px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>Android TV Projector</h1>
                <p class="hint">Enter the 6-digit pairing code shown on the TV.</p>
                <input id="pin" inputmode="numeric" maxlength="6" pattern="[0-9]{6}" />
                <button onclick="claim()">Pair</button>
                <div id="msg" class="hint"></div>
              </div>
              <script>
                const clientId = sessionStorage.getItem('tv_client') || (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random());
                sessionStorage.setItem('tv_client', clientId);
                function setMsg(t){ document.getElementById('msg').textContent = t; }
                async function claim(){
                  const pin = document.getElementById('pin').value.trim();
                  if (!/^[0-9]{6}$/.test(pin)) { setMsg('Enter the 6-digit pairing code.'); return; }
                  try {
                    const res = await fetch('session/claim', {method:'POST', body:new URLSearchParams({clientId, pin})});
                    if (res.ok) { location.replace('app'); return; }
                    if (res.status === 409) { setMsg('TV is in use by another device.'); return; }
                    if (res.status === 401) { setMsg('Invalid pairing code.'); return; }
                    if (res.status === 429) { setMsg('Too many attempts. Wait 30 seconds.'); return; }
                    throw new Error('HTTP ' + res.status);
                  } catch(e) { setMsg('Unable to connect: ' + e); }
                }
              </script>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Referrer-Policy", "no-referrer")
        }
    }

    private fun serveApp(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <meta name="referrer" content="no-referrer" />
              <title>Android TV Projector</title>
              <style>
                :root { --bg:#0b1020; --card:rgba(30,41,59,.75); --accent:#6366f1; --accent2:#22d3ee; --text:#e2e8f0; --muted:#94a3b8; --border:rgba(148,163,184,.15); }
                * { box-sizing: border-box; }
                body { margin:0; font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; background: radial-gradient(1200px 600px at 20% 10%, #1e293b, #0b1020 60%); color: var(--text); min-height:100vh; display:flex; align-items:center; justify-content:center; padding:24px; }
                .card { background: var(--card); backdrop-filter: blur(12px); border:1px solid var(--border); border-radius:20px; padding:36px; max-width:620px; width:100%; box-shadow:0 25px 70px rgba(0,0,0,.5); animation: fadeIn .4s ease; }
                @keyframes fadeIn { from { opacity:0; transform: translateY(10px);} to {opacity:1; transform:none;} }
                h1 { margin:0 0 4px; font-size:30px; background: linear-gradient(90deg,var(--accent),var(--accent2)); -webkit-background-clip:text; background-clip:text; color:transparent; }
                .sub { color: var(--muted); margin:0 0 24px; font-size:14px; }
                .modes { display:flex; gap:8px; margin-bottom:20px; flex-wrap:wrap; }
                .mode { flex:1; min-width:90px; padding:10px 8px; border-radius:10px; border:1px solid var(--border); background:rgba(15,23,42,.6); color:var(--muted); cursor:pointer; text-align:center; font-weight:600; font-size:13px; transition:all .2s; user-select:none; }
                .mode:hover { border-color: var(--accent); color: var(--text); }
                .mode.active { background: linear-gradient(135deg,var(--accent),#4f46e5); color:#fff; border-color:transparent; box-shadow:0 6px 18px rgba(99,102,241,.4); }
                .panel { display:none; animation: fadeIn .3s ease; }
                .panel.active { display:block; }
                label { display:block; margin:14px 0 6px; font-weight:600; font-size:13px; color:var(--muted); text-transform:uppercase; letter-spacing:.5px; }
                input[type=file], input[type=text] { width:100%; padding:12px 14px; border-radius:10px; border:1px solid var(--border); background:rgba(15,23,42,.8); color:var(--text); font-size:14px; transition:border .2s; }
                input:focus { outline:none; border-color: var(--accent); }
                button { margin-top:16px; width:100%; padding:13px; border:none; border-radius:10px; background:linear-gradient(135deg,var(--accent),#4f46e5); color:#fff; font-size:15px; font-weight:600; cursor:pointer; transition:transform .1s, box-shadow .2s; box-shadow:0 6px 18px rgba(99,102,241,.35); }
                button:hover { transform:translateY(-1px); box-shadow:0 8px 22px rgba(99,102,241,.5); }
                button:active { transform:translateY(0); }
                .btn-row { display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-top:16px; }
                .btn-row button { margin-top:0; }
                .btn-green { background:linear-gradient(135deg,#10b981,#059669); box-shadow:0 6px 18px rgba(16,185,129,.35); }
                .btn-red { background:linear-gradient(135deg,#ef4444,#dc2626); box-shadow:0 6px 18px rgba(239,68,68,.35); }
                .hint { font-size:12px; color:var(--muted); margin-top:8px; }
                .status { margin-top:18px; padding:12px 14px; border-radius:10px; background:rgba(15,23,42,.8); color:var(--muted); font-size:13px; border:1px solid var(--border); min-height:40px; }
                audio { width:100%; margin-top:12px; border-radius:10px; }
                .footer { text-align:center; color:var(--muted); font-size:12px; margin-top:20px; }
                .footer a { color:var(--accent2); text-decoration:none; }
                .footer a:hover { text-decoration:underline; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>Android TV Projector</h1>
                <p class="sub">Upload a file, open a URL, or control the TV remotely.</p>
                <div class="modes">
                  <div class="mode active" data-mode="upload">Upload</div>
                  <div class="mode" data-mode="url">URL</div>
                  <div class="mode" data-mode="pdf">PDF</div>
                  <div class="mode" data-mode="video">Video</div>
                  <div class="mode" data-mode="files">Files</div>
                  <div class="mode" data-mode="share">Share</div>
                </div>

                <div class="panel active" id="panel-upload">
                  <form id="uploadForm">
                    <label for="file">Choose file</label>
                    <input type="file" id="file" name="file" required />
                    <div class="hint">Max 512 MB. PDF, video, image. Other files are shared as downloads.</div>
                    <button type="submit">Upload</button>
                  </form>
                </div>

                <div class="panel" id="panel-url">
                  <form id="urlForm">
                    <label for="url">Open a URL</label>
                    <input type="text" id="url" name="url" placeholder="https://example.com" required pattern="https?://.+" />
                    <button type="submit">Open URL</button>
                  </form>
                </div>

                <div class="panel" id="panel-pdf">
                  <p class="sub" style="margin-bottom:12px;">Control the PDF on the TV.</p>
                  <div class="btn-row">
                    <button onclick="pdfCmd('prev')">Prev Page</button>
                    <button onclick="pdfCmd('next')">Next Page</button>
                  </div>
                </div>

                                <div class="panel" id="panel-share">
                  <p class="sub" style="margin-bottom:12px;">Share a screen, window, or tab. Audio is included when the browser supports it.</p>
                  <div class="btn-row">
                    <button class="btn-green" id="shareBtn">Share screen</button>
                    <button class="btn-red" id="stopShareBtn">Stop</button>
                  </div>
                  <div class="hint">Desktop Chrome/Edge/Firefox/Safari. Phone browsers may not support screen capture.</div>
                </div>

                <div class="panel" id="panel-files">
                  <p class="sub" style="margin-bottom:12px;">Shared files (temporary).</p>
                  <div id="fileList" style="display:flex;flex-direction:column;gap:8px;"></div>
                  <div class="btn-row" style="margin-top:12px;">
                    <button onclick="refreshFiles()">Refresh</button>
                    <button class="btn-red" onclick="clearFiles()">Clear all</button>
                  </div>
                </div>
<div class="panel" id="panel-video">
                  <p class="sub" style="margin-bottom:12px;">Video controls</p>
                  <div class="btn-row">
                    <button onclick="videoCmd('play')">Play</button>
                    <button onclick="videoCmd('pause')">Pause</button>
                  </div>
                  <div class="btn-row" style="margin-top:10px;">
                    <button onclick="videoCmd('seekback')">-10s</button>
                    <button onclick="videoCmd('seekfwd')">+10s</button>
                  </div>
                  <div class="btn-row" style="margin-top:10px;">
                    <button onclick="videoCmd('replay')">Replay</button>
                    <button onclick="videoCmd('stop')">Stop</button>
                  </div>
                  <div class="divider">— audio sync —</div>
                  <p class="sub" style="margin-bottom:12px;">Sync audio to this device.</p>
                  <div class="btn-row">
                    <button class="btn-green" id="listenBtn">Listen (sync audio)</button>
                    <button class="btn-red" id="stopBtn">Stop</button>
                  </div>
                  <audio id="audioPlayer" preload="none" controls></audio>
                </div>

                <div class="status" id="status">Ready.</div>
                <div class="btn-row" style="margin-top:12px;">
                  <button class="btn-red" onclick="leaveSession()">Leave session</button>
                </div>
                <div class="footer">Developed by Aimon Islam &middot; AIVOO &middot; <a href="https://aivoo.app">aivoo.app</a></div>
              </div>
              <script>
                const status = document.getElementById('status');
                function setStatus(t){ status.textContent = t; }

                let heartbeatTimer = setInterval(async () => {
                  try {
                    const res = await fetch('session/heartbeat', {method:'POST'});
                    if (res.status === 410 || res.status === 409) {
                      clearInterval(heartbeatTimer);
                      setStatus('Session ended.');
                      setTimeout(() => { location.href = '.'; }, 800);
                    }
                  } catch (e) {}
                }, 2000);

                window.addEventListener('pagehide', () => {
                  fetch('session', {method:'DELETE', keepalive:true}).catch(()=>{});
                });

                async function leaveSession(){
                  try { await fetch('session', {method:'DELETE'}); } catch(e) {}
                  location.href = '.';
                }

                let pc = null;
                let localStream = null;
                let shareActive = false;
                function waitIce(connection) {
                  return new Promise(resolve => {
                    if (connection.iceGatheringState === 'complete') { resolve(); return; }
                    let done = false;
                    const check = () => {
                      if (done) return;
                      if (connection.iceGatheringState === 'complete') { done = true; resolve(); }
                      else setTimeout(check, 100);
                    };
                    check();
                    setTimeout(() => { if (!done) { done = true; resolve(); } }, 5000);
                  });
                }
                async function startShare() {
                  if (shareActive) return;
                  if (!window.isSecureContext) { setStatus('Screen sharing requires HTTPS.'); return; }
                  if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
                    setStatus('Screen sharing is not supported in this browser. Use desktop Chrome/Edge/Firefox/Safari.');
                    return;
                  }
                  try {
                    const stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 30 }, audio: true });
                    localStream = stream;
                    pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] });
                    stream.getTracks().forEach(track => pc.addTrack(track, stream));
                    pc.onconnectionstatechange = () => {
                      if (pc && (pc.connectionState === 'failed' || pc.connectionState === 'disconnected')) {
                        setStatus('Screen share connection lost.');
                        stopShare();
                      }
                    };
                    const offer = await pc.createOffer();
                    await pc.setLocalDescription(offer);
                    await waitIce(pc);
                    const res = await fetch('rtc/offer', { method:'POST', headers: {'Content-Type': 'text/plain'}, body: pc.localDescription.sdp });
                    const data = await res.json().catch(() => ({}));
                    if (!res.ok) {
                      const st = await fetch('rtc/status').then(r => r.json()).catch(() => ({}));
                      throw new Error(st.status || data.error || ('HTTP ' + res.status));
                    }
                    await pc.setRemoteDescription({ type: 'answer', sdp: data.answer });
                    shareActive = true;
                    setStatus('Sharing screen...');
                  } catch (e) {
                    setStatus('Share failed: ' + e);
                    stopShare();
                  }
                }
                function stopShare() {
                  if (localStream) {
                    localStream.getTracks().forEach(track => track.stop());
                    localStream = null;
                  }
                  if (pc) {
                    pc.close();
                    pc = null;
                  }
                  shareActive = false;
                  fetch('rtc/stop', {method:'POST'}).catch(()=>{});
                  setStatus('Sharing stopped.');
                }
                document.getElementById('shareBtn').addEventListener('click', startShare);
                document.getElementById('stopShareBtn').addEventListener('click', stopShare);
                document.querySelectorAll('.mode').forEach(m => {
                  m.addEventListener('click', () => {
                    document.querySelectorAll('.mode').forEach(x=>x.classList.remove('active'));
                    document.querySelectorAll('.panel').forEach(x=>x.classList.remove('active'));
                    m.classList.add('active');
                    document.getElementById('panel-' + m.dataset.mode).classList.add('active');
                    if (m.dataset.mode === "files") refreshFiles();
                  });
                });
                document.getElementById('uploadForm').addEventListener('submit', async (e) => {
                  e.preventDefault();
                  setStatus('Uploading...');
                  try {
                    const res = await fetch('upload', { method:'POST', body: new FormData(e.target) });
                    const txt = await res.text();
                    setStatus(res.ok ? ('OK: ' + txt) : ('Error ' + res.status + ': ' + txt));
                    if (res.ok) refreshFiles();
                  } catch (err) { setStatus('Network error: ' + err); }
                });
                document.getElementById('urlForm').addEventListener('submit', async (e) => {
                  e.preventDefault();
                  setStatus('Submitting URL...');
                  const fd = new FormData(e.target);
                  try {
                    const res = await fetch('url', { method:'POST', body: fd });
                    const txt = await res.text();
                    setStatus(res.ok ? ('OK: ' + txt) : ('Error ' + res.status + ': ' + txt));
                  } catch (err) { setStatus('Network error: ' + err); }
                });
                function videoCmd(action){ fetch('video/command', {method:'POST', body: new URLSearchParams({action})}).then(r=>r.text()).then(t=>setStatus('Video: '+action)).catch(e=>setStatus('Video err: '+e)); }
                function pdfCmd(action){ fetch('pdf/command', {method:'POST', body: new URLSearchParams({action})}).then(r=>r.text()).then(t=>setStatus('PDF: '+action)).catch(e=>setStatus('PDF err: '+e)); }
                async function refreshFiles(){
                  const el = document.getElementById('fileList');
                  el.innerHTML = 'Loading...';
                  try {
                    const res = await fetch('files');
                    const files = await res.json();
                    if (!files.length) { el.innerHTML = '<div class=hint>No files yet.</div>'; return; }
                    let html = '';
                    for (const f of files) {
                      html += '<div style="display:flex;gap:8px;align-items:stretch;margin-bottom:6px;">' +
                        '<a href="media/' + encodeURIComponent(f) + '" target="_blank" style="flex:1;display:block;padding:10px;border-radius:8px;background:#0f172a;color:#e2e8f0;text-decoration:none;border:1px solid #334155;">' + f + '</a>' +
                        '<button type="button" onclick="deleteFile(\'' + f + '\')" style="width:auto;margin:0;padding:0 12px;border-radius:8px;background:#dc2626;box-shadow:none;white-space:nowrap;">Delete</button>' +
                        '</div>';
                    }
                    el.innerHTML = html;
                  } catch(e){ el.innerHTML = 'Error: ' + e; }
                }
                async function deleteFile(name){
                  try {
                    await fetch('files/delete', {method:'POST', body:new URLSearchParams({name})});
                    setStatus('Deleted: ' + name);
                    refreshFiles();
                  } catch(e){ setStatus('Delete error: ' + e); }
                }
                async function clearFiles(){
                  if (!confirm('Delete all shared files?')) return;
                  try {
                    await fetch('files/clear', {method:'POST'});
                    setStatus('All shared files cleared.');
                    refreshFiles();
                  } catch(e){ setStatus('Clear error: ' + e); }
                }
                const audio = document.getElementById('audioPlayer');
                let syncTimer = null;
                let currentFile = null;
                document.getElementById('listenBtn').addEventListener('click', async () => {
                  if (syncTimer) { audio.play().catch(()=>{}); return; }
                  try { await fetch('listen/start', {method:'POST'}); } catch(e){}
                  setStatus('Syncing audio...');
                  let lastEventId = 0;
                  let pendingSeek = null;
                  let syncBusy = false;
                  let audioBlocked = false;
                  syncTimer = setInterval(async () => {
                    if (syncBusy) return;
                    syncBusy = true;
                    try {
                      const res = await fetch('nowplaying');
                      const info = await res.json();
                      if (!info || !info.file) {
                        audio.pause();
                        currentFile = null;
                        pendingSeek = null;
                        setStatus('No video playing.');
                        return;
                      }
                      if (info.eventId && info.eventId !== lastEventId) {
                        lastEventId = info.eventId;
                      }
                      if (info.file !== currentFile) {
                        currentFile = info.file;
                        pendingSeek = (info.positionMs || 0) / 1000;
                        audio.onloadedmetadata = () => {
                          if (pendingSeek !== null) {
                            try { audio.currentTime = pendingSeek; } catch (_) {}
                            pendingSeek = null;
                          }
                        };
                        audio.src = 'media/' + encodeURIComponent(info.file);
                        audio.load();
                      }
                      if (audio.readyState >= 1 && !audio.seeking) {
                        const target = (info.positionMs || 0) / 1000;
                        if (Number.isFinite(target) && Math.abs(audio.currentTime - target) > 0.35) {
                          try { audio.currentTime = target; } catch (_) {}
                        }
                      }
                      audio.loop = false;
                      if (info.playing) {
                        if (audio.ended && audio.readyState >= 1) {
                          try { audio.currentTime = 0; } catch (_) {}
                        }
                        if (audio.paused) {
                          audio.play().catch(() => {
                            if (!audioBlocked) {
                              audioBlocked = true;
                              setStatus('Audio blocked by the browser. Use the audio controls once, then press Listen again.');
                            }
                          });
                        } else {
                          audioBlocked = false;
                        }
                      } else if (!audio.paused) {
                        audio.pause();
                      }
                      if (!audioBlocked) setStatus('Synced: ' + info.file + (info.playing ? ' (playing)' : ' (paused)'));
                    } catch (err) {
                      setStatus('Sync error: ' + err);
                    } finally {
                      syncBusy = false;
                    }
                  }, 100);
                });
                document.getElementById('stopBtn').addEventListener('click', () => {
                  if (syncTimer) {
                    clearInterval(syncTimer);
                    syncTimer = null;
                    fetch('listen/stop', {method:'POST'}).catch(()=>{});
                  }
                  audio.pause();
                  currentFile = null;
                  setStatus('Stopped.');
                });
                window.addEventListener('pagehide', () => {
                  if (syncTimer) {
                    clearInterval(syncTimer);
                    syncTimer = null;
                    fetch('listen/stop', {method:'POST', keepalive:true}).catch(()=>{});
                  }
                  audio.pause();
                });
              </script>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("X-Frame-Options", "DENY")
            addHeader("Referrer-Policy", "no-referrer")
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            files.values.forEach { runCatching { File(it).delete() } }
            throw e
        }
        val tmpPath = files["file"] ?: files.values.firstOrNull()
        if (tmpPath == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No file uploaded")
        }
        val tmpFile = File(tmpPath)
        if (tmpFile.length() > MAX_UPLOAD_BYTES) {
            tmpFile.delete()
            return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, "text/plain", "File too large (max 512 MB)")
        }
        val type = MediaTypeDetector.detect(tmpFile)
        val isShared = (type == app.aivoo.tools.androidprojector.model.ContentType.UNKNOWN)
        val originalName = session.parameters["file"]?.firstOrNull()?.trim().orEmpty()
        val originalExt = originalName.substringAfterLast('.', "").lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
        // Unknown files are downloads only. Never infer APK from a ZIP header.
        val ext = if (isShared) ".bin" else extensionFor(type, originalExt)
        val outFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}$ext")
        val mime = if (isShared) "application/octet-stream" else mimeFromName(outFile.name)
        try {
            tmpFile.inputStream().use { input ->
                outFile.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            tmpFile.delete()
            outFile.delete()
            throw e
        }
        tmpFile.delete()
        synchronized(uploadedFiles) { uploadedFiles.add(outFile.name) }
        if (type == app.aivoo.tools.androidprojector.model.ContentType.VIDEO) {
            currentFile = outFile
            currentPositionMs = 0L
        }
        val uploaded = app.aivoo.tools.androidprojector.model.UploadedFile(
            name = outFile.name,
            path = outFile.absolutePath,
            displayName = originalName.ifBlank { outFile.name },
            mimeType = mime,
            type = type,
            size = outFile.length(),
        )
        onUpload(uploaded)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "Uploaded: ${outFile.name}")
    }

    fun removeUploadedFile(name: String) {
        synchronized(uploadedFiles) { uploadedFiles.remove(name) }
    }

    private fun handleUrl(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        try { session.parseBody(files) } catch (_: Exception) {}
        val params = session.parameters
        val url = params["url"]?.firstOrNull()?.trim()
        if (url == null || !UrlUtils.isValidHttpUrl(url)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid URL (must start with http:// or https://)")
        }
        val uploaded = app.aivoo.tools.androidprojector.model.UploadedFile(
            name = url,
            path = url,
            mimeType = "text/html",
            type = app.aivoo.tools.androidprojector.model.ContentType.URL,
            size = 0L,
        )
        onUpload(uploaded)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "URL queued: $url")
    }

    private fun serveMedia(session: IHTTPSession, name: String): Response {
        if (!PathUtils.isSafeMediaName(name)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        val base = context.cacheDir.canonicalFile
        val file = File(base, name).canonicalFile
        if (file.parentFile != base || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        val isUploaded = synchronized(uploadedFiles) { uploadedFiles.contains(name) }
        if (!isUploaded) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        val mime = mimeFromName(name)
        val total = file.length()
        when (val parsed = RangeParser.parse(session.headers["range"], total)) {
            is RangeParser.Result.Partial -> {
                val fis = FileInputStream(file)
                var skipped = 0L
                while (skipped < parsed.start) {
                    val n = fis.skip(parsed.start - skipped)
                    if (n <= 0L) break
                    skipped += n
                }
                if (skipped != parsed.start) {
                    runCatching { fis.close() }
                    return unsatisfiableRange(total)
                }
                val length = parsed.end - parsed.start + 1
                val resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, length)
                resp.addHeader("Content-Range", "bytes ${parsed.start}-${parsed.end}/$total")
                resp.addHeader("Accept-Ranges", "bytes")
                resp.addHeader("Cache-Control", "no-store")
                return resp
            }
            RangeParser.Result.Unsatisfiable -> return unsatisfiableRange(total)
            RangeParser.Result.NotRange -> {
                val fis = FileInputStream(file)
                val resp = newFixedLengthResponse(Response.Status.OK, mime, fis, total)
                resp.addHeader("Accept-Ranges", "bytes")
                resp.addHeader("Cache-Control", "no-store")
                return resp
            }
        }
    }

    private fun unsatisfiableRange(total: Long): Response {
        val resp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Range not satisfiable")
        resp.addHeader("Content-Range", "bytes */$total")
        return resp
    }

    private fun serveNowPlaying(): Response {
        val f = currentFile
        val json = if (f != null) {
            "{\"file\":\"${f.name}\",\"positionMs\":${currentPositionMs},\"playing\":${currentPlaying},\"eventId\":${videoEventId}}"
        } else {
            "{\"file\":null,\"positionMs\":0,\"playing\":false,\"eventId\":${videoEventId}}"
        }
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun handleListen(start: Boolean): Response {
        listenerCount = if (start) {
            listenerCount + 1
        } else {
            maxOf(0, listenerCount - 1)
        }
        val active = listenerCount > 0
        onListenChanged(active)
        val resp = newFixedLengthResponse(Response.Status.OK, "text/plain", if (active) "listening" else "stopped")
        return resp
    }

    private fun servePdfState(): Response {
        val q = '"'
        val json = "{" + q + "page" + q + ":" + pdfPage + "," + q + "pageCount" + q + ":" + pdfPageCount + "}"
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun handlePdfCommand(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        try { session.parseBody(files) } catch (_: Exception) {}
        val params = session.parameters
        val action = params["action"]?.firstOrNull()?.trim() ?: ""
        if (action.isNotBlank()) onPdfCommand(action)
        val resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")

        return resp
    }

    private fun serveVideoState(): Response {
        val q = '"'
        val json = "{" + q + "positionMs" + q + ":" + currentPositionMs + "," + q + "durationMs" + q + ":" + videoDurationMs + "," + q + "playing" + q + ":" + currentPlaying + "," + q + "eventId" + q + ":" + videoEventId + "}"
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun handleVideoCommand(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        try { session.parseBody(files) } catch (_: Exception) {}
        val params = session.parameters
        val action = params["action"]?.firstOrNull()?.trim() ?: ""
        if (action.isNotBlank()) onVideoCommand(action)
        val resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")

        return resp
    }

    private fun handleDelete(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        try {
            session.parseBody(files)
        } catch (_: Exception) {
        }
        val name = session.parameters["name"]?.firstOrNull()?.trim()
        if (name.isNullOrBlank() || !PathUtils.isSafeMediaName(name)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid file name")
        }
        val base = context.cacheDir.canonicalFile
        val file = File(base, name).canonicalFile
        if (file.parentFile != base || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        val removed = synchronized(uploadedFiles) { uploadedFiles.remove(name) }
        if (!removed) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        if (!file.delete()) {
            synchronized(uploadedFiles) { uploadedFiles.add(name) }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Delete failed")
        }
        if (currentFile?.name == name) {
            currentFile = null
            currentPlaying = false
            currentPositionMs = 0L
            videoDurationMs = 0L
            videoEventId++
        }
        onFilesChanged()
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "Deleted: $name")
    }

    private fun handleClear(): Response {
        val names = synchronized(uploadedFiles) { ArrayList(uploadedFiles) }
        val base = context.cacheDir.canonicalFile
        for (name in names) {
            if (!PathUtils.isSafeMediaName(name)) continue
            val file = File(base, name).canonicalFile
            if (file.parentFile == base) {
                file.delete()
            }
        }
        synchronized(uploadedFiles) { uploadedFiles.clear() }
        currentFile = null
        currentPlaying = false
        currentPositionMs = 0L
        videoDurationMs = 0L
        videoEventId++
        onFilesChanged()
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "Cleared")
    }

    private fun serveFiles(): Response {
        val names = synchronized(uploadedFiles) { ArrayList(uploadedFiles) }
        val sb = StringBuilder("[")
        names.forEachIndexed { i, name ->
            if (i > 0) sb.append(",")
            sb.append('"').append(name.replace("\"", "")).append('"')
        }
        sb.append("]")
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun mimeFromName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".ts") -> "video/mp2t"
            lower.endsWith(".3gp") -> "video/3gpp"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".bmp") -> "image/bmp"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".heic") -> "image/heic"
            lower.endsWith(".avif") -> "image/avif"
            lower.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun extensionFor(
        type: app.aivoo.tools.androidprojector.model.ContentType,
        originalExt: String?,
    ): String = when (type) {
        app.aivoo.tools.androidprojector.model.ContentType.PDF -> ".pdf"
        app.aivoo.tools.androidprojector.model.ContentType.VIDEO ->
            if (originalExt in VIDEO_EXTENSIONS) ".$originalExt" else ".mp4"
        app.aivoo.tools.androidprojector.model.ContentType.IMAGE ->
            if (originalExt in IMAGE_EXTENSIONS) ".$originalExt" else ".jpg"
        app.aivoo.tools.androidprojector.model.ContentType.URL -> ""
        app.aivoo.tools.androidprojector.model.ContentType.UNKNOWN -> ".bin"
    }
}
