// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import org.junit.Assert.*
import org.junit.Test

class ProRoutingTest
{
	@Test
	fun thresholds()
	{
		assertEquals(3, ProRouting.SHORT_MAX_WORDS)
		assertEquals(18, ProRouting.SHORT_MAX_CHARS)
		assertEquals(6, ProRouting.CONTEXT_MIN_WORDS)
		assertEquals(2, ProRouting.CONTEXT_PAIRS)
		assertEquals(160, ProRouting.CONTEXT_CHARS)
		assertEquals(ProRouting.CONTEXT_PAIRS, ProApi.MAX_CONTEXT)
	}

	@Test
	fun shortLinesStayOnDevice()
	{
		for(t in listOf("Yes.", "Get down!", "Who are you?", "Options", "", "   ", "Thank you so much.", "120 / 250", "03:45 - 12,500",
				"PRESS X TO CONTINUE", "PRESS OPTIONS TO SKIP", "SAVING - DO NOT TURN OFF", "JOEL: Get down!"))
			assertTrue("'$t'", ProRouting.isOnDevice(t))
		// 18 karakter sınırı: 4 kelime ama 18 karakter → cihaz içi; 19 karakter → yapay zekâ
		assertTrue(ProRouting.isOnDevice("I am so very tired"))
		assertFalse(ProRouting.isOnDevice("I am so very tired."))
		for(t in listOf("Where do you think you're going?", "We have to leave before they find us.",
				"I TOLD YOU WE SHOULD NEVER HAVE COME BACK HERE", "Don't let them see you, Ellie."))
			assertFalse("'$t'", ProRouting.isOnDevice(t))
		assertFalse("19 karakter, 4 kelime", ProRouting.isOnDevice("Where are you going"))
	}

	@Test
	fun contextOnlyWhenUseful()
	{
		assertTrue(ProRouting.wantsContext("I told you we should never trust them"))
		assertTrue(ProRouting.wantsContext("That's what I was trying to say"))
		assertTrue(ProRouting.wantsContext("and then we ran to the car"))
		assertTrue(ProRouting.wantsContext("…and then we ran to the car"))
		assertTrue(ProRouting.wantsContext("But we never made it out alive"))
		assertTrue(ProRouting.wantsContext("So we wait until the sun sets"))
		assertFalse(ProRouting.wantsContext("Welcome to the old harbor district today"))
		assertFalse("kısa", ProRouting.wantsContext("Give it to me"))
		assertFalse("alt dize eşleşmez", ProRouting.wantsContext("Withering heathens hit the shore again"))
		val pairs = (1..4).map { SubtitlePair("en$it " + "x".repeat(200), "tr$it") } + SubtitlePair(" ", "boş")
		val ctx = ProRouting.contextFor("Give it to me now please, they said", pairs)
		assertEquals(listOf("tr3", "tr4"), ctx.map { it.turkish })
		assertTrue(ctx.all { it.english.length == 160 })
		assertEquals(emptyList<SubtitlePair>(), ProRouting.contextFor("Welcome to the old harbor district today", pairs))
		assertEquals(emptyList<SubtitlePair>(), ProRouting.contextFor("I told you they would never let us leave", emptyList()))
	}
}
