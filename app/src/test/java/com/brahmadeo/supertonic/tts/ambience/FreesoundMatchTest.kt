package com.brahmadeo.supertonic.tts.ambience
import org.junit.Assert.*
import org.junit.Test
class FreesoundMatchTest {
 @Test fun requiresObjectActionAndMaterial() {
  assertTrue(FreesoundMatch.event("door_creak_wood", "Old wooden door creaking", emptyList()))
  assertFalse(FreesoundMatch.event("door_creak_wood", "Metal door creaking", emptyList()))
  assertFalse(FreesoundMatch.event("door_open", "door closing", emptyList()))
  assertFalse(FreesoundMatch.event("car_horn", "Telephone ringtone", listOf("horn")))
 }
 @Test fun acceptsTagsButNeverDescriptionAnecdotes() {
  assertTrue(FreesoundMatch.event("door_slam", "Take 4", listOf("door", "slamming")))
  assertFalse(FreesoundMatch.event("abacus", "male vocal sample", listOf("music")))
  assertFalse(FreesoundMatch.event("door_slam", "Door slam synth remix", emptyList()))
 }
 @Test fun licensesAreWhitelistedByFullUrl() {
  assertTrue(FreesoundMatch.allowedLicense("https://creativecommons.org/licenses/by/4.0/"))
  assertTrue(FreesoundMatch.allowedLicense("http://creativecommons.org/licenses/by-nc/3.0/"))
  assertTrue(FreesoundMatch.allowedLicense("http://creativecommons.org/publicdomain/zero/1.0/"))
  assertFalse(FreesoundMatch.allowedLicense("https://example.com/CC0"))
  assertFalse(FreesoundMatch.allowedLicense("https://creativecommons.org/licenses/sampling+/1.0/"))
 }
 @Test fun eventWindowFindsLateEventInsteadOfLeadingSilence() {
  val rate=8000;val pcm=FloatArray(rate*20*2)
  for(i in rate*15 until rate*16) { val v=kotlin.math.sin(i*0.4).toFloat()*0.6f;pcm[i*2]=v;pcm[i*2+1]=v }
  val result=EventWindow.crop(pcm,rate)
  assertEquals(rate*8*2,result.size)
  assertTrue(result.any { kotlin.math.abs(it)>0.5f })
 }
 @Test fun shortStereoRecordingsAreNotPaddedOrTruncated() {
  val pcm=floatArrayOf(0.1f,-0.2f,0.3f,-0.4f)
  assertSame(pcm,EventWindow.crop(pcm,44100))
 }
}
