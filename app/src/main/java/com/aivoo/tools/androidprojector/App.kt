package app.aivoo.tools.androidprojector

import android.app.Application
import android.content.Context

class App : Application() {
    companion object {
        fun clearCache(context: Context) {
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        clearCache(this)
    }
}

