package recvault.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Exhaustive over a small universe + randomized over a large one.
 * Each assertion is one of the theorems in lean/RecVault.lean, checked on the real Kotlin code.
 */
class PlannerTest {
    private val universe: List<LocalFile> = (0 until 6).map { i ->
        LocalFile("f$i.aac", 1000L + i, time = i * 10L, md5Hex = "m$i", quickXorB64 = "q$i")
    }

    private fun subsets(xs: List<LocalFile>): List<List<LocalFile>> =
        (0 until (1 shl xs.size)).map { mask -> xs.filterIndexed { i, _ -> mask and (1 shl i) != 0 } }

    /** Remote keys: real matches, plus decoys that share a name but have wrong size or wrong hash (corrupt uploads). */
    private fun remoteVariants(cloud: Cloud): List<Set<Key>> {
        val good = universe.map { it.keyFor(cloud) }
        val decoys = universe.flatMap { listOf(Key(it.name, it.size, "BAD"), Key(it.name, it.size + 1, it.keyFor(cloud).hash)) }
        val out = mutableListOf<Set<Key>>()
        for (g in subsets(universe)) for (d in listOf(emptyList(), decoys.take(3), decoys)) {
            out += (g.map { it.keyFor(cloud) } + d).toSet()
        }
        check(good.size == 6)
        return out
    }

    @Test fun exhaustiveTheorems() {
        var cases = 0
        for (cloud in Cloud.values()) for (L in subsets(universe)) for (R in remoteVariants(cloud)) {
            val p = Planner.plan(L, R, cloud)
            // plan_spec: f ∈ plan ↔ f ∈ L ∧ key(f) ∉ R
            for (f in universe) assertEquals(f in L && f.keyFor(cloud) !in R, f in p)
            // plan_nil_iff: plan = [] ↔ every local file verified
            assertEquals(p.isEmpty(), L.all { it.keyFor(cloud) in R })
            // decoys never count: a corrupt remote copy (wrong hash/size) never removes a file from the plan
            for (f in p) assertTrue(R.none { it == f.keyFor(cloud) })
            // after_sync: uploading+verifying exactly the plan covers everything
            val R2 = R + p.map { it.keyFor(cloud) }
            assertTrue(Planner.plan(L, R2, cloud).isEmpty())
            // alarm_silent_sound: no alarm ⇒ every file older than T is verified
            for (now in listOf(0L, 25L, 60L, 1000L)) for (T in listOf(0L, 15L, 40L)) {
                if (!Planner.alarm(now, T, L, R, cloud)) {
                    for (f in L) if (now - f.time > T) assertTrue(f.keyFor(cloud) in R)
                }
                // and conversely: alarm ⇒ some old file genuinely unverified (no false alarms)
                if (Planner.alarm(now, T, L, R, cloud)) {
                    assertTrue(L.any { now - it.time > T && it.keyFor(cloud) !in R })
                }
            }
            cases++
        }
        assertTrue(cases > 5000, "ran $cases cases")
    }

    /** Lean: alarm_antitone + stale_quiet_sound. The phone's stale list V ⊆ the cloud's true list R. */
    @Test fun staleListNeverHidesAGap() {
        var cases = 0
        val cloud = Cloud.DRIVE
        val keys = universe.map { it.keyFor(cloud) }
        for (L in subsets(universe)) for (r in subsets(universe)) {
            val R = r.map { it.keyFor(cloud) }.toSet()
            for (v in subsets(r)) {
                val V = v.map { it.keyFor(cloud) }.toSet()
                for (now in listOf(0L, 30L, 100L)) for (T in listOf(0L, 20L)) {
                    if (Planner.alarm(now, T, L, R, cloud)) assertTrue(Planner.alarm(now, T, L, V, cloud))
                    if (!Planner.alarm(now, T, L, V, cloud))
                        for (f in L) if (now - f.time > T) assertTrue(f.keyFor(cloud) in R)
                    cases++
                }
            }
        }
        assertTrue(keys.size == 6 && cases > 100_000, "ran $cases cases")
    }

    @Test fun randomized() {
        val rnd = java.util.Random(42)
        repeat(20_000) {
            val n = rnd.nextInt(60)
            val L = (0 until n).map { i -> LocalFile("r$i", rnd.nextInt(5).toLong(), rnd.nextInt(100).toLong(), "m${rnd.nextInt(4)}", "q${rnd.nextInt(4)}") }
            val R = (0 until rnd.nextInt(80)).map { Key("r${rnd.nextInt(70)}", rnd.nextInt(5).toLong(), "m${rnd.nextInt(4)}") }.toSet()
            val p = Planner.plan(L, R, Cloud.DRIVE)
            assertEquals(L.filter { Key(it.name, it.size, it.md5Hex) !in R }, p)
            assertFalse(p.any { it !in L }) // plan ⊆ local: never invents files
        }
    }
}
