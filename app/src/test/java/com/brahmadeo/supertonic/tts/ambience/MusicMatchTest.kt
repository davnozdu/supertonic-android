package com.brahmadeo.supertonic.tts.ambience

import org.junit.Assert.*
import org.junit.Test

class MusicMatchTest {
    private val request = "calm soft slow piano, 60 BPM, minor harmony, no vocals"
    @Test fun sameExplicitProfileIsAllowed() {
        assertTrue(MusicMatch.matches(request, "Calm piano instrumental music", "Soft slow music, minor, 61 BPM, no vocals", emptyList()))
    }
    @Test fun moodInstrumentsTempoAndModeCannotBeGuessed() {
        for (different in listOf(
            request.replace("calm", "tense"), request.replace("piano", "guitar"),
            request.replace("60 BPM", "85 BPM"), request.replace("minor", "major"))) {
            assertFalse(different, MusicMatch.matches(request, "Instrumental music", different, emptyList()))
        }
        assertFalse(MusicMatch.matches(request, "Stillness of Ink", "", emptyList()))
        assertNull(MusicMatch.query("Stillness of Ink"))
    }
    @Test fun speechAndSongsAreNeverMoodBeds() {
        assertFalse(MusicMatch.matches(request, "Calm piano song", request + ", female vocals", emptyList()))
        assertFalse(MusicMatch.matches(request, "Piano soundtrack", request.replace("no vocals", "spoken words"), emptyList()))
        assertFalse(MusicMatch.matches(request, "Piano music", "calm soft slow piano, minor, 60 BPM", emptyList()))
    }
    @Test fun noPercussionNeedsExplicitEvidence() {
        assertFalse(MusicMatch.matches(request + ", no drums", "Instrumental music", request, emptyList()))
        assertTrue(MusicMatch.matches(request + ", no drums", "Instrumental music", request + ", no percussion", emptyList()))
    }
    @Test fun intensitiesAndKeysCannotBeInterchanged() {
        assertFalse(MusicMatch.matches(request + ", low intensity", "Instrumental music", request, emptyList()))
        assertFalse(MusicMatch.matches(request, "Instrumental music", request + ", intensified", emptyList()))
        assertFalse(MusicMatch.matches(request + ", D minor", "Instrumental music", request + ", C minor", emptyList()))
    }
    @Test fun exactCachedRequestsIgnoreOnlyTechnicalRecordingHint() {
        assertTrue(MusicMatch.exact(request, request + ", high-fidelity stereo studio recording, crisp clear detailed highs, wide stereo"))
        assertFalse(MusicMatch.exact(request, request.replace("60", "55")))
    }
    @Test fun duplicateMoodsReuseWorkButKeepThreeVariants() {
        val clips = (1..3).flatMap { mood -> (0..2).map { variant ->
            MusicReuse.Clip("m${mood}_$variant.m4a", "calm piano variant $variant", 30f)
        } }
        val aliases = MusicReuse.duplicates(clips)
        assertEquals(3, aliases.size)
        assertEquals(6, aliases.values.sumOf { it.size })
        assertEquals(listOf("m2_0.m4a", "m3_0.m4a"), aliases["m1_0.m4a"])
    }
    @Test fun differentDurationsAndVariantsRemainSeparate() {
        val clips = listOf(MusicReuse.Clip("m1_0.m4a", "calm piano", 30f),
            MusicReuse.Clip("m2_0.m4a", "calm piano", 60f), MusicReuse.Clip("m1_1.m4a", "calm piano", 30f))
        assertTrue(MusicReuse.duplicates(clips).isEmpty())
    }
}
