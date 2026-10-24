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

	@Test
	fun defaultTrackerShowsOnFirstDetection()
	{
		val t = SubtitleTracker()
		val a = SubtitleCandidate("Hello there, friend.", 0.3f, 0.6f, 0.7f, 0.7f)
		assertTrue(t.onFrame(a) is SubtitleTracker.Action.Show)
		assertTrue(t.isShowing)
		assertTrue(t.onFrame(a.copy(text = "Hello there, fr1end.")) is SubtitleTracker.Action.Keep)
		val b = SubtitleCandidate("Get down!", 0.4f, 0.6f, 0.6f, 0.7f)
		val show = t.onFrame(b)
		assertTrue(show is SubtitleTracker.Action.Show)
		assertEquals("Get down!", (show as SubtitleTracker.Action.Show).candidate.text)
	}

	@Test
	fun trackerHidesImmediatelyWhenRegionChanged()
	{
		val t = SubtitleTracker()
		val a = SubtitleCandidate("Hello there, friend.", 0.3f, 0.6f, 0.7f, 0.7f)
		assertTrue(t.onFrame(a) is SubtitleTracker.Action.Show)
		assertEquals(SubtitleTracker.Action.Hide, t.onFrame(null, regionChanged = true))
		assertFalse(t.isShowing)
		assertEquals(SubtitleTracker.Action.None, t.onFrame(null, regionChanged = true))
	}

	@Test
	fun trackerToleratesSingleOcrFlakeOnUnchangedRegion()
	{
		val t = SubtitleTracker()
		val a = SubtitleCandidate("Hello there, friend.", 0.3f, 0.6f, 0.7f, 0.7f)
		assertTrue(t.onFrame(a) is SubtitleTracker.Action.Show)
		assertEquals(SubtitleTracker.Action.None, t.onFrame(null, regionChanged = false))
		assertTrue(t.onFrame(a) is SubtitleTracker.Action.Keep)
		assertEquals(SubtitleTracker.Action.None, t.onFrame(null))
		assertEquals(SubtitleTracker.Action.Hide, t.onFrame(null))
	}

	@Test
	fun trackerUpdatesOnTypewriterGrowth()
	{
		val t = SubtitleTracker()
		val box = SubtitleCandidate("", 0.3f, 0.6f, 0.7f, 0.7f)
		val first = t.onFrame(box.copy(text = "We need to get to the bridge before"))
		assertTrue(first is SubtitleTracker.Action.Show && !first.grown)
		// similar enough to be "the same", but more words revealed -> show again
		val grown = t.onFrame(box.copy(text = "We need to get to the bridge before the"))
		assertTrue(grown is SubtitleTracker.Action.Show && grown.grown)
		// same full text again -> keep (no re-translation)
		assertTrue(t.onFrame(box.copy(text = "We need to get to the bridge before the")) is SubtitleTracker.Action.Keep)
		// a much longer reveal is a different text -> show
		assertTrue(t.onFrame(box.copy(text = "We need to get to the bridge before the storm hits us all.")) is SubtitleTracker.Action.Show)
	}

	@Test
	fun growthIgnoresPunctuationJitter()
	{
		assertFalse(SubtitleTracker.isGrowth("Where are you going?", "Where are you going ?!"))
		assertFalse(SubtitleTracker.isGrowth("Where are you going?", "Where are y0u going"))
		assertTrue(SubtitleTracker.isGrowth("Where are you", "Where are you going"))
	}
}
