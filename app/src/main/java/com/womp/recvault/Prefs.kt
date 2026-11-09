package com.womp.recvault

import android.content.Context
import android.os.Build
import recvault.core.Cloud

/** Small persistent settings + status. Critical flags use commit() (synchronous, survives a crash right after). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("rv", Context.MODE_PRIVATE)

    var wantRecording: Boolean
        get() = sp.getBoolean("wantRecording", false)
        set(v) { sp.edit().putBoolean("wantRecording", v).commit() }

    var deviceLabel: String
        get() = sp.getString("deviceLabel", null) ?: sanitize(Build.MODEL ?: "phone").also { deviceLabel = it }
        set(v) { sp.edit().putString("deviceLabel", sanitize(v)).commit() }

    var lastHeartbeatMs: Long
        get() = sp.getLong("hb", 0)
        set(v) { sp.edit().putLong("hb", v).apply() }

    var recorderError: String?
        get() = sp.getString("recErr", null)
        set(v) { sp.edit().putString("recErr", v).apply() }

    var googleAccount: String?
        get() = sp.getString("gAcct", null)
        set(v) { sp.edit().putString("gAcct", v).commit() }

    var driveRootId: String?
        get() = sp.getString("dRoot", null)
        set(v) { sp.edit().putString("dRoot", v).apply() }

    var driveDevId: String?
        get() = sp.getString("dDev", null)
        set(v) { sp.edit().putString("dDev", v).apply() }

    var msRefreshToken: String?
        get() = sp.getString("msRt", null)
        set(v) { sp.edit().putString("msRt", v).commit() }

    var msAccessToken: String?
        get() = sp.getString("msAt", null)
        set(v) { sp.edit().putString("msAt", v).apply() }

    var msAccessExpiryMs: Long
        get() = sp.getLong("msAtExp", 0)
        set(v) { sp.edit().putLong("msAtExp", v).apply() }

    var msPkceVerifier: String?
        get() = sp.getString("msPkce", null)
        set(v) { sp.edit().putString("msPkce", v).commit() }

    var msPkceState: String?
        get() = sp.getString("msState", null)
        set(v) { sp.edit().putString("msState", v).commit() }

    /** A cloud this build can't reach is OFF, so it can't raise a permanent false alarm. */
    fun configured(c: Cloud) = c != Cloud.ONEDRIVE || Config.MS_CLIENT_ID.isNotBlank()
    fun enabled(c: Cloud) = configured(c) && sp.getBoolean("en_$c", true)
    fun setEnabled(c: Cloud, v: Boolean) { sp.edit().putBoolean("en_$c", v).commit() }

    /** Last time this cloud reported "nothing pending". */
    fun allClearMs(c: Cloud) = sp.getLong("clear_$c", 0)
    fun lastCheckMs(c: Cloud) = sp.getLong("check_$c", 0)
    fun pending(c: Cloud) = sp.getInt("pend_$c", -1)
    fun error(c: Cloud): String? = sp.getString("err_$c", null)

    fun saveStatus(c: Cloud, nowMs: Long, pending: Int, error: String?) {
        sp.edit().apply {
            putLong("check_$c", nowMs)
            putInt("pend_$c", pending)
            putString("err_$c", error)
            if (pending == 0 && error == null) putLong("clear_$c", nowMs)
        }.apply()
    }

    fun saveError(c: Cloud, error: String) { sp.edit().putString("err_$c", error).apply() }

    companion object {
        fun sanitize(s: String): String =
            s.replace(Regex("[^A-Za-z0-9-]"), "-").trim('-').take(24).ifEmpty { "phone" }
    }
}
