package recvault.core

/**
 * Recording quality presets. All AAC-LC, mono, in a crash-safe ADTS stream.
 * The user picks one; it applies from the next chunk (the app starts a new chunk right away).
 */
enum class Quality(val kbps: Int, val sampleRate: Int, val label: String, val blurb: String) {
    VOICE(32, 32_000, "Voice", "clear speech, smallest files"),
    STANDARD(64, 44_100, "Standard", "fuller voice, good default"),
    HIGH(128, 48_000, "High", "keeps the texture: laughs, breath, the room");

    val bitRate: Int get() = kbps * 1000

    /** Bytes for 24 hours of nonstop recording (CBR estimate; real files run within a few % of this). */
    val bytesPerDay: Long get() = kbps.toLong() * 1000 / 8 * 86_400

    /** e.g. "350 MB" or "1.4 GB" */
    val perDayText: String get() {
        val mb = bytesPerDay / 1_000_000.0
        return if (mb < 1000) "${(Math.round(mb / 10) * 10)} MB" else "%.1f GB".format(mb / 1000)
    }

    companion object {
        val DEFAULT = STANDARD
        fun fromKbps(k: Int): Quality = values().firstOrNull { it.kbps == k } ?: DEFAULT
    }
}
