package recvault.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoliciesTest {
    @Test fun cacheNeverStoresEmptyOrDisagreeing() {
        for (h in -2L..50L) for (m in -2L..50L) {
            val c = HashCachePolicy.cacheable(h, m)
            assertEquals(h > 0 && h == m, c)
            if (c) assertTrue(h > 0) // Lean: cache_never_empty
        }
    }

    /** The bug this policy fixes: a pass hashes a half-born (0-byte) file. It must not be remembered. */
    @Test fun halfBornChunkIsNotCached() {
        assertFalse(HashCachePolicy.cacheable(hashedSize = 0, mediaStoreSize = 0))
        // MediaStore still says 0 while the real file is complete: also not cached, so it is re-read next pass
        assertFalse(HashCachePolicy.cacheable(hashedSize = 3_600_000, mediaStoreSize = 0))
        assertTrue(HashCachePolicy.cacheable(hashedSize = 3_600_000, mediaStoreSize = 3_600_000))
    }

    @Test fun stall() {
        assertFalse(StallPolicy.stalled(89_999, 1_000_000))
        assertTrue(StallPolicy.stalled(90_000, 1_000_000))
        assertFalse(StallPolicy.stalled(90_000, 30_000)) // fresh chunk, give it time
        for (g in 0L..200_000L step 5_000) for (s in 0L..200_000L step 5_000)
            assertEquals(g >= 90_000 && s >= 90_000, StallPolicy.stalled(g, s))
    }
}
