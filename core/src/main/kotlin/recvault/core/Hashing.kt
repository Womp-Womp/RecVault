package recvault.core

import java.io.InputStream
import java.security.MessageDigest

data class Hashes(val size: Long, val md5Hex: String, val quickXorB64: String)

/** One pass over the stream computes both clouds' fingerprints. */
fun hashStream(input: InputStream): Hashes {
    val md5 = MessageDigest.getInstance("MD5")
    val qx = QuickXorHash()
    val buf = ByteArray(256 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (n == 0) continue
        md5.update(buf, 0, n)
        qx.update(buf, 0, n)
        total += n
    }
    val hex = md5.digest().joinToString("") { "%02x".format(it) }
    return Hashes(total, hex, qx.digestBase64())
}
