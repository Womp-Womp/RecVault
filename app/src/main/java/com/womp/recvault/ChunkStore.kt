package com.womp.recvault

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import recvault.core.HashCachePolicy
import recvault.core.Hashes
import recvault.core.hashStream

/**
 * Recordings live in the PUBLIC folder Recordings/RecVault (Music/RecVault on Android 10-11),
 * so they survive even if RecVault is uninstalled. This app contains no delete code.
 */
object ChunkStore {
    data class Chunk(val uri: Uri, val name: String, val msSize: Long, val timeMs: Long, val modified: Long)

    fun relPath(): String =
        if (Build.VERSION.SDK_INT >= 31) "Recordings/RecVault/" else "Music/RecVault/"

    private fun collection(): Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun create(ctx: Context, displayName: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/aac")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath())
        }
        return ctx.contentResolver.insert(collection(), v)
            ?: throw IllegalStateException("MediaStore refused to create $displayName")
    }

    fun list(ctx: Context): List<Chunk> {
        val out = ArrayList<Chunk>()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        ctx.contentResolver.query(
            collection(), proj,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relPath()),
            "${MediaStore.MediaColumns.DATE_ADDED} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                if (!name.endsWith(".aac")) continue
                out += Chunk(
                    uri = Uri.withAppendedPath(collection(), id.toString()),
                    name = name,
                    msSize = c.getLong(2),
                    timeMs = c.getLong(3) * 1000,
                    modified = c.getLong(4),
                )
            }
        }
        return out
    }

    /** Hash cache so a month of recordings isn't re-read every 15 minutes. Immutable files => safe. */
    private val lock = Any()

    fun hashes(ctx: Context, ch: Chunk): Hashes = synchronized(lock) {
        val cache = loadCache(ctx)
        val key = "${ch.name}|${ch.msSize}|${ch.modified}"
        cache[key]?.let { return it }
        val h = ctx.contentResolver.openInputStream(ch.uri)?.use { hashStream(it) }
            ?: throw IllegalStateException("cannot open ${ch.name}")
        // Only remember results that can't be stale: non-empty AND agreeing with MediaStore's size.
        // So a real recording can never get stuck behind a cached "empty" result.
        if (HashCachePolicy.cacheable(h.size, ch.msSize)) {
            cache[key] = h
            saveCache(ctx, cache)
        }
        h
    }

    private var memo: MutableMap<String, Hashes>? = null

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "hashes.tsv")

    private fun loadCache(ctx: Context): MutableMap<String, Hashes> {
        memo?.let { return it }
        val m = HashMap<String, Hashes>()
        val f = cacheFile(ctx)
        if (f.exists()) f.forEachLine { line ->
            val p = line.split('\t')
            if (p.size == 4) m[p[0]] = Hashes(p[1].toLong(), p[2], p[3])
        }
        memo = m
        return m
    }

    private fun saveCache(ctx: Context, m: Map<String, Hashes>) {
        val tmp = File(ctx.filesDir, "hashes.tsv.tmp")
        tmp.bufferedWriter().use { w ->
            for ((k, h) in m) w.write("$k\t${h.size}\t${h.md5Hex}\t${h.quickXorB64}\n")
        }
        tmp.renameTo(cacheFile(ctx))
    }
}
