package com.beudbeud.fuji

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.beudbeud.fuji.data.DebugLog
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.ui.App

private object CrashLogger : Thread.UncaughtExceptionHandler {
    var next: Thread.UncaughtExceptionHandler? = null
    override fun uncaughtException(t: Thread, e: Throwable) {
        DebugLog.log("CRASH in ${t.name}: ${android.util.Log.getStackTraceString(e)}")
        next?.uncaughtException(t, e)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(applicationContext)
        // Fatal crashes land in the app's own log, so a user report can carry
        // the stack trace — logcat is out of reach without adb. onCreate runs
        // again on rotation, hence the identity check before wrapping.
        if (Thread.getDefaultUncaughtExceptionHandler() !== CrashLogger) {
            CrashLogger.next = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashLogger)
        }
        // ponytail: repository recreated on rotation — it just reloads a small JSON file
        val repo = RecipeRepository(applicationContext)
        val sharedText = if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else null
        val sharedImage = if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } else null
        setContent { App(repo, sharedText, sharedImage) }
    }
}
