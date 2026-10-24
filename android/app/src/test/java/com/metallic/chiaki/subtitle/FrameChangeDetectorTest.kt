package com.metallic.chiaki.subtitle

import org.junit.Assert.*
import org.junit.Test

class FrameChangeDetectorTest
{
	private val w = 256
	private val h = 64

	/** Dark frame with optional bright "text" bar between the given x/y ranges. */
	private fun frame(
		textX0: Int = -1, textX1: Int = -1, textY0: Int = 20, textY1: Int = 40,
		base: Int = 20, noise: Int = 0, stride: Int = w, offset: Int = 0
	): ByteArray
	{
		val buf = ByteArray(offset + stride * h)
		var seed = 12345
		for(y in 0 until h)
			for(x in 0 until w)
			{
				seed = seed * 1103515245 + 12345
				val n = if(noise > 0) ((seed ushr 16) and 0x7fff) % (noise * 2 + 1) - noise else 0
				val text = x in textX0 until textX1 && y in textY0 until textY1 && (x / 3) % 2 == 0
				val v = (if(text) 235 else base) + n
				buf[offset + y * stride + x] = v.coerceIn(0, 255).toByte()
			}
		return buf
	}

	@Test
	fun identicalFramesAreUnchanged()
	{
		val d = FrameChangeDetector()
		val a = d.computeSignature(frame(60, 200), w, h)
		val b = d.computeSignature(frame(60, 200), w, h)
		assertEquals(0, d.changedCells(a, b))
		assertFalse(d.isChanged(a, b))
	}

	@Test
	fun videoNoiseIsIgnored()
	{
		val d = FrameChangeDetector()
		val a = d.computeSignature(frame(60, 200), w, h)
		val b = d.computeSignature(frame(60, 200, noise = 10), w, h)
		assertFalse(d.isChanged(a, b))
	}

	@Test
	fun appearingAndDisappearingTextIsDetected()
	{
		val d = FrameChangeDetector()
		val empty = d.computeSignature(frame(), w, h)
		val text = d.computeSignature(frame(60, 200), w, h)
		assertTrue(d.isChanged(empty, text))
		assertTrue(d.isChanged(text, empty))
	}

	@Test
	fun typewriterGrowthIsDetected()
	{
		val d = FrameChangeDetector()
		val a = d.computeSignature(frame(60, 120), w, h)
		val b = d.computeSignature(frame(60, 140), w, h)
		assertTrue(d.isChanged(a, b))
	}

	@Test
	fun respectsRowStrideAndOffset()
	{
		val d = FrameChangeDetector()
		val plain = d.computeSignature(frame(60, 200), w, h)
		val strided = d.computeSignature(frame(60, 200, stride = w + 64, offset = 100), w, h, rowStride = w + 64, offset = 100)
		assertTrue(plain.contentEquals(strided))
	}

	@Test
	fun nullSignatureCountsAsChanged()
	{
		val d = FrameChangeDetector()
		assertTrue(d.isChanged(null, IntArray(d.size)))
	}

	@Test
	fun schedulerRunsOnChangeFallbackAndNeverWhileBusy()
	{
		val d = FrameChangeDetector()
		val s = OcrScheduler(d, fallbackMs = 1000)
		val empty = d.computeSignature(frame(), w, h)
		val text = d.computeSignature(frame(60, 200), w, h)

		assertTrue(s.shouldRun(empty, 0, ocrBusy = false))       // first frame
		assertFalse(s.shouldRun(empty, 80, ocrBusy = false))     // unchanged
		assertFalse(s.shouldRun(text, 160, ocrBusy = true))      // changed, but OCR in flight
		assertTrue(s.shouldRun(text, 240, ocrBusy = false))      // changed -> run
		assertFalse(s.shouldRun(text, 320, ocrBusy = false))     // unchanged since that OCR
		assertFalse(s.shouldRun(text, 1200, ocrBusy = false))    // < 1000 ms since last OCR
		assertTrue(s.shouldRun(text, 1240, ocrBusy = false))     // fallback
		s.reset()
		assertTrue(s.shouldRun(text, 1300, ocrBusy = false))
	}

	@Test
	fun schedulerRespectsMinGap()
	{
		val d = FrameChangeDetector()
		val s = OcrScheduler(d, fallbackMs = 1000, minGapMs = 150)
		val empty = d.computeSignature(frame(), w, h)
		val text = d.computeSignature(frame(60, 200), w, h)
		assertTrue(s.shouldRun(empty, 0, ocrBusy = false))
		assertFalse(s.shouldRun(text, 100, ocrBusy = false))
		assertTrue(s.shouldRun(text, 150, ocrBusy = false))
	}

	@Test
	fun boxChangedFractionLooksOnlyInsideBox()
	{
		val d = FrameChangeDetector()
		val text = d.computeSignature(frame(64, 192, 16, 48), w, h)
		val empty = d.computeSignature(frame(), w, h)
		// text box normalized to the region: x 0.25..0.75, y 0.25..0.75
		assertTrue(d.changedFraction(text, empty, 0.25f, 0.25f, 0.75f, 0.75f) > 0.5f)
		// change only outside the box (e.g. background motion at the far left)
		val motion = d.computeSignature(frame(0, 40, 0, 64), w, h)
		assertEquals(0f, d.changedFraction(empty, motion, 0.25f, 0.25f, 0.75f, 0.75f), 0.0001f)
		assertTrue(d.isChanged(empty, motion))
	}
}

