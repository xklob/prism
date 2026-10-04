package com.caleb.prism

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

class SongTimingTest {
    @Test fun playbackClockAccountsForSpeedPauseSeekAndUnknownPosition() {
        val song = SongIdentity("Test", "Artist", 200000)
        val playing = SongPlayback(song, "player", "id", 30000, 1.25, true, 100.0)
        assertEquals(35000L, playing.positionAt(104.0))
        assertEquals(30000L, playing.copy(playing = false).positionAt(104.0))
        assertEquals(200000L, playing.positionAt(1000.0))
        assertNull(playing.copy(positionMs = null).positionAt(101.0))
        assertFalse(playing.copy(positionMs = 35000, sampledAt = 104.0).discontinuityFrom(playing, 104.0))
        assertTrue(playing.copy(positionMs = 65000, sampledAt = 104.0).discontinuityFrom(playing, 104.0))
        assertTrue(playing.copy(playing = false).discontinuityFrom(playing, 100.0))
    }

    @Test fun matchingRejectsWrongArtistRemixAndDifferentLength() {
        val query = SongIdentity("Wake Me Up", "Avicii", 247426)
        val exact = CatalogSong(query.copy(spotifyId = "0nrRP2bk19rLc0orkWPQk2", isrc = "SEUM71301326"), 124.08, "id")
        assertEquals(exact, SongMatching.choose(query, listOf(exact)))
        assertNull(SongMatching.choose(query.copy(title = "Wake Me Up (Club Remix)"), listOf(exact)))
        assertNull(SongMatching.choose(query.copy(artist = "Different Artist"), listOf(exact)))
        assertNull(SongMatching.choose(query.copy(durationMs = 300000), listOf(exact)))
        assertNull(SongMatching.choose(query.copy(artist = ""), listOf(exact)))
        assertEquals(exact, SongMatching.choose(query.copy(spotifyId = exact.song.spotifyId, artist = ""), listOf(exact)))
        assertEquals(exact, SongMatching.choose(query.copy(isrc = "SEUM71301326", artist = ""), listOf(exact)))
        assertNull(SongMatching.choose(query, listOf(exact, exact.copy(song = exact.song.copy(isrc = "OTHER")))))
        assertNotNull(SongMatching.choose(query, listOf(exact.copy(song = exact.song.copy(artist = "Avicii / Aloe Blacc")))))
    }

    @Test fun parsesOnlyTrackIdentifiersAndValidTimecodes() {
        val id = "0nrRP2bk19rLc0orkWPQk2"
        assertEquals(id, SongMatching.spotifyId("spotify:track:$id"))
        assertEquals(id, SongMatching.spotifyId("https://open.spotify.com/track/$id?si=example"))
        assertNull(SongMatching.spotifyId("https://example.com/track/$id"))
        assertNull(SongMatching.spotifyId("spotify:album:$id"))
        assertEquals(152.0, SongMatching.timecode("02:32")!!, 0.0)
        assertEquals(3752.5, SongMatching.timecode("1:02:32.5")!!, 0.0)
        assertNull(SongMatching.timecode("00:99")); assertNull(SongMatching.timecode("NaN:00"))
    }

    @Test fun requestBudgetCountsFailuresAndManualRetriesToo() {
        val budget = RecognitionBudget(3)
        assertTrue(budget.acquire(100.0))
        assertFalse(budget.acquire(110.0))
        assertTrue(budget.acquire(130.0)); assertTrue(budget.acquire(160.0))
        assertEquals(0, budget.remaining(1000.0))
        assertFalse(budget.acquire(200.0))
        val restored = RecognitionBudget(3, budget.timestamps())
        assertFalse(restored.acquire(200.0))
        assertEquals(2700.0, budget.waitSeconds(1000.0), 0.0)
        assertTrue(budget.acquire(3700.0)); assertEquals(0, budget.remaining(3700.0))
    }

    @Test fun catalogTempoAloneCannotInventBeatsOrBars() {
        for (periodicity in listOf(false, true)) {
            val tracker = RhythmTracker(periodicity)
            tracker.setTempoHint(TempoHint(128.0, "track"))
            repeat(1500) { tracker.observe(it * .02, 0f, 0f, true) }
            val state = tracker.state(29.98)
            assertFalse(state.locked); assertFalse(state.barLocked); assertFalse(state.tempoAssisted)
        }
    }

    @Test fun strongAudioCanRejectAnIncorrectCatalogTempoAndManualTimingWins() {
        for (periodicity in listOf(false, true)) {
            val tracker = RhythmTracker(periodicity)
            tracker.setTempoHint(TempoHint(173.0, "wrong-version"))
            repeat(1500) { i ->
                val time = i * .02
                val phase = time * 2 - round(time * 2)
                val beat = exp(-.5 * (phase / .055).pow(2)).toFloat()
                val down = if (floor(time * 2 + .5).toInt() % 4 == 0) beat * .85f else 0f
                tracker.observe(time, beat - down, down, true)
            }
            assertEquals(120f, tracker.state(29.98).bpm, 1.5f)
            assertFalse(tracker.state(29.98).tempoAssisted)
            tracker.tap(30.0); tracker.tap(30.6); tracker.tap(31.2)
            tracker.setTempoHint(TempoHint(128.0, "new-track")); tracker.discontinuity()
            assertTrue(tracker.state(31.2).manualTempo)
            assertEquals(100f, tracker.state(31.2).bpm, .01f)
        }
    }

    @Test fun catalogPriorDoesNotDragAPitchedTrackBackToItsOriginalTempo() {
        for (periodicity in listOf(false, true)) {
            val tracker = RhythmTracker(periodicity)
            tracker.setTempoHint(TempoHint(124.0, "original-tempo"))
            repeat(1500) { i ->
                val time = i * .02
                val phase = time * 128 / 60 - kotlin.math.round(time * 128 / 60)
                val beat = kotlin.math.exp(-.5 * (phase / .06).pow(2)).toFloat()
                val down = if (kotlin.math.floor(time * 128 / 60 + .5).toInt() % 4 == 0) beat * .85f else 0f
                tracker.observe(time, beat - down, down, true)
            }
            assertEquals(128f, tracker.state(29.98).bpm, .6f)
        }
    }
}
