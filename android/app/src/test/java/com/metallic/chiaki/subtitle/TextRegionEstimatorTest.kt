// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TextRegionEstimatorTest
{
	private val w = 1280
	private val h = 252

	/** Koyu, hafif gürültülü zemin. */
	private fun background(seed: Int = 1, base: Int = 30, noise: Int = 10): ByteArray
	{
		var s = seed
		return ByteArray(w * h) {
			s = s * 1103515245 + 12345
			(base + ((s ushr 16) and 0x7FFF) % (noise + 1)).toByte()
		}
	}

	/**
	 * Sentetik yazı satırı: [x0, x1) aralığında [glyphH] yüksekliğinde, 3 px kalınlıkta dikey çizgilerden
	 * oluşan "harfler" (her 9 px'de bir), her 6 harfte bir 12 px kelime boşluğu.
	 */
	private fun drawLine(img: ByteArray, x0: Int, x1: Int, top: Int, glyphH: Int, value: Int = 235)
	{
		var x = x0
		var n = 0
		while(x + 3 <= x1)
		{
			for(y in top until top + glyphH)
				for(dx in 0 until 3)
					img[y * w + x + dx] = value.toByte()
			// yatay çubuk (e, t gibi) üstte
			for(dx in 0 until 7)
				if(x + dx < x1)
					img[(top + glyphH / 3) * w + x + dx] = value.toByte()
			n++
			x += if(n % 6 == 0) 21 else 9
		}
	}

	@Test
	fun emptyBandHasNoRegion()
	{
		assertNull(TextRegionEstimator().estimate(background(), 0, w, w, h))
	}

	@Test
	fun singleCenteredLineIsBoxedTightly()
	{
		val img = background()
		drawLine(img, 400, 880, 180, 22)
		val r = TextRegionEstimator().estimate(img, 0, w, w, h)!!
		// Yazının tamamı kutuda
		assertTrue("$r", r.left <= 400 && r.right >= 880 && r.top <= 180 && r.bottom >= 202)
		// ve kutu dar (tüm bandın küçük bir parçası)
		assertTrue("$r", r.width < 560 && r.height < 70)
		assertTrue("$r", r.left % 2 == 0 && r.top % 2 == 0 && r.width % 2 == 0 && r.height % 2 == 0)
		assertTrue("lineHeight ${r.lineHeight}", r.lineHeight in 16..30)
	}

	@Test
	fun twoLinesAreBothIncluded()
	{
		val img = background()
		drawLine(img, 300, 980, 150, 22)
		drawLine(img, 450, 830, 182, 22)
		val r = TextRegionEstimator().estimate(img, 0, w, w, h)!!
		assertTrue("$r", r.left <= 300 && r.right >= 980 && r.top <= 150 && r.bottom >= 204)
		assertTrue("$r", r.height < 110)
		// Bitişik iki satır tek dev satır sanılmamalı
		assertTrue("lineHeight ${r.lineHeight}", r.lineHeight in 14..34)
	}

	@Test
	fun cornerHudIsIgnored()
	{
		val img = background()
		drawLine(img, 500, 780, 190, 20)
		// sağ alt köşede HUD (ör. "x 25" sayaç)
		drawLine(img, 1180, 1270, 60, 20)
		val r = TextRegionEstimator().estimate(img, 0, w, w, h)!!
		assertTrue("$r", r.right < 1100)
		assertTrue("$r", r.left <= 500 && r.right >= 780)
	}

	@Test
	fun onlyHudGivesNoRegion()
	{
		val img = background()
		drawLine(img, 10, 90, 60, 20)
		assertNull(TextRegionEstimator().estimate(img, 0, w, w, h))
	}

	@Test
	fun respectsOffsetAndStride()
	{
		val stride = w + 64
		val off = 32
		val big = ByteArray(off + stride * h) { 20 }
		val img = background()
		drawLine(img, 400, 880, 180, 22)
		for(y in 0 until h)
			System.arraycopy(img, y * w, big, off + y * stride, w)
		val a = TextRegionEstimator().estimate(img, 0, w, w, h)
		val b = TextRegionEstimator().estimate(big, off, stride, w, h)
		assertEquals(a, b)
	}

	@Test
	fun largeTextIsDownscaledSmallIsNot()
	{
		val est = TextRegionEstimator()
		val small = est.toCrop(TextRegion(400, 170, 880, 214, 24), w, h, 1280)
		assertEquals(480, small.dstWidth)
		assertEquals(44, small.dstHeight)
		assertEquals(1f, small.scale, 0.0001f)

		val big = est.toCrop(TextRegion(200, 100, 1080, 240, 64), w, h, 1280)
		// 64 px satır → ~32 px
		assertEquals(0.5f, big.scale, 0.01f)
		assertTrue(big.dstWidth % 2 == 0 && big.dstHeight % 2 == 0)
		assertTrue(big.dstWidth <= big.srcWidth && big.dstHeight <= big.srcHeight)
	}

	@Test
	fun hugeRegionFallsBackToFullBand()
	{
		val c = TextRegionEstimator().toCrop(TextRegion(0, 0, 1280, 240, 0), w, h, 1280)
		assertEquals(0, c.srcLeft)
		assertEquals(0, c.srcTop)
		assertEquals(w, c.srcWidth)
		assertEquals(h, c.srcHeight)
		val none = TextRegionEstimator().toCrop(null, w, h, 960)
		assertEquals(960, none.dstWidth)
	}

	@Test
	fun cropMappingBackToFrameIsExact()
	{
		val frameW = 1280
		val frameH = 720
		val regionTop = 468
		val crop = OcrCrop(srcLeft = 300, srcTop = 120, srcWidth = 600, srcHeight = 100, dstWidth = 300, dstHeight = 50)
		// OCR görüntüsünde (0,0) → bandın (300,120) → karenin (300, 588)
		assertEquals(300f / frameW, crop.toFrameX(0f, frameW), 1e-6f)
		assertEquals(588f / frameH, crop.toFrameY(0f, regionTop, frameH), 1e-6f)
		// (300,50) = OCR görüntüsünün sağ alt köşesi → (900, 688)
		assertEquals(900f / frameW, crop.toFrameX(300f, frameW), 1e-6f)
		assertEquals(688f / frameH, crop.toFrameY(50f, regionTop, frameH), 1e-6f)
	}

	@Test
	fun estimatedCropContainsTextAfterMapping()
	{
		val img = background(seed = 7, base = 60, noise = 30)
		drawLine(img, 350, 930, 120, 48, value = 250)
		val est = TextRegionEstimator()
		val r = est.estimate(img, 0, w, w, h)!!
		val crop = est.toCrop(r, w, h, 1280)
		assertTrue("scale ${crop.scale} lh ${r.lineHeight}", crop.scale < 0.95f)
		val l = crop.toFrameX(0f, w) * w
		val rr = crop.toFrameX(crop.dstWidth.toFloat(), w) * w
		assertTrue(l <= 350 && rr >= 930)
		assertTrue(abs(rr - l - crop.srcWidth) < 0.01f)
	}

	@Test
	fun contrastStretchExpandsDimText()
	{
		val px = ByteArray(100 + 50) { if(it < 100) (if(it % 2 == 0) 90 else 130).toByte() else 128.toByte() }
		LumaCrop.stretchContrast(px, 100)
		val lo = px.take(100).minOf { it.toInt() and 0xFF }
		val hi = px.take(100).maxOf { it.toInt() and 0xFF }
		assertTrue("$lo..$hi", lo < 20 && hi > 235)
		// chroma untouched
		assertTrue(px.drop(100).all { it == 128.toByte() })
		// already full range: unchanged
		val full = ByteArray(10) { if(it % 2 == 0) 0 else 255.toByte() }
		val copy = full.copyOf()
		LumaCrop.stretchContrast(full, 10)
		assertTrue(full.contentEquals(copy))
	}
}
