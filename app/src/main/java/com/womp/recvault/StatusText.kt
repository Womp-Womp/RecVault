package com.womp.recvault

import android.content.Context
import android.os.SystemClock
import recvault.core.Cloud
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StatusText {
    private fun ago(ms: Long): String {
        if (ms <= 0) return "never"
        val m = (System.currentTimeMillis() - ms) / 60000
        return when {
            m < 1 -> "just now"
            m < 60 -> "${m}m ago"
            else -> "${m / 60}h ${m % 60}m ago"
        }
    }

    private fun cloudLine(ctx: Context, c: Cloud): String {
        val p = Prefs(ctx)
        val name = if (c == Cloud.DRIVE) "Drive" else "OneDrive"
        if (!p.enabled(c)) return "$name: off"
        val pend = p.pending(c)
        val mark = if (pend == 0 && p.error(c) == null) "✓" else "…"
        val tail = if (pend > 0) " · $pend waiting" else ""
        return "$name $mark all safe ${ago(p.allClearMs(c))}$tail"
    }

    fun short(ctx: Context): String {
        val el = (SystemClock.elapsedRealtime() - RecorderService.chunkStartedElapsed) / 1000
        val rec = if (RecorderService.liveUri != null) "● REC chunk ${el / 60}:${"%02d".format(el % 60)}" else "○ mic retrying"
        return "$rec\n${cloudLine(ctx, Cloud.DRIVE)}\n${cloudLine(ctx, Cloud.ONEDRIVE)}"
    }

    fun long(ctx: Context): String {
        val p = Prefs(ctx)
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("EEE MMM d, h:mm:ss a", Locale.US)
        sb.append(
            when {
                RecorderService.running && RecorderService.liveUri != null -> {
                    val el = (SystemClock.elapsedRealtime() - RecorderService.chunkStartedElapsed) / 1000
                    "● RECORDING (chunk ${el / 60}:${"%02d".format(el % 60)} of 15:00)"
                }
                RecorderService.running -> "○ RECORDER RETRYING: ${p.recorderError ?: ""}"
                p.wantRecording -> "⚠ RECORDING IS OFF (phone stopped it): tap Start"
                else -> "■ Not recording"
            }
        ).append("\n")
        if (RecorderService.lastGapMs >= 0) sb.append("   last chunk switch gap: ${RecorderService.lastGapMs} ms\n")
        sb.append("   device label: ${p.deviceLabel}\n\n")
        for (c in Cloud.values()) {
            val name = if (c == Cloud.DRIVE) "GOOGLE DRIVE" else "ONEDRIVE"
            sb.append("$name\n")
            if (!p.enabled(c)) { sb.append("   turned off\n\n"); continue }
            sb.append("   all safe as of: ${ago(p.allClearMs(c))}\n")
            sb.append("   last check: ${if (p.lastCheckMs(c) > 0) fmt.format(Date(p.lastCheckMs(c))) else "never"}\n")
            val pend = p.pending(c)
            sb.append("   waiting to upload: ${if (pend < 0) "?" else pend}\n")
            p.error(c)?.let { sb.append("   problem: $it\n") }
            sb.append("\n")
        }
        sb.append("Phone free space: ${SyncEngine.freeBytes() / (1024 * 1024 * 1024)} GB\n")
        sb.append("Recordings folder: ${ChunkStore.relPath()}\n")
        return sb.toString()
    }
}
