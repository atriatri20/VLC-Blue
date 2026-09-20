package org.videolan.vlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.videolan.vlc.gui.browser.BrowserThumbnails
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure-logic coverage for the 1.0.2 thumbnail fixes (T1/T4/T5 in
 * 1.0.2-设计.md): the disk-aware stale anchor, the forced-refresh lifecycle
 * and backoff pinning, and the size cap on the state maps. Plain JUnit4 -
 * minSdk 17 is below Robolectric 4.3.1's floor (SDK 21), and the pure logic
 * needs no Android runtime (BrowserThumbnails builds its Handler lazily).
 */
@RunWith(JUnit4::class)
class BrowserThumbnailsTest {

    companion object {
        private const val STALE_MS = 60L * 60L * 1000L
        private const val BASE_MS = 60L * 1000L
        private const val MAX_MS = 30L * 60L * 1000L
        private const val FORCED_WINDOW_MS = 5 * 60 * 1000L
    }

    private fun now() = System.currentTimeMillis()

    // region T1 - stale anchor = max(memory time, disk mtime)

    @Test
    fun stale_memoryExpiredButDiskYoung_notStale() {
        val now = now()
        assertFalse(BrowserThumbnails.isStaleForTest(now - 2 * STALE_MS, now - 1000L, forced = false, now = now))
    }

    @Test
    fun stale_memoryAndDiskBothExpired_stale() {
        val now = now()
        assertTrue(BrowserThumbnails.isStaleForTest(now - 2 * STALE_MS, now - 2 * STALE_MS, forced = false, now = now))
    }

    @Test
    fun stale_bothYoung_notStale() {
        val now = now()
        assertFalse(BrowserThumbnails.isStaleForTest(now - 1000L, now - 2000L, forced = false, now = now))
    }

    @Test
    fun stale_forced_alwaysStaleEvenWhenFresh() {
        val now = now()
        assertTrue(BrowserThumbnails.isStaleForTest(now, now, forced = true, now = now))
    }

    @Test
    fun stale_nothingCached_stale() {
        assertTrue(BrowserThumbnails.isStaleForTest(0L, 0L, forced = false, now = now()))
    }

    // endregion

    // region T4 - backoff curve and forced failure pinning

    @Test
    fun negativeDelay_doublesPerStrike() {
        assertEquals(BASE_MS, BrowserThumbnails.negativeDelayMs(1))
        assertEquals(2 * BASE_MS, BrowserThumbnails.negativeDelayMs(2))
        assertEquals(4 * BASE_MS, BrowserThumbnails.negativeDelayMs(3))
        assertEquals(8 * BASE_MS, BrowserThumbnails.negativeDelayMs(4))
        assertEquals(16 * BASE_MS, BrowserThumbnails.negativeDelayMs(5))
    }

    @Test
    fun negativeDelay_cappedAtThirtyMinutes() {
        assertEquals(MAX_MS, BrowserThumbnails.negativeDelayMs(6))
    }

    @Test
    fun noteFailure_normalAccumulatesStrikes() {
        val key = "test_normal_${now()}"
        val now = now()
        try {
            BrowserThumbnails.noteFailure(key, forced = false, now = now)
            assertEquals(1, BrowserThumbnails.debugStrikes(key))
            assertEquals(now + BASE_MS, BrowserThumbnails.debugNegativeUntil(key))

            BrowserThumbnails.noteFailure(key, forced = false, now = now)
            assertEquals(2, BrowserThumbnails.debugStrikes(key))
            assertEquals(now + 2 * BASE_MS, BrowserThumbnails.debugNegativeUntil(key))
        } finally {
            BrowserThumbnails.debugReset(key)
        }
    }

    @Test
    fun noteFailure_strikesCappedAtSix() {
        val key = "test_cap_${now()}"
        val now = now()
        try {
            repeat(10) { BrowserThumbnails.noteFailure(key, forced = false, now = now) }
            assertEquals(6, BrowserThumbnails.debugStrikes(key))
            assertEquals(now + MAX_MS, BrowserThumbnails.debugNegativeUntil(key))
        } finally {
            BrowserThumbnails.debugReset(key)
        }
    }

    @Test
    fun noteFailure_forcedPinsSingleStrike() {
        val key = "test_forced_${now()}"
        val now = now()
        try {
            // a forced refresh failing after earlier failures must not extend the curve
            BrowserThumbnails.noteFailure(key, forced = false, now = now)
            BrowserThumbnails.noteFailure(key, forced = false, now = now)
            assertEquals(2, BrowserThumbnails.debugStrikes(key))

            BrowserThumbnails.noteFailure(key, forced = true, now = now)
            assertEquals(1, BrowserThumbnails.debugStrikes(key))
            assertEquals(now + BASE_MS, BrowserThumbnails.debugNegativeUntil(key))

            // and it stays pinned no matter how often the forced attempt fails
            BrowserThumbnails.noteFailure(key, forced = true, now = now)
            assertEquals(1, BrowserThumbnails.debugStrikes(key))
        } finally {
            BrowserThumbnails.debugReset(key)
        }
    }

    // endregion

    // region T4b - a forced mark must not outlive the decode it asked for

    @Test
    fun forced_withinWindow_keepsYoungEntryStale() {
        val key = "test_forcedpending_${now()}"
        val now = now()
        try {
            BrowserThumbnails.debugForce(key, now - 1000L)
            // memory and disk are both young: only the pending request makes it stale
            assertTrue(BrowserThumbnails.isStale(key, diskModified = now, now = now))
            assertTrue(BrowserThumbnails.debugForced(key))
        } finally {
            BrowserThumbnails.debugReset(key)
        }
    }

    @Test
    fun forced_afterWindow_isDroppedSoTheRowSettles() {
        val key = "test_forcedlost_${now()}"
        val now = now()
        try {
            // older than the window: the decode it was meant to trigger never landed
            // (host unreachable / negative cache / candidate not parsed yet), and a
            // permanent mark would re-decode the row on every single rebind
            BrowserThumbnails.debugForce(key, now - FORCED_WINDOW_MS - 1000L)
            assertFalse(BrowserThumbnails.isStale(key, diskModified = now, now = now))
            assertFalse(BrowserThumbnails.debugForced(key))
            assertFalse(BrowserThumbnails.isStale(key, diskModified = now, now = now))
        } finally {
            BrowserThumbnails.debugReset(key)
        }
    }

    // endregion

    // region T5 - map size cap

    @Test
    fun putCapped_evictsBeyondCap() {
        val map = ConcurrentHashMap<String, Long>()
        val cap = 64
        for (i in 0 until cap + 10) with(BrowserThumbnails) { map.putCapped("k$i", i.toLong(), cap) }
        // approximate LRU evicts keys.firstOrNull() (hash-bucket order), so the most
        // recent key is NOT guaranteed to survive; the real contract is the cap holds
        // and surviving entries are never corrupted.
        assertEquals(cap, map.size)
        map.forEach { (k, v) -> assertEquals(k.removePrefix("k").toLong(), v) }
    }

    @Test
    fun putCapped_neverExceedsCap() {
        val map = ConcurrentHashMap<String, Long>()
        val cap = 4096
        for (i in 0 until cap + 1000) with(BrowserThumbnails) { map.putCapped("k$i", i.toLong()) }
        assertEquals(cap, map.size)
    }

    // endregion
}
