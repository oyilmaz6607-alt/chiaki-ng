// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import org.junit.Assert.*
import org.junit.Test

class ProEngineTest
{
	private val key = "AIzaSyA1234567890abcdef"

	@Test
	fun defaultEngineFollowsSubscriptionThenKey()
	{
		assertEquals("pro", TranslationSettings.effectiveEngine(null, key, subscribed = true))
		assertEquals("pro", TranslationSettings.effectiveEngine(null, null, subscribed = true))
		assertEquals("gemini", TranslationSettings.effectiveEngine(null, key, subscribed = false))
		assertEquals("mlkit", TranslationSettings.effectiveEngine(null, null, subscribed = false))
		assertEquals("pro", TranslationSettings.chosenEngine(null, null, subscribed = true))
		assertEquals("gemini", TranslationSettings.chosenEngine("gemini", null, subscribed = true))
	}

	@Test
	fun explicitChoiceIsRespected()
	{
		assertEquals("pro", TranslationSettings.effectiveEngine("pro", null, subscribed = true))
		// Pro seçili ama abonelik yok: anahtar varsa Gemini, yoksa cihaz içi
		assertEquals("gemini", TranslationSettings.effectiveEngine("pro", key, subscribed = false))
		assertEquals("mlkit", TranslationSettings.effectiveEngine("pro", "", subscribed = false))
		// abone olsa da kullanıcı açıkça başka motor seçtiyse o kullanılır
		assertEquals("gemini", TranslationSettings.effectiveEngine("gemini", key, subscribed = true))
		assertEquals("mlkit", TranslationSettings.effectiveEngine("mlkit", key, subscribed = true))
		assertTrue(TranslationSettings.isRemote("pro"))
		assertTrue(TranslationSettings.isRemote("gemini"))
		assertFalse(TranslationSettings.isRemote("mlkit"))
	}

	@Test
	fun proPartialDoesNotReplaceDraftAndFinalWins()
	{
		val s = RefineState()
		val g = s.newGeneration()
		assertTrue(s.offer(g, RefineState.Source.PRO_PARTIAL))
		assertTrue(s.offer(g, RefineState.Source.PRO_PARTIAL))
		// geç gelen cihaz içi taslak yarım Pro metnini ezmez
		assertFalse(s.offer(g, RefineState.Source.MLKIT))
		assertTrue(s.offer(g, RefineState.Source.PRO))
		assertFalse(s.offer(g, RefineState.Source.PRO_PARTIAL))
		assertFalse(s.offer(g, RefineState.Source.CACHE_MLKIT))

		val g2 = s.newGeneration()
		assertTrue(s.offer(g2, RefineState.Source.MLKIT))
		// tam taslak ekrandayken yarım cümle gösterilmez; son sonuç gösterilir
		assertFalse(s.offer(g2, RefineState.Source.PRO_PARTIAL))
		assertTrue(s.offer(g2, RefineState.Source.PRO))
		assertTrue(s.isFinal(RefineState.Source.CACHE_PRO))
	}

	@Test
	fun dropPartialLetsFallbackShow()
	{
		val s = RefineState()
		val g = s.newGeneration()
		assertTrue(s.offer(g, RefineState.Source.PRO_PARTIAL))
		s.dropPartial(g - 1)
		assertEquals(RefineState.Source.PRO_PARTIAL, s.shown)
		s.dropPartial(g)
		assertNull(s.shown)
		assertTrue(s.offer(g, RefineState.Source.MLKIT))
		// taslak ekrandayken dropPartial bir şey değiştirmez
		s.dropPartial(g)
		assertEquals(RefineState.Source.MLKIT, s.shown)
	}
}
