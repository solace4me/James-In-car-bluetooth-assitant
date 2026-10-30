package com.james.assistant

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

data class Track(val uri: Uri, val title: String)
data class MusicFolder(val name: String, val tracks: List<Track>)

/**
 * Reads the phone's music through Android's media library (MediaStore), so it works
 * the same on every brand and on Android 10 and newer.
 * Each folder directly inside Music/ is a playlist; songs in its subfolders count too.
 */
object MusicLibrary {

    const val DEFAULT_FOLDER = "James_Default"

    /** Not a real folder: every song in every Music folder, played shuffled ("Shuffle all"). */
    const val ALL_SONGS = "All songs"

    fun folders(ctx: Context): List<MusicFolder> {
        val byFolder = linkedMapOf<String, MutableList<Track>>()
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
                    // "Music/James_Default/" -> "James_Default"
                    val parts = (c.getString(pathCol) ?: continue).split('/')
                    val folder = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: c.getString(nameCol)?.substringBeforeLast('.')
                        ?: "Track $id"
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    byFolder.getOrPut(folder) { mutableListOf() }.add(Track(uri, title))
                }
            }
        } catch (e: SecurityException) {
            Log.w(JamesService.TAG, "No permission to read music: ${e.message}")
        } catch (e: Exception) {
            Log.e(JamesService.TAG, "Reading music failed: ${e.message}", e)
        }
        return byFolder.map { (name, tracks) -> MusicFolder(name, tracks) }
            .sortedWith(compareBy({ !it.name.equals(DEFAULT_FOLDER, true) }, { it.name.lowercase() }))
    }

    /** Finds a folder by name, ignoring upper/lower case. */
    fun folder(ctx: Context, name: String): MusicFolder? {
        val all = folders(ctx)
        if (name == ALL_SONGS) {
            val tracks = all.flatMap { it.tracks }
            return if (tracks.isEmpty()) null else MusicFolder(ALL_SONGS, tracks)
        }
        return all.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
