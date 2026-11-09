package com.womp.recvault

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import recvault.core.Key
import recvault.core.LocalFile
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Microsoft OneDrive via Graph. Scope Files.ReadWrite.AppFolder: RecVault only sees its own
 * app folder, OneDrive / Apps / RecVault / <device> /, nothing else in your OneDrive.
 * Sign-in is OAuth 2 with PKCE in the browser; no Microsoft SDK bundled.
 */
class OneDriveClient(private val ctx: Context) : CloudClient {
    companion object {
        const val AUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
        const val REDIRECT = "recvault://oauth"
        const val SCOPES = "Files.ReadWrite.AppFolder offline_access"
        const val BASE = "https://graph.microsoft.com/v1.0/me/drive/special/approot"
        const val FRAG = 320 * 1024 * 32 // 10 MiB, multiple of 320 KiB as Graph requires

        private fun b64url(b: ByteArray) =
            Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        /** Build the browser sign-in URL and remember the PKCE secret. */
        fun signInUrl(ctx: Context): String {
            val p = Prefs(ctx)
            val rnd = SecureRandom()
            val verifier = b64url(ByteArray(48).also { rnd.nextBytes(it) })
            val state = b64url(ByteArray(16).also { rnd.nextBytes(it) })
            p.msPkceVerifier = verifier
            p.msPkceState = state
            val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            return "$AUTH/authorize?client_id=${Http.enc(Config.MS_CLIENT_ID)}" +
                "&response_type=code&redirect_uri=${Http.enc(REDIRECT)}" +
                "&scope=${Http.enc(SCOPES)}&code_challenge=$challenge&code_challenge_method=S256" +
                "&state=$state&prompt=select_account"
        }

        /** Called with the recvault://oauth?code=… redirect. */
        suspend fun finishSignIn(ctx: Context, redirect: Uri) {
            val p = Prefs(ctx)
            redirect.getQueryParameter("error")?.let {
                throw IllegalStateException("Microsoft said: $it ${redirect.getQueryParameter("error_description") ?: ""}")
            }
            if (redirect.getQueryParameter("state") != p.msPkceState) throw IllegalStateException("sign-in state mismatch, try again")
            val code = redirect.getQueryParameter("code") ?: throw IllegalStateException("no code")
            val r = withContext(Dispatchers.IO) {
                Http.call(
                    "POST", "$AUTH/token", mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    Http.form(
                        "client_id" to Config.MS_CLIENT_ID,
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to REDIRECT,
                        "code_verifier" to (p.msPkceVerifier ?: ""),
                        "scope" to SCOPES,
                    ),
                )
            }
            if (!r.ok) throw Http.HttpError(r.code, r.body)
            saveTokens(p, JSONObject(r.body))
        }

        private fun saveTokens(p: Prefs, j: JSONObject) {
            j.optString("refresh_token", "").takeIf { it.isNotEmpty() }?.let { p.msRefreshToken = it }
            p.msAccessToken = j.getString("access_token")
            p.msAccessExpiryMs = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
        }
    }

    private val prefs = Prefs(ctx)
    private val label get() = prefs.deviceLabel

    private suspend fun token(force: Boolean = false): String {
        val at = prefs.msAccessToken
        if (!force && at != null && System.currentTimeMillis() < prefs.msAccessExpiryMs - 120_000) return at
        val rt = prefs.msRefreshToken ?: throw NeedsSignIn("OneDrive needs you to sign in (tap Connect OneDrive)")
        val r = withContext(Dispatchers.IO) {
            Http.call(
                "POST", "$AUTH/token", mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                Http.form("client_id" to Config.MS_CLIENT_ID, "grant_type" to "refresh_token",
                    "refresh_token" to rt, "scope" to SCOPES),
            )
        }
        if (r.code == 400 && r.body.contains("invalid_grant")) {
            prefs.msRefreshToken = null
            throw NeedsSignIn("OneDrive sign-in expired, tap Connect OneDrive")
        }
        if (!r.ok) throw Http.HttpError(r.code, r.body)
        saveTokens(prefs, JSONObject(r.body))
        return prefs.msAccessToken!!
    }

    private suspend fun api(method: String, url: String, json: JSONObject? = null): Http.Resp {
        val h = mutableMapOf("Authorization" to "Bearer ${token()}")
        if (json != null) h["Content-Type"] = "application/json"
        var r = withContext(Dispatchers.IO) { Http.call(method, url, h, json?.toString()?.toByteArray()) }
        if (r.code == 401) {
            h["Authorization"] = "Bearer ${token(force = true)}"
            r = withContext(Dispatchers.IO) { Http.call(method, url, h, json?.toString()?.toByteArray()) }
        }
        return r
    }

    override suspend fun prepare() {
        if (Config.MS_CLIENT_ID.isBlank()) throw NeedsSignIn("OneDrive not configured in this build")
        token()
        val r = api("GET", "$BASE:/$label")
        if (r.code == 404) {
            val c = api("POST", "$BASE/children", JSONObject().put("name", label).put("folder", JSONObject())
                .put("@microsoft.graph.conflictBehavior", "fail"))
            if (!c.ok && c.code != 409) throw Http.HttpError(c.code, c.body)
        } else if (!r.ok) throw Http.HttpError(r.code, r.body)
    }

    private fun keyOf(item: JSONObject): Key? {
        val file = item.optJSONObject("file") ?: return null
        val qx = file.optJSONObject("hashes")?.optString("quickXorHash", "") ?: ""
        return Key(item.getString("name"), item.optLong("size", -1), qx)
    }

    override suspend fun listKeys(): Set<Key> {
        val out = HashSet<Key>()
        var url: String? = "$BASE:/$label:/children?\$select=name,size,file&\$top=500"
        while (url != null) {
            val r = api("GET", url)
            if (!r.ok) throw Http.HttpError(r.code, r.body)
            val j = JSONObject(r.body)
            val arr = j.getJSONArray("value")
            for (i in 0 until arr.length()) keyOf(arr.getJSONObject(i))?.let { out += it }
            url = j.optString("@odata.nextLink", "").ifEmpty { null }
        }
        return out
    }

    override suspend fun upload(f: LocalFile, uri: Uri): Key {
        // "replace" can only ever overwrite a same-named copy whose hash did NOT match (a corrupt one):
        // the planner never uploads a file the cloud already holds correctly.
        val s = api("POST", "$BASE:/$label/${f.name}:/createUploadSession",
            JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace")))
        if (!s.ok) throw Http.HttpError(s.code, s.body)
        val uploadUrl = JSONObject(s.body).getString("uploadUrl")

        var last: Http.Resp? = null
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                var start = 0L
                while (start < f.size) {
                    val len = minOf(FRAG.toLong(), f.size - start)
                    val end = start + len - 1
                    // the upload URL is pre-authorized; Graph rejects an Authorization header here
                    val r = Http.stream("PUT", uploadUrl, mapOf("Content-Range" to "bytes $start-$end/${f.size}"), input, len)
                    if (r.code !in listOf(200, 201, 202)) throw Http.HttpError(r.code, r.body)
                    last = r
                    start += len
                }
            }
        }
        if (last?.code !in listOf(200, 201)) throw java.io.IOException("OneDrive did not confirm the upload finished")

        val check = api("GET", "$BASE:/$label/${f.name}?\$select=name,size,file")
        if (!check.ok) throw Http.HttpError(check.code, check.body)
        return keyOf(JSONObject(check.body)) ?: Key(f.name, -1, "")
    }
}
