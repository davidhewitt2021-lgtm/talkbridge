package com.talkbridge

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class CrashApp : Application() {

    // attachBaseContext is the earliest code we control — it runs before
    // library initialisers, so crashes in them get captured too.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(base.filesDir, "last_crash.txt").writeText(
                    "TalkBridge crash\nThread: ${thread.name}\n\n" + sw.toString()
                )
            } catch (_: Exception) {}
            previous?.uncaughtException(thread, throwable)
        }
    }
}
