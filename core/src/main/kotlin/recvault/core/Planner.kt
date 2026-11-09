package recvault.core

/**
 * The whole "what still needs backing up" brain. Pure functions, no I/O.
 * Mirrors lean/RecVault.lean definition-for-definition:
 *   plan L R      = L.filter (· ∉ R)            (Lean: `plan`)
 *   alarm now T L R = ∃ f ∈ plan L R, now - f.time > T   (Lean: `alarm`)
 *
 * A "Key" identifies verified content: same name, same size, same hash.
 * A remote file only counts if ALL THREE match what is on the phone.
 */
data class Key(val name: String, val size: Long, val hash: String)

data class LocalFile(
    val name: String,
    val size: Long,
    /** creation time, epoch millis */
    val time: Long,
    val md5Hex: String,
    val quickXorB64: String,
)

enum class Cloud { DRIVE, ONEDRIVE }

fun LocalFile.keyFor(cloud: Cloud): Key = when (cloud) {
    Cloud.DRIVE -> Key(name, size, md5Hex)
    Cloud.ONEDRIVE -> Key(name, size, quickXorB64)
}

object Planner {
    /** Files on the phone whose exact content the cloud has not confirmed. Order preserved (oldest first if input is). */
    fun plan(local: List<LocalFile>, remote: Set<Key>, cloud: Cloud): List<LocalFile> =
        local.filter { it.keyFor(cloud) !in remote }

    /** True iff some unconfirmed file is older than [thresholdMs]. Silence => everything older is confirmed (proved in Lean). */
    fun alarm(nowMs: Long, thresholdMs: Long, local: List<LocalFile>, remote: Set<Key>, cloud: Cloud): Boolean =
        plan(local, remote, cloud).any { nowMs - it.time > thresholdMs }

    /** Oldest unconfirmed file, for the human-readable alarm text. */
    fun oldestPending(local: List<LocalFile>, remote: Set<Key>, cloud: Cloud): LocalFile? =
        plan(local, remote, cloud).minByOrNull { it.time }
}
