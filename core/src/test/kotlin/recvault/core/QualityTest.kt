package recvault.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualityTest {
    @Test fun storageMath() {
        assertEquals(345_600_000L, Quality.VOICE.bytesPerDay)
        assertEquals(691_200_000L, Quality.STANDARD.bytesPerDay)
        assertEquals(1_382_400_000L, Quality.HIGH.bytesPerDay)
        assertEquals("350 MB", Quality.VOICE.perDayText)
        assertEquals("690 MB", Quality.STANDARD.perDayText)
        assertEquals("1.4 GB", Quality.HIGH.perDayText)
    }

    @Test fun lookupNeverFails() {
        for (q in Quality.values()) assertEquals(q, Quality.fromKbps(q.kbps))
        for (k in listOf(-1, 0, 31, 96, 256, Int.MAX_VALUE)) assertEquals(Quality.DEFAULT, Quality.fromKbps(k))
    }

    @Test fun presetsAreValidForAacLc() {
        // AAC-LC sample rates Android's encoder accepts, and a sane bits-per-sample range for mono
        val ok = setOf(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
        for (q in Quality.values()) {
            assertTrue(q.sampleRate in ok, "$q rate")
            val bitsPerSample = q.bitRate.toDouble() / q.sampleRate
            assertTrue(bitsPerSample in 0.5..6.0, "$q bits/sample $bitsPerSample")
        }
        // stall detection still sees growth well inside its 90 s window at the lowest rate
        assertTrue(Quality.VOICE.bitRate / 8 * 30 > 0)
    }
}
