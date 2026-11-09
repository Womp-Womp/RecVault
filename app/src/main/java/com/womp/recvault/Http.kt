package com.womp.recvault

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Plain HttpURLConnection. No third-party networking code, no ads, no analytics. */
object Http {
    class Resp(val code: Int, val body: String, val headers: Map<String?, List<String>>) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        val ok get() = code in 200..299
    }

    /** HTTP status error with the server's own words, so the status screen can show the real reason. */
    class HttpError(val code: Int, body: String) : IOException("HTTP $code: ${body.take(300)}")

    fun call(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 30_000
            c.readTimeout = 120_000
            c.instanceFollowRedirects = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            return read(c)
        } finally {
            c.disconnect()
        }
    }

    /** Streams [length] bytes from [input] as the request body. */
    fun stream(
        method: String,
        url: String,
        headers: Map<String, String>,
        input: InputStream,
        length: Long,
    ): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 30_000
            c.readTimeout = 300_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.doOutput = true
            c.setFixedLengthStreamingMode(length)
            c.outputStream.use { out ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("file shorter than expected ($left bytes missing)")
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            return read(c)
        } finally {
            c.disconnect()
        }
    }

    private fun read(c: HttpURLConnection): Resp {
        val code = c.responseCode
        val s = (if (code >= 400) c.errorStream else c.inputStream)
        val text = s?.bufferedReader()?.use { it.readText() } ?: ""
        return Resp(code, text, c.headerFields)
    }

    fun form(vararg kv: Pair<String, String>): ByteArray =
        kv.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.toByteArray()

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
