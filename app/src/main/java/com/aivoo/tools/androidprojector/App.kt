package com.aivoo.tools.androidprojector

import android.app.Application
import android.content.Context
import java.io.File

class App : Application() {
    companion object {
        lateinit var instance: App
            private set

        fun clearCache(context: Context) {
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        clearCache(this)
    }

    fun cacheDir(): File = cacheDir
}

