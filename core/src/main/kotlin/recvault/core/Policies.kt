package recvault.core

/**
 * Small decision rules the Android layer calls. Kept pure so they are tested here
 * and modeled in lean/RecVault.lean.
 */
object HashCachePolicy {
    /** Cache a hash only if it can't be stale: the file had bytes, and MediaStore agreed on the size.
     *  Lean: `cacheable` + `cache_never_empty`. */
    fun cacheable(hashedSize: Long, mediaStoreSize: Long): Boolean =
        hashedSize > 0 && hashedSize == mediaStoreSize
}

object StallPolicy {
    const val STALL_MS = 90_000L

    /** The recording file hasn't grown for 90 s (and the chunk is at least that old): the mic is not delivering. */
    fun stalled(msSinceGrowth: Long, msSinceChunkStart: Long): Boolean =
        msSinceGrowth >= STALL_MS && msSinceChunkStart >= STALL_MS
}
