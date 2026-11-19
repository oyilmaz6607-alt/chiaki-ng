// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import org.junit.Assert.*
import org.junit.Test

class DubbingTest
{
	@Test
	fun profileKeysRoundTrip()
	{
		assertEquals(6, VoiceProfile.ALL.size)
		for(p in VoiceProfile.ALL)
			assertEquals(p, VoiceProfile.parse(p.key))
		assertEquals("kadin-cocuk", VoiceProfile(true, VoiceProfile.Age.CHILD).key)
		assertNull(VoiceProfile.parse("robot"))
		assertNull(VoiceProfile.parse(null))
		assertEquals(VoiceProfile(false, VoiceProfile.Age.ADULT), VoiceProfile.parse(" Erkek-Yetiskin "))
	}

	@Test
	fun childHigherPitchElderSlower()
	{
		val child = VoiceProfile(false, VoiceProfile.Age.CHILD)
		val man = VoiceProfile(false, VoiceProfile.Age.ADULT)
		val oldMan = VoiceProfile(false, VoiceProfile.Age.ELDER)
		assertTrue(child.pitch > man.pitch)
		assertTrue(oldMan.pitch < man.pitch)
		assertTrue(oldMan.rate < man.rate)
	}

	@Test
	fun speakerIsParsedFromEnglishAndStrippedFromTurkish()
	{
		val l = DubbingParser.parse("JOEL: Get down!", "JOEL: Yere eğil!")
		assertEquals("JOEL", l.speaker)
		assertEquals("Yere eğil!", l.text)
	}

	@Test
	fun lineWithoutSpeakerKeepsFullText()
	{
		val l = DubbingParser.parse("Who are you?", "Sen kimsin?")
		assertNull(l.speaker)
		assertEquals("Sen kimsin?", l.text)
	}

	@Test
	fun mixedCaseLabelIsNotASpeaker()
	{
		val l = DubbingParser.parse("Note: keep going", "Not: devam et")
		assertNull(l.speaker)
		assertEquals("Not: devam et", l.text)
	}

	@Test
	fun turkishSpeakerNamesUpperCaseCorrectly()
	{
		assertEquals("IBRAHIM", DubbingParser.normalizeName("ibrahim"))
		assertEquals("KIZ", DubbingParser.normalizeName("  kiz "))
	}

	@Test
	fun voiceMapParsesAndFallsBackToDefault()
	{
		val map = VoiceMap.parse("avci=erkek-yasli; KIZ=kadin-cocuk;bozuk;X=yanlis")
		assertEquals(2, map.size)
		val def = VoiceProfile(false, VoiceProfile.Age.ADULT)
		assertEquals(VoiceProfile(false, VoiceProfile.Age.ELDER), VoiceMap.voiceFor(map, "AVCI", SpeakerVoice(def)).profile)
		assertEquals(VoiceProfile(true, VoiceProfile.Age.CHILD), VoiceMap.voiceFor(map, "kız", SpeakerVoice(def)).profile)
		assertEquals(SpeakerVoice(def), VoiceMap.voiceFor(map, "BILINMEYEN", SpeakerVoice(def)))
		assertEquals(SpeakerVoice(def), VoiceMap.voiceFor(map, null, SpeakerVoice(def)))
	}

	@Test
	fun voiceMapNormalizeIsStable()
	{
		val n = VoiceMap.normalize("avci=erkek-yasli;;kiz=kadin-cocuk")
		assertEquals("AVCI=erkek-yasli;KIZ=kadin-cocuk", n)
		assertEquals(n, VoiceMap.normalize(n))
		assertEquals("", VoiceMap.normalize(""))
	}

	@Test
	fun panIsParsedAndNormalized()
	{
		val map = VoiceMap.parse("avci=erkek-yasli@sol;kiz=kadin-cocuk@sag;x=erkek-yetiskin@yanlis")
		assertEquals(Pan.LEFT, map["AVCI"]!!.pan)
		assertEquals(Pan.RIGHT, map["KIZ"]!!.pan)
		assertEquals(Pan.CENTER, map["X"]!!.pan)
		assertEquals(-1f, Pan.LEFT.value, 0f)
		assertEquals("AVCI=erkek-yasli@sol;KIZ=kadin-cocuk@sag;X=erkek-yetiskin", VoiceMap.normalize("avci=erkek-yasli@sol;kiz=kadin-cocuk@sag;x=erkek-yetiskin@yanlis"))
	}
}
