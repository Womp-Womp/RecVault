package com.womp.recvault

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import recvault.core.Cloud
import recvault.core.Key
import recvault.core.LocalFile
import recvault.core.Planner
import recvault.core.keyFor
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One sync pass = for each cloud: list what the cloud verifiably has, upload the difference
 * (Planner.plan), re-list to confirm, then run the watchdog. Idempotent: running it twice,
 * or crashing halfway, can only cause a harmless re-upload, never a loss.
 */
object SyncEngine {
    const val TAG = "RecVault"
    /** "Every hour" promise + 15 min grace for the chunk that was still recording. */
    const val OVERDUE_MS = 75 * 60 * 1000L
    const val HEARTBEAT_STALE_MS = 3 * 60 * 1000L
    const val LOW_STORAGE_BYTES = 2L * 1024 * 1024 * 1024

    private val mutex = Mutex()

    fun client(ctx: Context, c: Cloud): CloudClient = when (c) {
        Cloud.DRIVE -> DriveClient(ctx)
        Cloud.ONEDRIVE -> OneDriveClient(ctx)
    }

    data class Local(val files: List<LocalFile>, val uris: Map<String, Uri>)

    fun scanLocal(ctx: Context): Local {
        val live = RecorderService.liveUri
        val liveName = RecorderService.liveName
        val files = ArrayList<LocalFile>()
        val uris = HashMap<String, Uri>()
        for (ch in ChunkStore.list(ctx)) {
            // still being written (MediaStore may have renamed a clash to "name (1).aac", hence the prefix)
            if (ch.uri == live || (liveName != null && ch.name.startsWith(liveName.removeSuffix(".aac")))) continue
            val h = try { ChunkStore.hashes(ctx, ch) } catch (e: Exception) { Log.w(TAG, "hash ${ch.name}: $e"); continue }
            if (h.size == 0L) continue // mic produced nothing; nothing to back up
            files += LocalFile(ch.name, h.size, ch.timeMs, h.md5Hex, h.quickXorB64)
            uris[ch.name] = ch.uri
        }
        files.sortBy { it.time }
        return Local(files, uris)
    }

    suspend fun runOnce(ctx: Context) = mutex.withLock {
        val prefs = Prefs(ctx)
        var local: Local? = null
        try {
            local = scanLocal(ctx)
            for (cloud in Cloud.values()) {
                if (!prefs.enabled(cloud)) continue
                syncCloud(ctx, prefs, cloud, local)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sync pass crashed", e)
            Notif.alarm(ctx, Notif.ID_INTERNAL, "⚠ RecVault hit an error",
                "${e.javaClass.simpleName}: ${e.message ?: ""}\nRecordings are safe on the phone; retrying every 15 min.")
        } finally {
            watchdog(ctx, local)
        }
    }

    private suspend fun syncCloud(ctx: Context, prefs: Prefs, cloud: Cloud, local: Local) {
        val now = System.currentTimeMillis()
        try {
            val client = client(ctx, cloud)
            client.prepare()
            val remote = client.listKeys()
            saveVerified(ctx, cloud, remote)
            val todo = Planner.plan(local.files, remote, cloud)
            var problem: String? = null
            for (f in todo) {
                try {
                    val got = client.upload(f, local.uris.getValue(f.name))
                    if (got != f.keyFor(cloud)) {
                        problem = "Fingerprint MISMATCH on ${f.name}; will re-upload"
                        Log.e(TAG, "$cloud mismatch: local=${f.keyFor(cloud)} cloud=$got")
                    } else {
                        addVerified(ctx, cloud, got)
                    }
                } catch (e: NeedsSignIn) {
                    throw e
                } catch (e: IOException) {
                    problem = friendly(e)
                    Log.w(TAG, "$cloud upload ${f.name}: $e")
                    break // network trouble: stop hammering, next pass resumes
                }
            }
            // Trust nothing we didn't just hear from the cloud: list again.
            val confirmed = client.listKeys()
            saveVerified(ctx, cloud, confirmed)
            val pending = Planner.plan(local.files, confirmed, cloud).size
            prefs.saveStatus(cloud, now, pending, if (pending == 0) null else problem)
        } catch (e: NeedsSignIn) {
            prefs.saveError(cloud, e.message ?: "sign-in needed")
        } catch (e: Exception) {
            Log.w(TAG, "$cloud sync failed", e)
            prefs.saveError(cloud, friendly(e))
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet"
        is java.net.SocketTimeoutException -> "Network timed out"
        is Http.HttpError -> e.message ?: "server error"
        else -> "${e.javaClass.simpleName}: ${e.message ?: ""}".take(200)
    }

    // ---- last-known verified set per cloud (only ever UNDER-states what the cloud has, so the alarm errs loud) ----

    private fun vFile(ctx: Context, c: Cloud) = File(ctx.filesDir, "verified_$c.tsv")

    @Synchronized fun loadVerified(ctx: Context, c: Cloud): MutableSet<Key> {
        val f = vFile(ctx, c)
        val s = HashSet<Key>()
        if (f.exists()) f.forEachLine { l ->
            val p = l.split('\t')
            if (p.size == 3) s += Key(p[0], p[1].toLong(), p[2])
        }
        return s
    }

    @Synchronized private fun saveVerified(ctx: Context, c: Cloud, keys: Set<Key>) {
        val tmp = File(ctx.filesDir, "verified_$c.tsv.tmp")
        tmp.bufferedWriter().use { w -> keys.forEach { w.write("${it.name}\t${it.size}\t${it.hash}\n") } }
        tmp.renameTo(vFile(ctx, c))
    }

    @Synchronized private fun addVerified(ctx: Context, c: Cloud, k: Key) {
        vFile(ctx, c).appendText("${k.name}\t${k.size}\t${k.hash}\n")
    }

    // ---- watchdog: the "never silent" half ----

    /** Never throws: if the check itself can't run, THAT is an alarm. */
    fun watchdog(ctx: Context, given: Local? = null) {
        try {
            watchdogInner(ctx, given ?: scanLocal(ctx))
        } catch (e: Exception) {
            Log.e(TAG, "watchdog failed", e)
            Notif.alarm(ctx, Notif.ID_INTERNAL, "⚠ RecVault can't check backups",
                "${e.javaClass.simpleName}: ${e.message ?: ""}\nOpen RecVault.")
        }
    }

    private fun watchdogInner(ctx: Context, local: Local) {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.US)

        for (cloud in Cloud.values()) {
            val id = if (cloud == Cloud.DRIVE) Notif.ID_DRIVE else Notif.ID_ONEDRIVE
            val name = if (cloud == Cloud.DRIVE) "Google Drive" else "OneDrive"
            if (!prefs.enabled(cloud)) { Notif.clear(ctx, id); continue }
            val verified = loadVerified(ctx, cloud)
            // Theorem alarm_silent_sound (lean/RecVault.lean): quiet => every file older than OVERDUE is verified.
            if (Planner.alarm(now, OVERDUE_MS, local.files, verified, cloud)) {
                val pend = Planner.plan(local.files, verified, cloud)
                val oldest = pend.minByOrNull { it.time }!!
                Notif.alarm(ctx, id, "⚠ NOT BACKED UP to $name",
                    "${pend.size} recording(s) not confirmed in $name. Oldest from ${fmt.format(Date(oldest.time))}.\n" +
                        "Reason: ${prefs.error(cloud) ?: "waiting for a connection"}\nThey are safe on this phone. Tap for details.")
            } else {
                Notif.clear(ctx, id)
            }
        }

        if (prefs.wantRecording) {
            val hbAge = now - prefs.lastHeartbeatMs
            when {
                !RecorderService.running -> Notif.alarm(ctx, Notif.ID_REC_STOPPED, "⚠ RECORDING IS OFF",
                    "RecVault was stopped by the phone. Tap here to resume.", resume = true)
                hbAge > HEARTBEAT_STALE_MS -> Notif.alarm(ctx, Notif.ID_REC_STOPPED, "⚠ RECORDING MAY BE STUCK",
                    "No audio written for ${hbAge / 60000} min. ${prefs.recorderError ?: ""} Tap to check.", resume = true)
                else -> Notif.clear(ctx, Notif.ID_REC_STOPPED)
            }
        } else {
            Notif.clear(ctx, Notif.ID_REC_STOPPED)
        }

        val free = freeBytes()
        if (free < LOW_STORAGE_BYTES) {
            Notif.alarm(ctx, Notif.ID_STORAGE, "⚠ PHONE STORAGE LOW",
                "Only ${free / (1024 * 1024)} MB free. Recording will stop when it's full. RecVault never deletes; free space yourself.")
        } else Notif.clear(ctx, Notif.ID_STORAGE)
    }

    fun freeBytes(): Long = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
}
