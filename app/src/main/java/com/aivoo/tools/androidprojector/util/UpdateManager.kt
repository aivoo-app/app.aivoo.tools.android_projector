package com.aivoo.tools.androidprojector.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.aivoo.tools.androidprojector.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val REPO = "aivoo-app/app.aivoo.tools.android_projector"
    private const val PREFS = "update_prefs"
    private const val KEY_TAG = "installed_tag"

    data class ReleaseInfo(val tagName: String, val downloadUrl: String, val name: String)

    suspend fun checkLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$REPO/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (conn.responseCode != 200) return@withContext null
            val json = conn.inputStream.bufferedReader().readText()
            val obj = JSONObject(json)
            val tag = obj.optString("tag_name")
            val assets = obj.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            if (tag.isBlank() || apkUrl.isNullOrBlank()) null else ReleaseInfo(tag, apkUrl, obj.optString("name"))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun isUpdateAvailable(context: Context, latest: ReleaseInfo): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installed = prefs.getString(KEY_TAG, "v" + BuildConfig.VERSION_NAME) ?: "v" + BuildConfig.VERSION_NAME
        return latest.tagName != installed
    }

    suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit = {}): File? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.cacheDir, "update.apk")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val total = conn.contentLength
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var downloaded = 0
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) onProgress((downloaded * 100) / total)
                    }
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun markInstalled(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, tag).apply()
    }

    fun installApk(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
