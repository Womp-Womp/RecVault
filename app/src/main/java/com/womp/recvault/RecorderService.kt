package com.womp.recvault

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import recvault.core.Quality
import recvault.core.StallPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records continuously in 15-minute chunks.
 * Format is AAC in an ADTS stream (.aac): every ~23 ms frame stands alone, so if the phone dies
 * mid-chunk, everything written up to that moment still plays. (An .m4a would be unreadable.)
 */
class RecorderService : Service() {

    companion object {
        const val TAG = "RecVault"
        const val CHUNK_MS = 15 * 60 * 1000L
        const val ACTION_STOP = "com.womp.recvault.STOP"
        const val ACTION_ROTATE = "com.womp.recvault.ROTATE"

        /** The chunk being written right now. The uploader never touches it. */
        @Volatile var liveUri: Uri? = null
        /** Name of the chunk about to be / being written; set BEFORE the file exists. */
        @Volatile var liveName: String? = null
        @Volatile var running = false
        @Volatile var chunkStartedElapsed = 0L
        @Volatile var lastGapMs = -1L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RecorderService::class.java))
        }

        /** Close the current chunk and start a new one now (e.g. so a new quality applies immediately). */
        fun rotateNow(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_ROTATE))
        }

        fun stop(ctx: Context) {
            Prefs(ctx).wantRecording = false
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var wake: PowerManager.WakeLock? = null
    private var failures = 0
    private lateinit var prefs: Prefs

    private val rotate = Runnable { rotateChunk() }
    private var lastSize = -1L
    private var lastGrowthElapsed = 0L
    private var beats = 0

    private val heartbeat = object : Runnable {
        override fun run() {
            val fd = pfd
            if (recorder != null && fd != null) {
                val size = try { android.system.Os.fstat(fd.fileDescriptor).st_size } catch (_: Exception) { -1L }
                val now = SystemClock.elapsedRealtime()
                if (size > 0 && size != lastSize) {
                    lastSize = size
                    lastGrowthElapsed = now
                    prefs.lastHeartbeatMs = System.currentTimeMillis() // heartbeat = audio actually grew
                }
                if (StallPolicy.stalled(now - lastGrowthElapsed, now - chunkStartedElapsed)) {
                    // 32 kbps is ~4 KB/s; no growth for 90 s means the mic is not delivering audio.
                    onRecorderError("Mic stopped delivering audio (file not growing)")
                }
            }
            Notif.updateStatus(this@RecorderService, StatusText.short(this@RecorderService))
            if (++beats % 10 == 0) {
                // every ~5 min, check alarms from here too (don't rely only on WorkManager timing)
                Thread { SyncEngine.watchdog(applicationContext) }.start()
            }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ROTATE) {
            if (running) rotateChunk()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(Notif.ID_STATUS, Notif.status(this, "Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(Notif.ID_STATUS, Notif.status(this, "Starting…"))
            }
        } catch (e: Exception) {
            // Android blocks starting the mic from the background (e.g. after a reboot). Say so, loudly.
            Log.e(TAG, "startForeground refused", e)
            prefs.recorderError = "Android blocked background start: ${e.javaClass.simpleName}"
            Notif.alarm(this, Notif.ID_REC_STOPPED, "⚠ RECORDING IS OFF",
                "Android won't let recording start by itself. Tap here to resume.", resume = true)
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.wantRecording = true
        Notif.clear(this, Notif.ID_REC_STOPPED)
        if (!running) {
            running = true
            prefs.lastHeartbeatMs = System.currentTimeMillis() // fresh start: give the mic a moment
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RecVault:rec").apply { setReferenceCounted(false); acquire() }
            startChunk()
            handler.post(heartbeat)
        }
        return START_STICKY
    }

    private fun chunkName(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "RV_${prefs.deviceLabel}_$ts.aac"
    }

    private fun startChunk() {
        if (!running) return
        try {
            val name = chunkName()
            liveName = name // before the file exists, so no backup pass can catch it half-born
            val uri = ChunkStore.create(this, name)
            liveUri = uri // set BEFORE any byte is written, so the uploader never grabs a growing file
            val fd = contentResolver.openFileDescriptor(uri, "w") ?: error("no fd")
            pfd = fd
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            val q = Quality.fromKbps(prefs.qualityKbps)
            r.setAudioSamplingRate(q.sampleRate)
            r.setAudioEncodingBitRate(q.bitRate)
            r.setOutputFile(fd.fileDescriptor)
            r.setOnErrorListener { _, what, extra -> handler.post { onRecorderError("MediaRecorder error $what/$extra") } }
            r.prepare()
            r.start()
            recorder = r
            chunkStartedElapsed = SystemClock.elapsedRealtime()
            lastSize = -1L
            lastGrowthElapsed = chunkStartedElapsed
            failures = 0
            prefs.recorderError = null
            handler.removeCallbacks(rotate)
            handler.postDelayed(rotate, CHUNK_MS)
            Log.i(TAG, "chunk started $uri")
        } catch (e: Exception) {
            Log.e(TAG, "startChunk failed", e)
            closeChunk()
            onRecorderError("Could not start mic: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Stop current recorder and close its file. Never deletes anything. */
    private fun closeChunk() {
        handler.removeCallbacks(rotate)
        recorder?.let { r ->
            try { r.stop() } catch (e: Exception) { Log.w(TAG, "stop: ${e.message}") }
            try { r.release() } catch (_: Exception) {}
        }
        recorder = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        liveUri = null
        liveName = null
    }

    private fun rotateChunk() {
        val t0 = SystemClock.elapsedRealtime()
        closeChunk()
        startChunk()
        lastGapMs = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "rotated, gap ${lastGapMs}ms")
        SyncWorker.kick(this)
    }

    private fun onRecorderError(msg: String) {
        closeChunk()
        failures++
        prefs.recorderError = msg
        Log.e(TAG, "recorder error #$failures: $msg")
        if (failures >= 3) {
            Notif.alarm(this, Notif.ID_REC_STOPPED, "⚠ RECORDING TROUBLE",
                "$msg\nRetrying automatically (attempt $failures). Another app may be using the mic.")
        }
        SyncWorker.kick(this) // back up whatever was captured
        val delay = minOf(60_000L, 2_000L * failures)
        handler.postDelayed({ if (running && recorder == null) startChunk() }, delay)
    }

    private fun shutdown() {
        running = false
        handler.removeCallbacksAndMessages(null)
        closeChunk()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        SyncWorker.kick(this)
    }

    override fun onDestroy() {
        val wasRunning = running
        shutdown()
        if (wasRunning && prefs.wantRecording) {
            Notif.alarm(this, Notif.ID_REC_STOPPED, "⚠ RECORDING STOPPED",
                "The phone stopped RecVault. Tap here to resume recording.", resume = true)
        }
        super.onDestroy()
    }
}
