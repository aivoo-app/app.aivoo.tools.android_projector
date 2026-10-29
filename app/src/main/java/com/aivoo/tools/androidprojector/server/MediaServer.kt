package app.aivoo.tools.androidprojector.server

import android.content.Context
import android.util.Log
import app.aivoo.tools.androidprojector.model.UploadedFile
import app.aivoo.tools.androidprojector.media.AudioBroadcaster
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.util.regex.Pattern

class MediaServer(
    private val context: Context,
    private val port: Int = 7071,
    private val onUpload: (UploadedFile) -> Unit,
    private val onListenChanged: (Boolean) -> Unit = {},
    private val onPdfCommand: (String) -> Unit = {},
    private val onVideoCommand: (String) -> Unit = {},
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "MediaServer"
        private const val MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        private val URL_PATTERN = Pattern.compile("^(https?://).+", Pattern.CASE_INSENSITIVE)
    }

    @Volatile var currentFile: File? = null
    @Volatile var currentPositionMs: Long = 0L
    @Volatile var currentPlaying: Boolean = false
    @Volatile var listenerActive: Boolean = false
    @Volatile var pdfPage: Int = 0
    @Volatile var pdfPageCount: Int = 0
    @Volatile var videoPositionMs: Long = 0L
    @Volatile var videoDurationMs: Long = 0L
    @Volatile var videoPlaying: Boolean = false
    @Volatile var videoEventId: Long = 1
    val uploadedFiles = mutableListOf<String>()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "$method $uri")
        return try {
            when {
                uri == "/" && method == Method.GET -> serveLanding()
                uri == "/upload" && method == Method.POST -> handleUpload(session)
                uri == "/url" && method == Method.POST -> handleUrl(session)
                uri.startsWith("/media/") && method == Method.GET -> serveMedia(session, uri.removePrefix("/media/"))
                uri == "/nowplaying" && method == Method.GET -> serveNowPlaying()
                uri == "/audio/live" && method == Method.GET -> serveAudioLive()
                uri == "/pdf/state" && method == Method.GET -> servePdfState()
                uri == "/pdf/command" && method == Method.POST -> handlePdfCommand(session)
                uri == "/video/state" && method == Method.GET -> serveVideoState()
                uri == "/video/command" && method == Method.POST -> handleVideoCommand(session)
                uri == "/files" && method == Method.GET -> serveFiles()
                uri == "/listen/start" && method == Method.POST -> handleListen(true)
                uri == "/listen/stop" && method == Method.POST -> handleListen(false)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }
    private fun serveLanding(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
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
                </div>

                <div class="panel active" id="panel-upload">
                  <form id="uploadForm">
                    <label for="file">Choose file</label>
                    <input type="file" id="file" name="file" required />
                    <div class="hint">Max 2 GB. Supported: PDF, video, image.</div>
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

                                <div class="panel" id="panel-files">
                  <p class="sub" style="margin-bottom:12px;">Shared files (temporary).</p>
                  <div id="fileList" style="display:flex;flex-direction:column;gap:8px;"></div>
                  <button onclick="refreshFiles()" style="margin-top:12px;">Refresh</button>
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
                  <audio id="audioPlayer" preload="none"></audio>
                </div>

                <div class="status" id="status">Ready.</div>
                <div class="footer">Developed by Aimon Islam &middot; AIVOO &middot; <a href="https://aivoo.app">aivoo.app</a></div>
              </div>
              <script>
                const status = document.getElementById('status');
                function setStatus(t){ status.textContent = t; }
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
                    const res = await fetch('/upload', { method:'POST', body: new FormData(e.target) });
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
                    const res = await fetch('/url', { method:'POST', body: fd });
                    const txt = await res.text();
                    setStatus(res.ok ? ('OK: ' + txt) : ('Error ' + res.status + ': ' + txt));
                  } catch (err) { setStatus('Network error: ' + err); }
                });
                function videoCmd(action){ fetch('/video/command', {method:'POST', body: new URLSearchParams({action})}).then(r=>r.text()).then(t=>setStatus('Video: '+action)).catch(e=>setStatus('Video err: '+e)); }
                function pdfCmd(action){ fetch('/pdf/command', {method:'POST', body: new URLSearchParams({action})}).then(r=>r.text()).then(t=>setStatus('PDF: '+action)).catch(e=>setStatus('PDF err: '+e)); }
                async function refreshFiles(){
                  const el = document.getElementById('fileList');
                  el.innerHTML = 'Loading...';
                  try {
                    const res = await fetch('/files');
                    const files = await res.json();
                    if (!files.length) { el.innerHTML = '<div class=hint>No files yet.</div>'; return; }
                    let html = '';
                    for (const f of files) {
                      html += '<a href="/media/' + encodeURIComponent(f) + '" target="_blank" style="display:block;padding:10px;border-radius:8px;background:#0f172a;color:#e2e8f0;text-decoration:none;border:1px solid #334155;margin-bottom:6px;">' + f + '</a>';
                    }
                    el.innerHTML = html;
                  } catch(e){ el.innerHTML = 'Error: ' + e; }
                }
                const audio = document.getElementById('audioPlayer');
                let syncTimer = null;
                let currentFile = null;
                document.getElementById('listenBtn').addEventListener('click', async () => {
                  if (syncTimer) return;
                  try { await fetch('/listen/start', {method:'POST'}); } catch(e){}
                  setStatus('Syncing audio...');
                  let lastEventId = 0;
                  syncTimer = setInterval(async () => {
                    try {
                      const res = await fetch('/nowplaying');
                      const info = await res.json();
                      if (!info || !info.file) { setStatus('No video playing.'); return; }
                      if (info.eventId && info.eventId !== lastEventId) {
                        lastEventId = info.eventId;
                        if (info.file !== currentFile) {
                        currentFile = info.file;
                        audio.src = '/media/' + encodeURIComponent(info.file);
                        audio.load();
                      }
                      if (Math.abs(audio.currentTime - info.positionMs/1000) > 0.2) {
                        audio.currentTime = info.positionMs / 1000;
                      }
                      audio.loop = false;
                      if (info.playing) { if (audio.ended) { audio.currentTime = 0; } audio.play().catch(()=>{}); } else { audio.pause(); }
                      setStatus('Synced: ' + info.file + (info.playing ? ' (playing)' : ' (paused)'));
                      }
                    } catch (err) { setStatus('Sync error: ' + err); }
                  }, 100);
                });
                document.getElementById('stopBtn').addEventListener('click', () => {
                  if (syncTimer) { clearInterval(syncTimer); syncTimer = null; }
                  fetch('/listen/stop', {method:'POST'}).catch(()=>{});
                  audio.pause();
                  currentFile = null;
                  setStatus('Stopped.');
                });
              </script>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val tmpPath = files["file"] ?: files.values.firstOrNull()
        if (tmpPath == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No file uploaded")
        }
        val tmpFile = File(tmpPath)
        if (tmpFile.length() > MAX_UPLOAD_BYTES) {
            tmpFile.delete()
            return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, "text/plain", "File too large (max 2 GB)")
        }
        val type = detectType(tmpFile)
        val isShared = (type == app.aivoo.tools.androidprojector.model.ContentType.UNKNOWN)
        val (ext, mime) = if (isShared) {
            val hb = ByteArray(2)
            FileInputStream(tmpFile).use { it.read(hb) }
            if (hb[0] == 0x50.toByte() && hb[1] == 0x4B.toByte()) Pair(".apk", "application/vnd.android.package-archive") else Pair(".bin", "application/octet-stream")
        } else {
            Pair(extensionFor(type), mimeFor(type))
        }
        val outFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}$ext")
        tmpFile.inputStream().use { input ->
            outFile.outputStream().use { out -> input.copyTo(out) }
        }
        tmpFile.delete()
        uploadedFiles.add(outFile.name)
        if (type == app.aivoo.tools.androidprojector.model.ContentType.VIDEO) {
            currentFile = outFile
            currentPositionMs = 0L
        }
        val uploaded = app.aivoo.tools.androidprojector.model.UploadedFile(
            name = outFile.name,
            path = outFile.absolutePath,
            mimeType = mime,
            type = type,
            size = outFile.length(),
        )
        onUpload(uploaded)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "Uploaded: ${outFile.name}")
    }

    private fun handleUrl(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        try { session.parseBody(files) } catch (_: Exception) {}
        val params = session.parameters
        val url = params["url"]?.firstOrNull()?.trim()
        if (url.isNullOrBlank() || !URL_PATTERN.matcher(url).matches()) {
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
        val file = File(context.cacheDir, name)
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        val mime = mimeFromName(name)
        val range = session.headers["range"]
        val total = file.length()
        if (range != null && range.startsWith("bytes=")) {
            val parts = range.removePrefix("bytes=").split("-")
            val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = parts.getOrNull(1)?.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
            val length = end - start + 1
            val fis = FileInputStream(file)
            fis.skip(start)
            val resp = newChunkedResponse(Response.Status.PARTIAL_CONTENT, mime, fis)
            resp.addHeader("Content-Range", "bytes $start-$end/$total")
            resp.addHeader("Content-Length", length.toString())
            resp.addHeader("Accept-Ranges", "bytes")
            return resp
        }
        val fis = FileInputStream(file)
        val resp = newChunkedResponse(Response.Status.OK, mime, fis)
        resp.addHeader("Content-Length", total.toString())
        resp.addHeader("Accept-Ranges", "bytes")
        return resp
    }

    private fun serveNowPlaying(): Response {
        val f = currentFile
        val json = if (f != null) {
            "{\"file\":\"${f.name}\",\"positionMs\":${currentPositionMs},\"playing\":${currentPlaying},\"eventId\":${videoEventId}}"
        } else {
            "{\"file\":null,\"positionMs\":0,\"playing\":false,\"eventId\":${videoEventId}}"
        }
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun serveAudioLive(): Response {
        val q = AudioBroadcaster.register()
        val sr = AudioBroadcaster.sampleRate
        val ch = AudioBroadcaster.channelCount
        val header = wavHeader(sr, ch)
        val stream = object : java.io.InputStream() {
            private var headerPos = 0
            private var current: ByteArray? = null
            private var offset = 0
            override fun read(): Int {
                val b = ByteArray(1)
                val n = read(b, 0, 1)
                return if (n == -1) -1 else (b[0].toInt() and 0xFF)
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (headerPos < header.size) {
                    val n = minOf(len, header.size - headerPos)
                    System.arraycopy(header, headerPos, b, off, n)
                    headerPos += n
                    return n
                }
                if (current == null || offset >= current!!.size) {
                    current = try { q.take() } catch (e: Exception) { null }
                    offset = 0
                    if (current == null) return -1
                }
                val c = current!!
                val n = minOf(len, c.size - offset)
                System.arraycopy(c, offset, b, off, n)
                offset += n
                return n
            }
            override fun close() {
                AudioBroadcaster.unregister(q)
                super.close()
            }
        }
        val resp = newChunkedResponse(Response.Status.OK, "audio/wav", stream)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    private fun wavHeader(sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)
        val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(0x7FFFFFFF)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort((channels * 2).toShort())
        bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(0x7FFFFFFF)
        return header
    }

    private fun handleListen(start: Boolean): Response {
        listenerActive = start
        onListenChanged(start)
        val resp = newFixedLengthResponse(Response.Status.OK, "text/plain", if (start) "listening" else "stopped")
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun servePdfState(): Response {
        val q = '"'
        val json = "{" + q + "page" + q + ":" + pdfPage + "," + q + "pageCount" + q + ":" + pdfPageCount + "}"
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun handlePdfCommand(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        try { session.parseBody(files) } catch (_: Exception) {}
        val params = session.parameters
        val action = params["action"]?.firstOrNull()?.trim() ?: ""
        if (action.isNotBlank()) onPdfCommand(action)
        val resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun serveVideoState(): Response {
        val q = '"'
        val json = "{" + q + "positionMs" + q + ":" + videoPositionMs + "," + q + "durationMs" + q + ":" + videoDurationMs + "," + q + "playing" + q + ":" + videoPlaying + "," + q + "eventId" + q + ":" + videoEventId + "}"
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun handleVideoCommand(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        try { session.parseBody(files) } catch (_: Exception) {}
        val params = session.parameters
        val action = params["action"]?.firstOrNull()?.trim() ?: ""
        if (action.isNotBlank()) onVideoCommand(action)
        val resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun serveFiles(): Response {
        val sb = StringBuilder("[")
        synchronized(uploadedFiles) {
            uploadedFiles.forEachIndexed { i, name ->
                if (i > 0) sb.append(",")
                sb.append('"').append(name.replace('"'.toString(), "")).append('"')
            }
        }
        sb.append("]")
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", sb.toString())
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun mimeFromName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun detectType(file: File): app.aivoo.tools.androidprojector.model.ContentType {
        return try {
            val bytes = ByteArray(32)
            val read = FileInputStream(file).use { it.read(bytes) }
            if (read <= 0) return app.aivoo.tools.androidprojector.model.ContentType.UNKNOWN
            if (read >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()) return app.aivoo.tools.androidprojector.model.ContentType.PDF
            if (read >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return app.aivoo.tools.androidprojector.model.ContentType.IMAGE
            if (read >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return app.aivoo.tools.androidprojector.model.ContentType.IMAGE
            if (read >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return app.aivoo.tools.androidprojector.model.ContentType.IMAGE
            if (read >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()) return app.aivoo.tools.androidprojector.model.ContentType.IMAGE
            if (read >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()) return app.aivoo.tools.androidprojector.model.ContentType.VIDEO
            if (read >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() && bytes[8] == 0x41.toByte() && bytes[9] == 0x56.toByte() && bytes[10] == 0x49.toByte()) return app.aivoo.tools.androidprojector.model.ContentType.VIDEO
            val head = String(bytes, 0, read, Charsets.ISO_8859_1)
            if (head.contains("ftyp") || head.contains("moov") || head.contains("mdat")) return app.aivoo.tools.androidprojector.model.ContentType.VIDEO
            app.aivoo.tools.androidprojector.model.ContentType.UNKNOWN
        } catch (e: Exception) {
            app.aivoo.tools.androidprojector.model.ContentType.UNKNOWN
        }
    }

    private fun extensionFor(type: app.aivoo.tools.androidprojector.model.ContentType): String = when (type) {
        app.aivoo.tools.androidprojector.model.ContentType.PDF -> ".pdf"
        app.aivoo.tools.androidprojector.model.ContentType.VIDEO -> ".mp4"
        app.aivoo.tools.androidprojector.model.ContentType.IMAGE -> ".jpg"
        app.aivoo.tools.androidprojector.model.ContentType.URL -> ""
        app.aivoo.tools.androidprojector.model.ContentType.UNKNOWN -> ".bin"
    }

    private fun mimeFor(type: app.aivoo.tools.androidprojector.model.ContentType): String = when (type) {
        app.aivoo.tools.androidprojector.model.ContentType.PDF -> "application/pdf"
        app.aivoo.tools.androidprojector.model.ContentType.VIDEO -> "video/mp4"
        app.aivoo.tools.androidprojector.model.ContentType.IMAGE -> "image/jpeg"
        app.aivoo.tools.androidprojector.model.ContentType.URL -> "text/html"
        app.aivoo.tools.androidprojector.model.ContentType.UNKNOWN -> "application/octet-stream"
    }
}
