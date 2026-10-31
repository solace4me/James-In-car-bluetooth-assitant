package com.james.assistant

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The offline English speech model that the "Hey Azarah" wake-word listener (Vosk) needs.
 * Downloaded once (~40 MB) instead of being built into the app, so the app stays small.
 * Stored in the app's private files; deleted if the app is uninstalled.
 */
object VoiceModel {

    const val URL_SMALL_EN = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val SIZE_MB = 40

    private fun root(ctx: Context) = File(ctx.filesDir, "vosk")
    private fun modelDir(ctx: Context) = File(root(ctx), "model")

    /** The unpacked model folder, or null if it isn't fully downloaded yet. */
    fun dir(ctx: Context): File? {
        val d = modelDir(ctx)
        return if (File(d, "READY").exists()) d else null
    }

    fun isReady(ctx: Context) = dir(ctx) != null

    // Download state, read by the setup screen.
    @Volatile var downloading = false
        private set
    /** 0..100 while downloading, -1 when not known. */
    @Volatile var percent = -1
        private set
    @Volatile var lastError: String? = null
        private set

    /** Starts the download in the background. [onDone] runs on that background thread. */
    fun download(ctx: Context, onDone: (ok: Boolean) -> Unit) {
        if (downloading || isReady(ctx)) return
        downloading = true
        percent = 0
        lastError = null
        val app = ctx.applicationContext
        Thread {
            val ok = try {
                fetchAndUnpack(app)
                true
            } catch (e: Exception) {
                Log.e(JamesService.TAG, "Voice model download failed: ${e.message}", e)
                lastError = e.message ?: "Download failed"
                false
            } finally {
                downloading = false
            }
            onDone(ok)
        }.start()
    }

    private fun fetchAndUnpack(ctx: Context) {
        val root = root(ctx).apply { mkdirs() }
        val zip = File(root, "model.zip")
        val tmp = File(root, "unpacking")
        tmp.deleteRecursively()

        // 1. Download
        // HttpURLConnection won't follow a redirect from http to https by itself, so follow by hand.
        var url = URL(URL_SMALL_EN)
        var conn: HttpURLConnection? = null
        for (attempt in 0..5) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val code = c.responseCode
            val next = if (code in 300..399) c.getHeaderField("Location") else null
            if (next != null) {
                c.disconnect()
                url = URL(url, next)
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                c.disconnect()
                throw IllegalStateException("Server answered $code")
            }
            conn = c
            break
        }
        if (conn == null) throw IllegalStateException("Too many redirects")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(zip).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) percent = ((done * 90) / total).toInt()   // 0..90 for the download
                }
            }
        }
        conn.disconnect()

        // 2. Unpack. The zip has one top folder (vosk-model-small-en-us-0.15/…): drop it.
        tmp.mkdirs()
        val base = tmp.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val inside = entry.name.substringAfter('/', "")
                if (inside.isEmpty()) continue
                val out = File(tmp, inside)
                if (!out.canonicalPath.startsWith(base)) continue   // ignore unsafe paths
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it) }
                }
            }
        }
        percent = 97

        // 3. Swap in and mark complete.
        val dest = modelDir(ctx)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IllegalStateException("Couldn't move the model into place")
        File(dest, "READY").writeText("ok")
        zip.delete()
        percent = 100
        Log.i(JamesService.TAG, "Voice model ready at $dest")
    }
}
