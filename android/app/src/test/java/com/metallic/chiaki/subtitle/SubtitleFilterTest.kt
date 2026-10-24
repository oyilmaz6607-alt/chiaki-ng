package com.metallic.chiaki.subtitle

import org.junit.Assert.*
import org.junit.Test

class SubtitleFilterTest
{
	private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float) =
		OcrLine(text, left, top, right, bottom)

	@Test
	fun picksCenteredTwoLineSubtitleAndIgnoresHud()
	{
		val lines = listOf(
			line("AMMO 30/120", 0.85f, 0.10f, 0.98f, 0.18f),          // HUD in corner
			line("We need to get to the bridge", 0.25f, 0.62f, 0.75f, 0.72f),
			line("before the storm hits.", 0.32f, 0.74f, 0.68f, 0.84f),
			line("|| ==", 0.45f, 0.90f, 0.55f, 0.95f)                 // noise
		)
		val c = SubtitleFilter.pickSubtitle(lines)
		assertNotNull(c)
		assertEquals("We need to get to the bridge before the storm hits.", c!!.text)
		assertEquals(0.62f, c.top, 0.001f)
		assertEquals(0.84f, c.bottom, 0.001f)
	}

	@Test
	fun returnsNullWithoutSubtitle()
	{
		assertNull(SubtitleFilter.pickSubtitle(listOf(line("HP 100", 0.02f, 0.1f, 0.1f, 0.2f))))
		assertNull(SubtitleFilter.pickSubtitle(emptyList()))
	}

	@Test
	fun ocrJitterCountsAsSameSubtitle()
	{
		assertTrue(SubtitleFilter.isSameSubtitle("Where are you going?", "Where are y0u going ?"))
		assertFalse(SubtitleFilter.isSameSubtitle("Where are you going?", "I don't know yet."))
	}

	@Test
	fun trackerWaitsForStableTextThenHides()
	{
		val t = SubtitleTracker(stableFrames = 2, emptyFramesToHide = 2)
		val a = SubtitleCandidate("Hello there, friend.", 0.3f, 0.6f, 0.7f, 0.7f)
		assertEquals(SubtitleTracker.Action.None, t.onFrame(a))
		assertTrue(t.onFrame(a) is SubtitleTracker.Action.Show)
		assertTrue(t.onFrame(a) is SubtitleTracker.Action.Keep)
		assertEquals(SubtitleTracker.Action.None, t.onFrame(null))
		assertEquals(SubtitleTracker.Action.Hide, t.onFrame(null))
	}
}
