package com.womp.recvault

import android.accounts.Account
import android.content.Context
import android.net.Uri
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import recvault.core.Key
import recvault.core.LocalFile

/**
 * Google Drive v3 REST. Scope is drive.file: RecVault can ONLY see files it created itself,
 * nothing else in your Drive. Files land in  My Drive / RecVault / <device> /
 */
class DriveClient(private val ctx: Context) : CloudClient {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER = "application/vnd.google-apps.folder"

        fun request(account: String?): AuthorizationRequest {
            val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
            if (account != null) b.setAccount(Account(account, "com.google"))
            return b.build()
        }
    }

    private val prefs = Prefs(ctx)
    private var token: String? = null

    private suspend fun fetchToken(): String {
        val res = Identity.getAuthorizationClient(ctx).authorize(request(prefs.googleAccount)).await()
        if (res.hasResolution()) throw NeedsSignIn("Google Drive needs you to sign in (tap Connect Google Drive)")
        return res.accessToken ?: throw NeedsSignIn("Google gave no access token")
    }

    /** One automatic retry with a fresh token on 401. */
    private suspend fun api(method: String, url: String, json: JSONObject? = null): Http.Resp {
        val t = token ?: fetchToken().also { token = it }
        val headers = mutableMapOf("Authorization" to "Bearer $t")
        if (json != null) headers["Content-Type"] = "application/json; charset=UTF-8"
        var r = withContext(Dispatchers.IO) { Http.call(method, url, headers, json?.toString()?.toByteArray()) }
        if (r.code == 401) {
            withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(ctx, t) } }
            val t2 = fetchToken().also { token = it }
            headers["Authorization"] = "Bearer $t2"
            r = withContext(Dispatchers.IO) { Http.call(method, url, headers, json?.toString()?.toByteArray()) }
        }
        return r
    }

    private fun q(s: String) = Http.enc(s)

    private suspend fun findFolder(name: String, parent: String): String? {
        val query = "name = '$name' and mimeType = '$FOLDER' and '$parent' in parents and trashed = false"
        val r = api("GET", "$API/files?q=${q(query)}&fields=files(id)&spaces=drive")
        if (!r.ok) throw Http.HttpError(r.code, r.body)
        val files = JSONObject(r.body).getJSONArray("files")
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private suspend fun createFolder(name: String, parent: String): String {
        val body = JSONObject().put("name", name).put("mimeType", FOLDER)
            .put("parents", org.json.JSONArray().put(parent))
        val r = api("POST", "$API/files?fields=id", body)
        if (!r.ok) throw Http.HttpError(r.code, r.body)
        return JSONObject(r.body).getString("id")
    }

    private suspend fun stillThere(id: String?): Boolean {
        if (id == null) return false
        val r = api("GET", "$API/files/$id?fields=id,trashed")
        return r.ok && !JSONObject(r.body).optBoolean("trashed", false)
    }

    private lateinit var devFolder: String

    override suspend fun prepare() {
        token = fetchToken()
        if (stillThere(prefs.driveDevId)) { devFolder = prefs.driveDevId!!; return }
        val root = (if (stillThere(prefs.driveRootId)) prefs.driveRootId else null)
            ?: findFolder("RecVault", "root") ?: createFolder("RecVault", "root")
        prefs.driveRootId = root
        val label = prefs.deviceLabel
        devFolder = findFolder(label, root) ?: createFolder(label, root)
        prefs.driveDevId = devFolder
    }

    override suspend fun listKeys(): Set<Key> {
        val out = HashSet<Key>()
        var page: String? = null
        do {
            val query = "'$devFolder' in parents and trashed = false"
            var url = "$API/files?q=${q(query)}&pageSize=1000&spaces=drive" +
                "&fields=${q("nextPageToken,files(name,size,md5Checksum)")}"
            if (page != null) url += "&pageToken=${q(page)}"
            val r = api("GET", url)
            if (!r.ok) throw Http.HttpError(r.code, r.body)
            val j = JSONObject(r.body)
            val arr = j.getJSONArray("files")
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (!f.has("md5Checksum")) continue
                out += Key(f.getString("name"), f.optString("size", "-1").toLong(), f.getString("md5Checksum"))
            }
            page = j.optString("nextPageToken", "").ifEmpty { null }
        } while (page != null)
        return out
    }

    override suspend fun upload(f: LocalFile, uri: Uri): Key {
        // 1. open a resumable session
        val meta = JSONObject().put("name", f.name).put("parents", org.json.JSONArray().put(devFolder))
        val t = token ?: fetchToken().also { token = it }
        val init = withContext(Dispatchers.IO) {
            Http.call(
                "POST", "$UPLOAD/files?uploadType=resumable&fields=${q("id,name,size,md5Checksum")}",
                mapOf(
                    "Authorization" to "Bearer $t",
                    "Content-Type" to "application/json; charset=UTF-8",
                    "X-Upload-Content-Type" to "audio/aac",
                    "X-Upload-Content-Length" to f.size.toString(),
                ),
                meta.toString().toByteArray(),
            )
        }
        if (init.code == 401) { token = null; throw Http.HttpError(401, init.body) }
        if (!init.ok) throw Http.HttpError(init.code, init.body)
        val session = init.header("Location") ?: throw java.io.IOException("Drive gave no upload URL")

        // 2. send the bytes
        val put = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                Http.stream("PUT", session, mapOf("Content-Type" to "audio/aac"), input, f.size)
            }
        }
        if (!put.ok) throw Http.HttpError(put.code, put.body)
        val id = JSONObject(put.body).getString("id")

        // 3. ask Drive, separately, what it actually has
        val check = api("GET", "$API/files/$id?fields=${q("name,size,md5Checksum")}")
        if (!check.ok) throw Http.HttpError(check.code, check.body)
        val c = JSONObject(check.body)
        return Key(c.getString("name"), c.optString("size", "-1").toLong(), c.optString("md5Checksum", ""))
    }
}
