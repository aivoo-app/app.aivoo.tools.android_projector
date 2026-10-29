package com.aivoo.tools.androidprojector.server

import android.content.Context
import android.util.Log
import com.aivoo.tools.androidprojector.model.UploadedFile
import com.aivoo.tools.androidprojector.media.AudioBroadcaster
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.util.regex.Pattern

class MediaServer(
    private val context: Context,
    private val port: Int = 7071,
    private val onUpload: (UploadedFile) -> Unit,
    private val onListenChanged: (Boolean) -> Unit = {},
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
                :root { --bg:#0f172a; --card:#1e293b; --accent:#6366f1; --text:#e2e8f0; --muted:#94a3b8; }
                * { box-sizing: border-box; }
                body { margin:0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background: linear-gradient(135deg,#0f172a,#1e293b); color: var(--text); min-height:100vh; display:flex; align-items:center; justify-content:center; padding:24px; }
                .card { background: var(--card); border-radius:16px; padding:32px; max-width:560px; width:100%; box-shadow:0 20px 60px rgba(0,0,0,.4); }
                h1 { margin:0 0 8px; font-size:28px; }
                p { color: var(--muted); margin:0 0 20px; }
                label { display:block; margin:14px 0 6px; font-weight:600; }
                input[type=file], input[type=text] { width:100%; padding:10px; border-radius:8px; border:1px solid #334155; background:#0f172a; color:var(--text); }
                button { margin-top:16px; width:100%; padding:12px; border:none; border-radius:8px; background:var(--accent); color:#fff; font-size:16px; font-weight:600; cursor:pointer; transition:filter .15s; }
                button:hover { filter:brightness(1.1); }
                .divider { text-align:center; color:var(--muted); margin:24px 0 8px; }
                .hint { font-size:12px; color:var(--muted); margin-top:8px; }
                .status { margin-top:16px; padding:10px; border-radius:8px; background:#0f172a; color:var(--muted); font-size:13px; }
                audio { width:100%; margin-top:12px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>Android TV Projector</h1>
                <p>Upload a file (PDF, video, image) to display on your TV, or submit a URL.</p>
                <form action="/upload" method="post" enctype="multipart/form-data" id="uploadForm">
                  <label for="file">Choose file</label>
                  <input type="file" id="file" name="file" accept=".pdf,.mp4,.mkv,.avi,.mov,.webm,.jpg,.jpeg,.png,.gif,.webp" required />
                  <div class="hint">Max 2 GB. Supported: PDF, video, image.</div>
                  <button type="submit">Upload</button>
                </form>
                <div class="divider">— or —</div>
                <form action="/url" method="post" id="urlForm">
                  <label for="url">Open a URL</label>
                  <input type="text" id="url" name="url" placeholder="https://example.com" required pattern="https?://.+" />
                  <button type="submit">Open URL</button>
                </form>
                <div class="divider">— audio —</div>
                <button id="listenBtn" style="background:#10b981;">Listen (sync audio)</button>
                <button id="stopBtn" style="background:#ef4444;margin-top:8px;">Stop</button>
                <audio id="audioPlayer" preload="none"></audio>
                <div class="status" id="status">Ready.</div>
                <div class="hint" style="text-align:center;margin-top:16px;">Developed by Aimon Islam · AIVOO · <a href="https://aivoo.app" style="color:#6366f1;">aivoo.app</a></div>
              </div>
              <script>
                const status = document.getElementById('status');
                function setStatus(t){ status.textContent = t; }
                document.getElementById('uploadForm').addEventListener('submit', async (e) => {
                  e.preventDefault();
                  setStatus('Uploading...');
                  try {
                    const res = await fetch('/upload', { method:'POST', body: new FormData(e.target) });
                    const txt = await res.text();
                    setStatus(res.ok ? ('OK: ' + txt) : ('Error ' + res.status + ': ' + txt));
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
                const audio = document.getElementById('audioPlayer');
                let syncTimer = null;
                let currentFile = null;
                document.getElementById('listenBtn').addEventListener('click', async () => {
                  if (syncTimer) return;
                  try { await fetch('/listen/start', {method:'POST'}); } catch(e){}
                  setStatus('Syncing audio...');
                  syncTimer = setInterval(async () => {
                    try {
                      const res = await fetch('/nowplaying');
                      const info = await res.json();
                      if (!info || !info.file) { setStatus('No video playing.'); return; }
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
                    } catch (err) { setStatus('Sync error: ' + err); }
                  }, 200);
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
        if (type == com.aivoo.tools.androidprojector.model.ContentType.UNKNOWN) {
            tmpFile.delete()
            return newFixedLengthResponse(Response.Status.UNSUPPORTED_MEDIA_TYPE, "text/plain", "Unsupported file type")
        }
        val ext = extensionFor(type)
        val outFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}$ext")
        tmpFile.inputStream().use { input ->
            outFile.outputStream().use { out -> input.copyTo(out) }
        }
        tmpFile.delete()
        if (type == com.aivoo.tools.androidprojector.model.ContentType.VIDEO) {
            currentFile = outFile
            currentPositionMs = 0L
        }
        val uploaded = com.aivoo.tools.androidprojector.model.UploadedFile(
            name = outFile.name,
            path = outFile.absolutePath,
            mimeType = mimeFor(type),
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
        val uploaded = com.aivoo.tools.androidprojector.model.UploadedFile(
            name = url,
            path = url,
            mimeType = "text/html",
            type = com.aivoo.tools.androidprojector.model.ContentType.URL,
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
            "{\"file\":\"${f.name}\",\"positionMs\":${currentPositionMs},\"playing\":${currentPlaying}}"
        } else {
            "{\"file\":null,\"positionMs\":0,\"playing\":false}"
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

    private fun detectType(file: File): com.aivoo.tools.androidprojector.model.ContentType {
        return try {
            val bytes = ByteArray(32)
            val read = FileInputStream(file).use { it.read(bytes) }
            if (read <= 0) return com.aivoo.tools.androidprojector.model.ContentType.UNKNOWN
            if (read >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()) return com.aivoo.tools.androidprojector.model.ContentType.PDF
            if (read >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return com.aivoo.tools.androidprojector.model.ContentType.IMAGE
            if (read >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return com.aivoo.tools.androidprojector.model.ContentType.IMAGE
            if (read >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return com.aivoo.tools.androidprojector.model.ContentType.IMAGE
            if (read >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()) return com.aivoo.tools.androidprojector.model.ContentType.IMAGE
            if (read >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()) return com.aivoo.tools.androidprojector.model.ContentType.VIDEO
            if (read >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() && bytes[8] == 0x41.toByte() && bytes[9] == 0x56.toByte() && bytes[10] == 0x49.toByte()) return com.aivoo.tools.androidprojector.model.ContentType.VIDEO
            val head = String(bytes, 0, read, Charsets.ISO_8859_1)
            if (head.contains("ftyp") || head.contains("moov") || head.contains("mdat")) return com.aivoo.tools.androidprojector.model.ContentType.VIDEO
            com.aivoo.tools.androidprojector.model.ContentType.UNKNOWN
        } catch (e: Exception) {
            com.aivoo.tools.androidprojector.model.ContentType.UNKNOWN
        }
    }

    private fun extensionFor(type: com.aivoo.tools.androidprojector.model.ContentType): String = when (type) {
        com.aivoo.tools.androidprojector.model.ContentType.PDF -> ".pdf"
        com.aivoo.tools.androidprojector.model.ContentType.VIDEO -> ".mp4"
        com.aivoo.tools.androidprojector.model.ContentType.IMAGE -> ".jpg"
        com.aivoo.tools.androidprojector.model.ContentType.URL -> ""
        com.aivoo.tools.androidprojector.model.ContentType.UNKNOWN -> ".bin"
    }

    private fun mimeFor(type: com.aivoo.tools.androidprojector.model.ContentType): String = when (type) {
        com.aivoo.tools.androidprojector.model.ContentType.PDF -> "application/pdf"
        com.aivoo.tools.androidprojector.model.ContentType.VIDEO -> "video/mp4"
        com.aivoo.tools.androidprojector.model.ContentType.IMAGE -> "image/jpeg"
        com.aivoo.tools.androidprojector.model.ContentType.URL -> "text/html"
        com.aivoo.tools.androidprojector.model.ContentType.UNKNOWN -> "application/octet-stream"
    }
}
