package com.james.assistant

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.io.File

data class Track(val uri: Uri, val title: String)
data class MusicFolder(val name: String, val tracks: List<Track>)

/**
 * Finds the playlists: every folder directly inside Music/ (songs in its subfolders count too).
 *
 * Two sources, so no folder goes missing:
 *  1. Android's media index (MediaStore): fast, has proper song titles and album art.
 *  2. The Music folder itself on disk: catches songs Android hasn't indexed yet (e.g. just
 *     copied from a laptop) and folders with no playable songs, which are listed as empty.
 * Songs found only on disk are handed to Android's media scanner, so next time they're indexed too.
 */
object MusicLibrary {

    const val DEFAULT_FOLDER = "Azarah_Default"

    /** The default folder's name before the app was renamed; still played if Azarah_Default doesn't exist. */
    const val OLD_DEFAULT_FOLDER = "James_Default"

    /** True for the folder that plays on connect (Azarah_Default, or the older James_Default). */
    fun isDefault(name: String) =
        name.equals(DEFAULT_FOLDER, ignoreCase = true) || name.equals(OLD_DEFAULT_FOLDER, ignoreCase = true)

    /** Not a real folder: every song in every Music folder, played shuffled ("Shuffle all"). */
    const val ALL_SONGS = "All songs"

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "3gp", "mka", "wma", "mp4a"
    )
    private const val MAX_DEPTH = 4
    private const val MAX_FILES = 5000

    /** Last scan, reused for a few seconds so back-to-back lookups don't re-read the whole library. */
    @Volatile private var cache: Pair<Long, List<MusicFolder>>? = null
    private const val CACHE_MS = 10_000L

    /** All playlists. Can take a moment with a big library: call it off the main thread. */
    fun folders(ctx: Context): List<MusicFolder> {
        val c = cache
        val now = android.os.SystemClock.elapsedRealtime()
        if (c != null && now - c.first < CACHE_MS) return c.second
        val fresh = scan(ctx)
        cache = now to fresh
        return fresh
    }

    private fun scan(ctx: Context): List<MusicFolder> {
        val byFolder = linkedMapOf<String, MutableList<Track>>()
        // "music/gospel/song.mp3" (lower case) for every song the media index knows about.
        val indexed = HashSet<String>()

        // ---- 1. Media index ----
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("Music/%"),
                "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val relPath = c.getString(pathCol) ?: continue          // "Music/Gospel/"
                    val display = c.getString(nameCol) ?: continue           // "song.mp3"
                    val folder = relPath.split('/').getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: display.substringBeforeLast('.')
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    byFolder.getOrPut(folder) { mutableListOf() }.add(Track(uri, title))
                    indexed += (relPath + display).lowercase()
                }
            }
        } catch (e: SecurityException) {
            Log.w(JamesService.TAG, "No permission to read the media index: ${e.message}")
        } catch (e: Exception) {
            Log.e(JamesService.TAG, "Reading the media index failed: ${e.message}", e)
        }

        // ---- 2. The Music folder on disk ----
        val notIndexed = mutableListOf<String>()
        try {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            root.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.forEach { dir ->
                    // Find a folder already seen in the index, whatever its upper/lower case.
                    val key = byFolder.keys.firstOrNull { it.equals(dir.name, ignoreCase = true) } ?: dir.name
                    val list = byFolder.getOrPut(key) { mutableListOf() }   // empty folders show up too
                    val extra = mutableListOf<Track>()
                    walk(dir, depth = 0) { file ->
                        val rel = "Music/" + file.relativeTo(root).path.replace(File.separatorChar, '/')
                        if (rel.lowercase() !in indexed) {
                            extra += Track(Uri.fromFile(file), file.nameWithoutExtension)
                            notIndexed += file.absolutePath
                        }
                    }
                    list += extra.sortedBy { it.title.lowercase() }
                }
        } catch (e: SecurityException) {
            Log.w(JamesService.TAG, "No permission to look inside Music: ${e.message}")
        } catch (e: Exception) {
            Log.e(JamesService.TAG, "Looking inside Music failed: ${e.message}", e)
        }

        if (notIndexed.isNotEmpty()) {
            Log.i(JamesService.TAG, "${notIndexed.size} songs aren't in the media index yet; asking Android to add them")
            try {
                MediaScannerConnection.scanFile(ctx.applicationContext, notIndexed.take(500).toTypedArray(), null, null)
            } catch (e: Exception) {
                Log.w(JamesService.TAG, "Media scan request failed: ${e.message}")
            }
        }

        return byFolder.map { (name, tracks) -> MusicFolder(name, tracks) }
            .sortedWith(compareBy({ !isDefault(it.name) }, { it.name.lowercase() }))
    }

    private fun walk(dir: File, depth: Int, onSong: (File) -> Unit) {
        if (depth > MAX_DEPTH) return
        if (File(dir, ".nomedia").exists() && depth > 0) return
        var count = 0
        dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
            if (count > MAX_FILES) return
            when {
                f.name.startsWith(".") -> {}
                f.isDirectory -> walk(f, depth + 1, onSong)
                f.extension.lowercase() in AUDIO_EXTENSIONS && f.length() > 0 -> {
                    onSong(f)
                    count++
                }
            }
        }
    }

    /** Finds a folder by name, ignoring upper/lower case. */
    fun folder(ctx: Context, name: String): MusicFolder? {
        val all = folders(ctx)
        if (name == ALL_SONGS) {
            val tracks = all.flatMap { it.tracks }
            return if (tracks.isEmpty()) null else MusicFolder(ALL_SONGS, tracks)
        }
        all.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        // Renamed app: an existing Music/James_Default folder still plays on connect.
        if (name.equals(DEFAULT_FOLDER, ignoreCase = true)) {
            return all.firstOrNull { it.name.equals(OLD_DEFAULT_FOLDER, ignoreCase = true) }
        }
        return null
    }

    /**
     * Album art for a song, or null if it has none. Works for indexed songs (content://)
     * and for songs found only on disk (file://). Call off the main thread.
     */
    fun loadArt(ctx: Context, uri: Uri, px: Int): Bitmap? {
        return try {
            if (uri.scheme == "file") {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(uri.path)
                    val bytes = mmr.embeddedPicture ?: return null
                    // Decode at roughly the size we need, not the full cover.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } finally {
                    try { mmr.release() } catch (_: Exception) {}
                }
            } else {
                ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
            }
        } catch (e: Exception) {
            null
        }
    }
}
