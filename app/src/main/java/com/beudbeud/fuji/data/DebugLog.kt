package com.beudbeud.fuji.data

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Append-only debug log in filesDir, viewable and shareable from the app. */
object DebugLog {
    private var file: File? = null
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    fun init(context: Context) {
        file = File(context.filesDir, "debug.log")
    }

    @Synchronized
    fun log(message: String) {
        val f = file ?: return
        runCatching {
            f.appendText("${LocalDateTime.now().format(fmt)} $message\n")
            // ponytail: crude size cap — keep the newest ~128KB
            if (f.length() > 256 * 1024) {
                f.writeText(f.readText().takeLast(128 * 1024).substringAfter('\n'))
            }
        }
    }

    fun read(): String = file?.takeIf { it.exists() }?.readText() ?: ""

    @Synchronized
    fun clear() {
        file?.delete()
    }
}
