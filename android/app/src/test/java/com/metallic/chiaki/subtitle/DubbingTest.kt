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
		assertEquals(VoiceProfile(false, VoiceProfile.Age.ELDER), VoiceMap.profileFor(map, "AVCI", def))
		assertEquals(VoiceProfile(true, VoiceProfile.Age.CHILD), VoiceMap.profileFor(map, "kız", def))
		assertEquals(def, VoiceMap.profileFor(map, "BILINMEYEN", def))
		assertEquals(def, VoiceMap.profileFor(map, null, def))
	}

	@Test
	fun voiceMapNormalizeIsStable()
	{
		val n = VoiceMap.normalize("avci=erkek-yasli;;kiz=kadin-cocuk")
		assertEquals("AVCI=erkek-yasli;KIZ=kadin-cocuk", n)
		assertEquals(n, VoiceMap.normalize(n))
		assertEquals("", VoiceMap.normalize(""))
	}
}
